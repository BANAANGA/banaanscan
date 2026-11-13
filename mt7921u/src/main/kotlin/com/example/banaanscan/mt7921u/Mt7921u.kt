// Ported from the Linux mt76 driver (mt792x_usb.c, mt7921/usb.c, mt792x_core.c, mt792x_regs.h, usb.c),
// Copyright (C) 2018 Lorenzo Bianconi and (C) 2020-2023 MediaTek Inc. BSD-3-Clause-Clear: see NOTICE.
package com.example.banaanscan.mt7921u

/**
 * The few USB operations the driver needs. The PC prototype implements this with libusb, the Android app with its
 * USB host API, so everything above it is shared.
 */
interface UsbIo {
    fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int = 500): ByteArray?
    fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int = 500): Int
    fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int = 1000): Int
    /** Returns the number of bytes read, 0 on timeout. */
    fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int
}

class Mt7921Exception(message: String) : Exception(message)

data class Capabilities(val macAddress: String?, val has6Ghz: Boolean?, val elements: Int)

data class BringUp(val chipId: Int, val revision: Int, val patchBuild: String, val firmware: String, val capabilities: Capabilities)

/**
 * Starts an MT7921U from power-on to running firmware, following the Linux driver's probe sequence.
 * This stage only loads firmware and asks the chip about itself: the radio isn't used and nothing is transmitted.
 */
class Mt7921u(private val usb: UsbIo, private val log: (String) -> Unit = {}) {
    private var seq = 0
    private val rxBuffer = ByteArray(32 * 1024)

    // ---------------------------------------------------------------- registers (mt792xu_rr / _wr / uhw)
    fun rr(addr: Long): Long {
        val d = usb.controlIn(TYPE_VENDOR_IN, VEND_READ_EXT, (addr ushr 16).toInt(), (addr and 0xffff).toInt(), 4)
            ?: throw Mt7921Exception("Register read 0x%08x failed".format(addr))
        return le32(d)
    }

    fun wr(addr: Long, value: Long) {
        usb.controlOut(TYPE_VENDOR_OUT, VEND_WRITE_EXT, (addr ushr 16).toInt(), (addr and 0xffff).toInt(), le32Bytes(value))
    }

    private fun uhwRr(addr: Long): Long {
        val d = usb.controlIn(TYPE_UHW_IN, VEND_DEV_MODE, (addr ushr 16).toInt(), (addr and 0xffff).toInt(), 4)
            ?: throw Mt7921Exception("UHW register read 0x%08x failed".format(addr))
        return le32(d)
    }

    private fun uhwWr(addr: Long, value: Long) {
        usb.controlOut(TYPE_UHW_OUT, VEND_WRITE, (addr ushr 16).toInt(), (addr and 0xffff).toInt(), le32Bytes(value))
    }

    private fun rmw(addr: Long, mask: Long, value: Long) = wr(addr, (rr(addr) and mask.inv()) or value)
    private fun set(addr: Long, bits: Long) = rmw(addr, 0, bits)
    private fun clear(addr: Long, bits: Long) = rmw(addr, bits, 0)

    private fun poll(addr: Long, mask: Long, value: Long, timeoutMs: Long): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (rr(addr) and mask == value) return true
            if (System.currentTimeMillis() > end) return false
            Thread.sleep(10)
        }
    }

    // ---------------------------------------------------------------- bring-up
    fun bringUp(patch: PatchFile, ram: RamFile): BringUp {
        val chip = rr(HW_CHIPID).toInt()
        val rev = (rr(HW_REV) and 0xff).toInt()
        log("chip 0x%04x revision 0x%02x".format(chip, rev))
        if (chip != 0x7961) throw Mt7921Exception("Not an MT7921 (chip id 0x%04x)".format(chip))

        // Firmware still running from an earlier driver: reset the Wi-Fi subsystem first, as Linux does.
        if (rr(CONN_ON_MISC) and FW_N9_RDY != 0L) {
            log("firmware already running: resetting the Wi-Fi subsystem")
            wfsysReset()
        }
        powerOn()
        dmaInit()
        set(UDMA_TX_QSEL, FW_DL_EN)
        loadFirmware(patch, ram)
        val caps = nicCapability()
        clear(UDMA_TX_QSEL, FW_DL_EN)
        return BringUp(chip, rev, patch.buildDate, "${ram.version} (${ram.buildDate})", caps)
    }

    private fun wfsysReset() {
        set(UDMA_WLCFG_0, WL_RX_FLUSH)
        if (!poll(UDMA_WLCFG_0, WL_RX_BUSY or WL_TX_BUSY, 0, 1000)) log("warning: USB DMA busy before reset")
        epResetOptions(false)
        uhwWr(CBTOP_RGU_WF_SUBSYS_RST, uhwRr(CBTOP_RGU_WF_SUBSYS_RST) or 1)
        Thread.sleep(1)
        uhwWr(CBTOP_RGU_WF_SUBSYS_RST, uhwRr(CBTOP_RGU_WF_SUBSYS_RST) and 1L.inv())
        uhwWr(UDMA_CONN_INFRA_STATUS_SEL, 0)
        repeat(2) {
            if (uhwRr(UDMA_CONN_INFRA_STATUS) and WFSYS_INIT_DONE != 0L) return
            Thread.sleep(100)
        }
        throw Mt7921Exception("Wi-Fi subsystem reset didn't complete")
    }

    private fun epResetOptions(reset: Boolean) {
        val bits = 0x3f0L or 0x700000L // out bulk endpoints 4-9, in endpoints 4-6
        val v = uhwRr(SSUSB_EPCTL_CSR_EP_RST_OPT)
        uhwWr(SSUSB_EPCTL_CSR_EP_RST_OPT, if (reset) v or bits else v and bits.inv())
    }

    private fun powerOn() {
        usb.controlOut(TYPE_VENDOR_OUT, VEND_POWER_ON, 0, 1, ByteArray(0))
        if (!poll(CONN_ON_MISC, FW_PWR_ON, FW_PWR_ON, 500)) throw Mt7921Exception("Timeout waiting for power on")
        log("powered on")
    }

    private fun dmaInit() {
        // mt792xu_wfdma_init
        for ((ring, base) in listOf(0 to 0x080L, 1 to 0x0c0L, 2 to 0x100L, 3 to 0x140L, 4 to 0x180L, 16 to 0x280L, 17 to 0x2c0L)) {
            rmw(UWFDMA0 + 0x600 + ring * 4, 0xffff00ffL, (4L) or (base shl 16))
        }
        clear(UWFDMA0_GLO_CFG, 1L shl 27)
        set(UWFDMA0_GLO_CFG, (1L shl 28) or (1L shl 21) or (1L shl 9) or 1L or (1L shl 2))
        rmw(DMASHDL + 0x010, 0xffff0000L, 0xffe00000L)
        clear(DMASHDL + 0x00c, 1L shl 16)
        rmw(DMASHDL + 0x01c, 0x0fff0fffL, 1L)
        for (i in 0 until 5) wr(DMASHDL + 0x020 + i * 4, 0x3L or (0xfffL shl 16))
        for (i in 5 until 16) wr(DMASHDL + 0x020 + i * 4, 0)
        wr(DMASHDL + 0x060, 0x32013201L)
        wr(DMASHDL + 0x064, 0x32013201L)
        wr(DMASHDL + 0x068, 0x55555444L)
        wr(DMASHDL + 0x06c, 0x55555444L)
        wr(DMASHDL + 0x070, 0x76540132L)
        wr(DMASHDL + 0x074, 0xFEDCBA98L)
        set(WFDMA_DUMMY_CR, 1L shl 1)
        // mt792xu_dma_init
        clear(UDMA_WLCFG_0, WL_RX_FLUSH)
        set(UDMA_WLCFG_0, WL_RX_EN or WL_TX_EN or WL_RX_MPSZ_PAD0 or TICK_1US_EN)
        rmw(UDMA_WLCFG_1, 0x0fffff00L, 50000L shl 8)
        set(UDMA_WLCFG_0, WL_TX_TMOUT_FUNC_EN)
        clear(UDMA_WLCFG_0, 0xffffL)
        clear(UDMA_WLCFG_1, 0xffL)
        // Firmware events on bulk-in endpoint 4, next to received frames.
        if (!poll(UWFDMA0_GLO_CFG, 1L shl 3, 0, 1000)) throw Mt7921Exception("RX DMA stayed busy")
        clear(UWFDMA0_GLO_CFG, 1L shl 2)
        set(WFDMA_HOST_CONFIG, 1L shl 6)
        set(UWFDMA0_GLO_CFG, 1L shl 2)
        epResetOptions(false)
        log("DMA initialised")
    }

    // ---------------------------------------------------------------- MCU messaging
    private fun nextSeq(): Int {
        seq = (seq + 1) and 0xf
        if (seq == 0) seq = 1
        return seq
    }

    /** Sends a command; if [wait], returns the reply with the same sequence number. */
    fun send(cid: Int, payload: ByteArray = ByteArray(0), wait: Boolean = true, ce: Boolean = false, timeoutMs: Long = 3000): Mcu.Event? {
        val s = nextSeq()
        val frame = Mcu.command(cid, s, payload, ce = ce)
        if (usb.bulkOut(EP_OUT_INBAND_CMD, frame) != frame.size) throw Mt7921Exception("Sending command 0x%02x failed".format(cid))
        if (!wait) return null
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            val n = usb.bulkIn(EP_IN_PKT_RX, rxBuffer, 200)
            if (n <= 0) continue
            val event = Mcu.parseEvent(rxBuffer, n) ?: continue
            if (event.seq == s) return event
            log("  (ignored event 0x%02x seq %d)".format(event.eid, event.seq))
        }
        throw Mt7921Exception("Timeout waiting for the reply to command 0x%02x (seq %d)".format(cid, s))
    }

    private fun sendFirmware(region: FirmwareRegion, start: Int) {
        // Linux only checks that this gets a reply, not what it says.
        send(start, Mcu.u32s(region.address, region.data.size.toLong(), region.mode))
        var at = 0
        while (at < region.data.size) {
            val chunk = region.data.copyOfRange(at, minOf(at + MAX_CHUNK, region.data.size))
            val frame = Mcu.scatter(chunk)
            if (usb.bulkOut(EP_OUT_AC_BE, frame, 3000) != frame.size) throw Mt7921Exception("Firmware upload failed at byte $at")
            at += chunk.size
        }
    }

    private fun loadFirmware(patch: PatchFile, ram: RamFile) {
        send(Mcu.NIC_POWER_CTRL, byteArrayOf(1, 0, 0, 0), wait = false) // "restart"
        if (!poll(CONN_ON_MISC, 0x7, FW_PWR_ON, 1000)) log("warning: MCU not ready for firmware download")

        when (val sem = send(Mcu.PATCH_SEM_CONTROL, Mcu.u32s(1))!!.status) {
            Mcu.PATCH_IS_DL -> log("ROM patch already loaded")
            Mcu.PATCH_NOT_DL_SEM_SUCCESS -> {
                log("loading ROM patch (${patch.buildDate}, ${patch.regions.sumOf { it.data.size }} bytes)")
                patch.regions.forEach { sendFirmware(it, Mcu.PATCH_START_REQ) }
                val finish = send(Mcu.PATCH_FINISH_REQ, ByteArray(4))!!.status
                if (finish != 0) throw Mt7921Exception("Patch finish failed ($finish)")
                val rel = send(Mcu.PATCH_SEM_CONTROL, Mcu.u32s(0))!!.status
                if (rel != Mcu.PATCH_REL_SEM_SUCCESS) throw Mt7921Exception("Patch semaphore release failed ($rel)")
            }
            else -> throw Mt7921Exception("Couldn't get the patch semaphore ($sem)")
        }

        log("loading firmware ${ram.version} (${ram.regions.size} regions, ${ram.regions.sumOf { it.data.size }} bytes)")
        ram.regions.forEach { sendFirmware(it, Mcu.TARGET_ADDRESS_LEN_REQ) }
        val override = ram.startAddress
        send(Mcu.FW_START_REQ, Mcu.u32s(if (override != null) 1 else 0, override ?: 0))
        if (!poll(CONN_ON_MISC, FW_N9_RDY, FW_N9_RDY, 1500)) throw Mt7921Exception("Timeout waiting for the firmware to start")
        log("firmware running")
    }

    private fun nicCapability(): Capabilities {
        val body = send(Mcu.GET_NIC_CAPAB, ce = true)!!.body
        if (body.size < 4) return Capabilities(null, null, 0)
        val count = (body[0].toInt() and 0xff) or ((body[1].toInt() and 0xff) shl 8)
        var at = 4
        var mac: String? = null
        var sixGhz: Boolean? = null
        repeat(count) {
            if (at + 8 > body.size) return@repeat
            val type = le32(body.copyOfRange(at, at + 4)).toInt()
            val len = le32(body.copyOfRange(at + 4, at + 8)).toInt()
            at += 8
            if (len < 0 || at + len > body.size) return@repeat
            when (type) {
                NIC_CAP_MAC_ADDR -> if (len >= 6) mac = (0 until 6).joinToString(":") { "%02x".format(body[at + it]) }
                NIC_CAP_6G -> if (len >= 1) sixGhz = body[at].toInt() != 0
            }
            at += len
        }
        return Capabilities(mac, sixGhz, count)
    }

    companion object {
        const val VENDOR_ID = 0x0e8d
        const val PRODUCT_ID = 0x7961

        // Control requests (MT_VEND_*) and request types: vendor | 0x1f (device), vendor | 0x1e (USB block)
        const val TYPE_VENDOR_IN = 0xdf
        const val TYPE_VENDOR_OUT = 0x5f
        const val TYPE_UHW_IN = 0xde
        const val TYPE_UHW_OUT = 0x5e
        const val VEND_DEV_MODE = 0x01
        const val VEND_WRITE = 0x02
        const val VEND_POWER_ON = 0x04
        const val VEND_READ_EXT = 0x63
        const val VEND_WRITE_EXT = 0x66

        // Endpoints, in the order the interface lists them (mt76u_set_endpoints)
        const val EP_OUT_INBAND_CMD = 0x08
        const val EP_OUT_AC_BE = 0x04
        const val EP_IN_PKT_RX = 0x84
        const val MAX_CHUNK = 4096

        // Registers (mt792x_regs.h)
        const val HW_CHIPID = 0x70010200L
        const val HW_REV = 0x70010204L
        const val CONN_ON_MISC = 0x7c0600f0L
        const val FW_PWR_ON = 0x1L
        const val FW_N9_RDY = 0x3L
        const val UMAC = 0x74000000L
        const val UDMA_TX_QSEL = UMAC + 0x008
        const val FW_DL_EN = 1L shl 3
        const val UDMA_WLCFG_1 = UMAC + 0x00c
        const val UDMA_WLCFG_0 = UMAC + 0x018
        const val WL_TX_TMOUT_FUNC_EN = 1L shl 16
        const val WL_RX_MPSZ_PAD0 = 1L shl 18
        const val WL_RX_FLUSH = 1L shl 19
        const val TICK_1US_EN = 1L shl 20
        const val WL_RX_EN = 1L shl 22
        const val WL_TX_EN = 1L shl 23
        const val WL_RX_BUSY = 1L shl 30
        const val WL_TX_BUSY = 1L shl 31
        const val UDMA_CONN_INFRA_STATUS = UMAC + 0xa20
        const val WFSYS_INIT_DONE = 1L shl 22
        const val UDMA_CONN_INFRA_STATUS_SEL = UMAC + 0xa24
        const val SSUSB_EPCTL_CSR_EP_RST_OPT = 0x74011890L
        const val CBTOP_RGU_WF_SUBSYS_RST = 0x70002600L
        const val UWFDMA0 = 0x7c024000L
        const val UWFDMA0_GLO_CFG = UWFDMA0 + 0x208
        const val DMASHDL = 0x7c026000L
        const val WFDMA_HOST_CONFIG = 0x7c027030L
        const val WFDMA_DUMMY_CR = 0x54000120L

        const val NIC_CAP_MAC_ADDR = 7
        const val NIC_CAP_6G = 0x18

        fun le32(b: ByteArray): Long =
            (b[0].toLong() and 0xff) or ((b[1].toLong() and 0xff) shl 8) or ((b[2].toLong() and 0xff) shl 16) or ((b[3].toLong() and 0xff) shl 24)

        fun le32Bytes(v: Long) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    }
}
