package io.github.banaanga.sniffowireless.mobile.ath9k

import java.nio.ByteBuffer
import java.nio.ByteOrder

/*
 * The AR9271's USB link, ported from Linux's ath9k_htc (hif_usb.c, htc_hst.c, wmi.c; ISC licence, see NOTICE): the
 * firmware download, then HTC (the firmware's message transport, with one endpoint per service) and WMI (commands to
 * the firmware, register reads and writes among them). The chip itself is set up from the host through those register
 * writes; see Ar9271.kt.
 */

/** USB transfers, from Android's USB host API or libusb on a PC. Interrupt endpoints use the bulk calls too. */
interface UsbIo {
    fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray?
    fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int
    fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int): Int
    /** Returns the number of bytes read, 0 on timeout. */
    fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
}

class Ath9kException(message: String) : Exception(message)

internal fun be16(b: ByteArray, i: Int) = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
internal fun be32(b: ByteArray, i: Int) = ByteBuffer.wrap(b, i, 4).order(ByteOrder.BIG_ENDIAN).int

class Htc(private val usb: UsbIo, private val log: (String) -> Unit = {}) {
    /** Endpoint of each connected service, by service id. */
    val endpoints = HashMap<Int, Int>()
    private var wmiEp = -1
    private var seq = 0
    /** Firmware events (WMI ids with bit 12 set) that arrived while waiting for a command's answer; unused here. */
    var events = 0
        private set

    /** Writes the firmware into the chip's RAM and starts it (ath9k_hif_usb_download_fw). */
    fun downloadFirmware(fw: ByteArray) {
        var addr = FIRMWARE_ADDR
        var off = 0
        while (off < fw.size) {
            val n = minOf(4096, fw.size - off)
            val chunk = fw.copyOfRange(off, off + n)
            if (usb.controlOut(0x40, FIRMWARE_DOWNLOAD, addr ushr 8, 0, chunk, 2000) < 0) throw Ath9kException("Firmware download failed at 0x%x".format(addr))
            off += n
            addr += n
        }
        usb.controlOut(0x40, FIRMWARE_DOWNLOAD_COMP, FIRMWARE_TEXT ushr 8, 0, ByteArray(0), 2000)
    }

    /**
     * Whether the firmware is already running (left from a session that didn't end cleanly). The running firmware
     * reports bcdDevice 0xffff in its device descriptor, the boot ROM its own version (open-ath9k-htc-firmware,
     * bGet_descriptor_patch: "we need it to detect if FW was uploaded"). A fresh GET_DESCRIPTOR is safe in both
     * states, unlike sending anything on the command endpoint to the ROM, or the firmware download to running firmware:
     * both leave the adapter needing a replug.
     */
    fun firmwareRunning(): Boolean {
        val d = usb.controlIn(0x80, 6, 0x0100, 0, 18, 1000) ?: return false
        return (d[12].toInt() and 0xFF) == 0xFF && (d[13].toInt() and 0xFF) == 0xFF
    }

    /** One message from the register/control endpoint, or null after [timeoutMs]. */
    private fun readRegIn(timeoutMs: Int): ByteArray? {
        val buf = ByteArray(64)
        val n = usb.bulkIn(EP_REG_IN, buf, timeoutMs)
        return if (n > 0) buf.copyOf(n) else null
    }

    /** Waits for the firmware's HTC ready message after the download. Returns the credit size it reports. */
    fun waitReady(timeoutMs: Long = 3000): Int {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val m = readRegIn(200) ?: continue
            val h = header(m) ?: continue
            if (h.ep == 0 && h.payload.size >= 8 && be16(h.payload, 0) == HTC_MSG_READY) return be16(h.payload, 4)
        }
        throw Ath9kException("The firmware didn't start (no HTC ready message)")
    }

    private class Msg(val ep: Int, val flags: Int, val payload: ByteArray)

    /** Splits off the HTC frame header (and a trailer, or the watchdog pattern some control messages start with). */
    private fun header(m: ByteArray): Msg? {
        var b = m
        if (b.size >= 4 && be32(b, 0) == 0x00C60000 && b.size >= 12) b = b.copyOfRange(4, b.size)
        if (b.size < 8) return null
        val ep = b[0].toInt() and 0xFF
        if (ep == 0x99) throw Ath9kException("The adapter's firmware crashed (panic report %s)".format(b.joinToString("") { "%02x".format(it) }))
        val flags = b[1].toInt() and 0xFF
        val len = be16(b, 2)
        var payload = b.copyOfRange(8, minOf(b.size, 8 + len))
        if (flags and HTC_FLAGS_RECV_TRAILER != 0 && ep != 0) payload = payload.copyOf(maxOf(0, payload.size - (b[4].toInt() and 0xFF)))
        return Msg(ep, flags, payload)
    }

    private fun send(ep: Int, pipe: Int, payload: ByteArray) {
        val out = ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN)
        out.put(ep.toByte()).put(0).putShort(payload.size.toShort()).putInt(0).put(payload)
        usb.bulkOut(pipe, out.array(), 1000)
    }

    /** Connects a service and returns the endpoint the firmware gave it (htc_connect_service). */
    fun connectService(serviceId: Int): Int {
        val msg = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
            .putShort(HTC_MSG_CONNECT_SERVICE.toShort()).putShort(serviceId.toShort()).putShort(0)
            .put(dlPipe(serviceId).toByte()).put(ulPipe(serviceId).toByte()).put(0).put(0).array()
        send(0, EP_REG_OUT, msg)
        val end = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < end) {
            val h = readRegIn(200)?.let(::header) ?: continue
            if (h.ep != 0 || h.payload.size < 8 || be16(h.payload, 0) != HTC_MSG_CONNECT_SERVICE_RESPONSE) continue
            if (be16(h.payload, 2) != serviceId) continue
            val status = h.payload[4].toInt() and 0xFF
            val ep = h.payload[5].toInt() and 0xFF
            if (status != 0 || ep <= 0 || ep >= 22) throw Ath9kException("The firmware refused service 0x%04x (status %d)".format(serviceId, status))
            endpoints[serviceId] = ep
            if (serviceId == WMI_CONTROL_SVC) wmiEp = ep
            return ep
        }
        throw Ath9kException("No answer connecting service 0x%04x".format(serviceId))
    }

    /** Credits for the transmit pipe, then setup complete (htc_init). Linux only waits for these to be sent. */
    fun init(credits: Int = 33) {
        send(0, EP_REG_OUT, byteArrayOf(0, HTC_MSG_CONFIG_PIPE.toByte(), EP_TX.toByte(), credits.toByte()))
        readRegIn(100)  // a config-pipe answer, if the firmware sends one
        send(0, EP_REG_OUT, byteArrayOf(0, HTC_MSG_SETUP_COMPLETE.toByte()))
        readRegIn(100)
    }

    /** Sends a WMI command and returns its answer (ath9k_wmi_cmd). */
    @Synchronized
    fun wmi(cmd: Int, payload: ByteArray = ByteArray(0), timeoutMs: Long = 1000): ByteArray {
        check(wmiEp > 0) { "WMI isn't connected" }
        seq = (seq + 1) and 0xFFFF
        val body = ByteBuffer.allocate(4 + payload.size).order(ByteOrder.BIG_ENDIAN).putShort(cmd.toShort()).putShort(seq.toShort()).put(payload).array()
        send(wmiEp, EP_REG_OUT, body)
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val h = readRegIn(100)?.let(::header) ?: continue
            if (h.ep != wmiEp || h.payload.size < 4) continue
            val id = be16(h.payload, 0)
            if (id and 0x1000 != 0) {
                events++
                continue
            }
            if (be16(h.payload, 2) != seq) continue
            return h.payload.copyOfRange(4, h.payload.size)
        }
        throw Ath9kException("No answer from the firmware to WMI command %d".format(cmd))
    }

    /** Puts the chip back into its boot ROM (ath9k_hif_usb_reboot), so the next start loads the firmware afresh. */
    fun reboot() {
        runCatching { usb.bulkOut(EP_REG_OUT, byteArrayOf(-1, -1, -1, -1), 1000) }
    }

    private fun ulPipe(service: Int) = when (service) { WMI_CONTROL_SVC -> 4; in WMI_BEACON_SVC..WMI_DATA_BK_SVC -> 1; else -> 0 }
    private fun dlPipe(service: Int) = when (service) { WMI_CONTROL_SVC -> 3; in WMI_BEACON_SVC..WMI_DATA_BK_SVC -> 2; else -> 0 }

    companion object {
        const val EP_TX = 0x01
        const val EP_RX = 0x82
        const val EP_REG_IN = 0x83
        const val EP_REG_OUT = 0x04

        private const val FIRMWARE_ADDR = 0x501000
        private const val FIRMWARE_TEXT = 0x903000
        private const val FIRMWARE_DOWNLOAD = 0x30
        private const val FIRMWARE_DOWNLOAD_COMP = 0x31
        private const val HTC_FLAGS_RECV_TRAILER = 1 shl 1

        private const val HTC_MSG_READY = 1
        private const val HTC_MSG_CONNECT_SERVICE = 2
        private const val HTC_MSG_CONNECT_SERVICE_RESPONSE = 3
        private const val HTC_MSG_SETUP_COMPLETE = 4
        private const val HTC_MSG_CONFIG_PIPE = 5

        const val WMI_CONTROL_SVC = 0x0100
        const val WMI_BEACON_SVC = 0x0101
        const val WMI_CAB_SVC = 0x0102
        const val WMI_UAPSD_SVC = 0x0103
        const val WMI_MGMT_SVC = 0x0104
        const val WMI_DATA_VO_SVC = 0x0105
        const val WMI_DATA_VI_SVC = 0x0106
        const val WMI_DATA_BE_SVC = 0x0107
        const val WMI_DATA_BK_SVC = 0x0108

        // WMI commands (wmi.h)
        const val WMI_ECHO = 1
        const val WMI_GET_FW_VERSION = 3
        const val WMI_DISABLE_INTR = 4
        const val WMI_ENABLE_INTR = 5
        const val WMI_ATH_INIT = 6
        const val WMI_DRAIN_TXQ_ALL = 11
        const val WMI_START_RECV = 12
        const val WMI_STOP_RECV = 13
        const val WMI_FLUSH_RECV = 14
        const val WMI_SET_MODE = 15
        const val WMI_NODE_CREATE = 16
        const val WMI_VAP_CREATE = 19
        const val WMI_REG_READ = 20
        const val WMI_REG_WRITE = 21
        const val WMI_TARGET_IC_UPDATE = 24
        const val WMI_REG_RMW = 32
    }
}
