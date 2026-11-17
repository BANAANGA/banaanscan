package io.github.banaanga.sniffowireless.mobile.ath9k

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A frame the AR9271 received, without its FCS. */
class RxFrame(
    val data: ByteArray,
    val freqMhz: Int,
    /** dBm, as Linux reports it (noise floor -95 dBm plus the chip's RSSI); null when the chip gave none. */
    val rssi: Int?,
    val fcsError: Boolean,
    /** Legacy rate in Mb/s, or null for HT. */
    val legacyMbps: Double?,
    /** HT MCS index, or null for legacy. */
    val mcs: Int?,
    val ht40: Boolean,
    val shortGi: Boolean,
    val tsf: Long,
)

/**
 * An AR9271 in monitor mode, the way Linux's ath9k_htc drives it (htc_drv_init.c, htc_drv_main.c, htc_drv_txrx.c; ISC
 * licence): firmware download and HTC set-up, the chip's init, then a monitor interface and a full reset for every
 * channel. Receive only: nothing is ever sent on the transmit endpoint.
 */
class Ath9kHtc(private val usb: UsbIo, private val log: (String) -> Unit = {}, regTrace: ((String) -> Unit)? = null) {
    val htc = Htc(usb, log)
    val regs = WmiRegs(htc, regTrace)
    val hw = Ar9271(regs, log)
    var firmwareVersion = ""
        private set
    var channel: Channel? = null
        private set
    private var started = false

    /** Loads [firmware] (htc_9271-1.4.0.fw), connects the services and initialises the chip (ath9k_htc_probe_device). */
    fun bringUp(firmware: ByteArray) {
        if (htc.firmwareRunning()) {
            // Left running by a session that didn't end cleanly: back into the boot ROM first, as Linux does on disconnect.
            log("The adapter's firmware is still running from an earlier session; restarting it")
            htc.reboot()
            // Wait for the boot ROM: its descriptor no longer carries the firmware's 0xffff (Linux waits seconds here too).
            val end = System.currentTimeMillis() + 5000
            do Thread.sleep(250) while (htc.firmwareRunning() && System.currentTimeMillis() < end)
            Thread.sleep(250)
            if (htc.firmwareRunning()) throw Ath9kException("The adapter didn't restart; unplug it and plug it in again")
        }
        log("Loading firmware (%d bytes)".format(firmware.size))
        htc.downloadFirmware(firmware)
        htc.waitReady()
        for (svc in SERVICES) htc.connectService(svc)
        htc.init(33)
        hw.init()
        val v = htc.wmi(Htc.WMI_GET_FW_VERSION)
        firmwareVersion = "%d.%d".format(be16(v, 0), be16(v, 2))
        if (be16(v, 0) != 1 || be16(v, 2) < 3) throw Ath9kException("Firmware $firmwareVersion is too old; 1.3 or later is needed")
        hw.disable()
        log("Firmware $firmwareVersion running")
    }

    /** ath9k_htc_start, then the monitor interface (ath9k_htc_add_monitor_interface), then tuning to [chan]. */
    fun startMonitor(chan: Channel) {
        htc.wmi(Htc.WMI_FLUSH_RECV)
        hw.reset(chan)
        setMode()
        htc.wmi(Htc.WMI_ATH_INIT)
        htc.wmi(Htc.WMI_START_RECV)
        hw.startReceive()
        // ath9k_htc_update_cap_target: ampdu limit 0xffff, 255 subframes, no coex, tx chainmask 1
        htc.wmi(Htc.WMI_TARGET_IC_UPDATE, byteArrayOf(0, 0, -1, -1, -1, 0, 1, 0))
        val mac = hw.macAddress
        htc.wmi(Htc.WMI_VAP_CREATE, byteArrayOf(0, HTC_M_MONITOR.toByte()) + mac + byteArrayOf(0, 0, 0, 0))
        htc.wmi(Htc.WMI_NODE_CREATE, mac + ByteArray(6) + byteArrayOf(0, 0, 1, 0, 0, 0, 0, -1, -1, 0))
        started = true
        setChannel(chan, fast = false)
    }

    /**
     * ath9k_htc_set_channel: a full reset on the new channel, as Linux does in monitor mode (about 220 ms over USB).
     * With [fast] the chip is retuned the way Linux does it while scanning (ath9k_hw_do_fastcc, about 80 ms), but on an
     * AR9271 that left the radio deaf for about one dwell in five, so it is off by default.
     */
    fun setChannel(chan: Channel, fast: Boolean = false) {
        check(started) { "startMonitor first" }
        htc.wmi(Htc.WMI_DISABLE_INTR)
        htc.wmi(Htc.WMI_DRAIN_TXQ_ALL)
        htc.wmi(Htc.WMI_STOP_RECV)
        if (!(fast && hw.fastChannelChange(chan))) hw.reset(chan)
        htc.wmi(Htc.WMI_START_RECV)
        hw.startReceive()
        setMode()
        htc.wmi(Htc.WMI_ENABLE_INTR)
        channel = chan
    }

    private fun setMode() {
        htc.wmi(Htc.WMI_SET_MODE, byteArrayOf(0, HTC_MODE_11NG.toByte()))
    }

    // ------------------------------------------------------------------ receiving (hif_usb.c rx stream, htc_drv_txrx.c)
    private val rxBuf = ByteArray(MAX_RX_BUF_SIZE)
    private var partial: ByteArray? = null
    private var partialNeed = 0
    private val queue = ArrayDeque<RxFrame>()

    /** The next received frame, or null if none arrived within [timeoutMs]. */
    fun receive(timeoutMs: Int): RxFrame? {
        queue.removeFirstOrNull()?.let { return it }
        val end = System.currentTimeMillis() + timeoutMs
        while (queue.isEmpty()) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            val n = usb.bulkIn(Htc.EP_RX, rxBuf, left)
            if (n > 0) parseStream(rxBuf, n)
        }
        return queue.removeFirstOrNull()
    }

    /** ath9k_hif_usb_rx_stream: [len][0x4e00 tag][packet][padding to 4], packets may continue in the next transfer. */
    private fun parseStream(b: ByteArray, n: Int) {
        var i = 0
        partial?.let { p ->
            val take = minOf(partialNeed, n)
            val joined = p + b.copyOfRange(0, take)
            partialNeed -= take
            i = take
            if (partialNeed > 0) { partial = joined; return }
            partial = null
            packet(joined.copyOf(joined.size - padOf(joined.size)))
        }
        while (i + 4 <= n) {
            val len = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
            val tag = (b[i + 2].toInt() and 0xFF) or ((b[i + 3].toInt() and 0xFF) shl 8)
            if (tag != RX_STREAM_TAG || len > 2 * MAX_RX_BUF_SIZE) return  // Linux drops the rest of the transfer too
            val pad = padOf(len)
            val start = i + 4
            if (start + len > n) {
                partial = b.copyOfRange(start, n)
                partialNeed = len + pad - (n - start)
                return
            }
            packet(b.copyOfRange(start, start + len))
            i = start + len + pad
        }
    }

    private fun padOf(len: Int) = (4 - (len and 3)) and 3

    /** One HTC packet from the receive pipe: [HTC header][ath_htc_rx_status][802.11 frame + FCS]. */
    private fun packet(p: ByteArray) {
        if (p.size < 8) return
        val flags = p[1].toInt() and 0xFF
        var body = p.copyOfRange(8, p.size)
        if (flags and 2 != 0) body = body.copyOf(maxOf(0, body.size - (p[4].toInt() and 0xFF)))
        if (body.size < RX_STATUS_SIZE) return
        val st = ByteBuffer.wrap(body).order(ByteOrder.BIG_ENDIAN)
        val tsf = st.getLong(0)
        val dataLen = st.getShort(8).toInt() and 0xFFFF
        val status = body[10].toInt() and 0xFF
        val rssi = body[12].toInt()  // signed
        val rate = body[20].toInt() and 0xFF
        val moreAggr = body[24].toInt() != 0
        val rxFlags = body[26].toInt() and 0xFF
        if (dataLen != body.size - RX_STATUS_SIZE || dataLen < 10) return
        if (status and RXERR_PHY != 0) return
        val frame = body.copyOfRange(RX_STATUS_SIZE, RX_STATUS_SIZE + dataLen - FCS_LEN.coerceAtMost(dataLen))
        val isHt = rate and 0x80 != 0
        queue.addLast(RxFrame(
            data = frame,
            freqMhz = channel?.freqMhz ?: 0,
            // ath9k_cmn_process_rssi: no signal for A-MPDU subframes but the last, nor when the chip marks it bad
            rssi = if (rssi == RSSI_BAD || moreAggr) null else NOISE_FLOOR_DBM + rssi,
            fcsError = status and RXERR_CRC != 0,
            legacyMbps = if (isHt) null else LEGACY_RATES[rate],
            mcs = if (isHt) rate and 0x7f else null,
            ht40 = rxFlags and RX_2040 != 0,
            shortGi = rxFlags and RX_GI != 0,
            tsf = tsf,
        ))
    }

    /** Stops receiving and puts the chip back into its boot ROM, so the next start loads the firmware again. */
    fun close() {
        runCatching {
            htc.wmi(Htc.WMI_DISABLE_INTR)
            htc.wmi(Htc.WMI_DRAIN_TXQ_ALL)
            htc.wmi(Htc.WMI_STOP_RECV)
        }
        htc.reboot()
    }

    companion object {
        val SERVICES = listOf(Htc.WMI_CONTROL_SVC, Htc.WMI_BEACON_SVC, Htc.WMI_CAB_SVC, Htc.WMI_UAPSD_SVC, Htc.WMI_MGMT_SVC,
            Htc.WMI_DATA_BE_SVC, Htc.WMI_DATA_BK_SVC, Htc.WMI_DATA_VI_SVC, Htc.WMI_DATA_VO_SVC)
        const val HTC_M_MONITOR = 8
        const val HTC_MODE_11NG = 1
        const val RX_STREAM_TAG = 0x4e00
        const val MAX_RX_BUF_SIZE = 16384
        const val RX_STATUS_SIZE = 40
        const val FCS_LEN = 4
        const val RXERR_CRC = 0x01
        const val RXERR_PHY = 0x02
        const val RX_GI = 0x04
        const val RX_2040 = 0x08
        const val NOISE_FLOOR_DBM = Ar9271.NOISE_FLOOR
        const val RSSI_BAD = -128

        /** ath9k_legacy_rates: the chip's rate code → Mb/s (short-preamble CCK codes have bit 2 set). */
        val LEGACY_RATES = mapOf(0x1b to 1.0, 0x1a to 2.0, 0x19 to 5.5, 0x18 to 11.0, 0x1e to 2.0, 0x1d to 5.5, 0x1c to 11.0,
            0x0b to 6.0, 0x0f to 9.0, 0x0a to 12.0, 0x0e to 18.0, 0x09 to 24.0, 0x0d to 36.0, 0x08 to 48.0, 0x0c to 54.0)

        /** USB ids of AR9271 adapters (Linux's ath9k_hif_usb_ids, the AR9271 ones). */
        val USB_IDS = listOf(0x0cf3 to 0x9271, 0x0cf3 to 0x1006, 0x0846 to 0x9030, 0x07b8 to 0x9271, 0x07d1 to 0x3a10,
            0x13d3 to 0x3327, 0x13d3 to 0x3328, 0x13d3 to 0x3346, 0x13d3 to 0x3348, 0x13d3 to 0x3349, 0x13d3 to 0x3350,
            0x04ca to 0x4605, 0x040d to 0x3801, 0x0cf3 to 0xb003, 0x0cf3 to 0xb002, 0x057c to 0x8403, 0x0471 to 0x209e,
            0x1eda to 0x2315)
    }
}
