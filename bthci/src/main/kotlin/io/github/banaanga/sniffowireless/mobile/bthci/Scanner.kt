package io.github.banaanga.sniffowireless.mobile.bthci

import kotlin.random.Random

data class ControllerInfo(
    val version: LocalVersion,
    val address: String,
    val extendedScanning: Boolean,
    val codedPhy: Boolean,
    val classic: Boolean,
    val firmware: String?,
)

/** Something the adapter heard. */
sealed interface Heard

/**
 * One advertising packet (or scan response). [addressType]: 0 public, 1 random, 2/3 the same resolved from a
 * private address, 0xFF anonymous. PHY numbers: 1 = 1M, 2 = 2M, 3 = Coded (long range), 0 = none.
 */
class Advert(
    val address: String,
    val addressType: Int,
    val rssi: Int?,
    val txPower: Int?,
    val connectable: Boolean,
    val scanResponse: Boolean,
    val legacy: Boolean,
    val primaryPhy: Int,
    val secondaryPhy: Int,
    val data: ByteArray,
) : Heard {
    val ad: AdData by lazy { AdData.parse(data) }
    val random get() = addressType == 1 || addressType == 3
}

/** A classic (BR/EDR) device answering an inquiry. [eir] is its extended inquiry response, when it sent one. */
class InquiryResponse(val address: String, val rssi: Int?, val classOfDevice: Int, val eir: AdData?) : Heard

data object InquiryComplete : Heard

/**
 * Puts a controller in scanning mode and decodes what it hears. A passive LE scan only listens; an active one sends
 * scan requests (from a random address, not the adapter's own) to get scan responses, which often hold the name.
 * Classic inquiry always transmits: it's how BR/EDR discovery works.
 */
class Scanner(private val hci: Hci, private val log: (String) -> Unit = {}) {
    private var extended = false
    private val fragments = HashMap<String, ByteArray>()

    /**
     * Resets the controller, loads Realtek firmware when the chip needs it ([firmware] gets a file name such as
     * "rtl8761bu_fw.bin" and returns its bytes, or null), and turns on the events scanning produces.
     */
    fun open(firmware: (String) -> ByteArray?): ControllerInfo {
        reset()
        var version = LocalVersion.parse(hci.commandOk(Hci.READ_LOCAL_VERSION))
        log("Controller: manufacturer 0x%04x, HCI %d rev 0x%04x, LMP %d sub 0x%04x".format(
            version.manufacturer, version.hciVersion, version.hciRevision, version.lmpVersion, version.lmpSubversion))
        var loaded: String? = null
        val chip = Realtek.chipNeedingFirmware(version)
        if (chip != null) {
            val rom = Realtek.readRomVersion(hci)
            val fw = firmware(chip.firmware) ?: throw HciException("${chip.name} needs ${chip.firmware}, which is missing")
            val parsed = RtlFirmware.parse(fw)
            val image = Realtek.image(chip, parsed, chip.config?.let(firmware), rom)
            log("${chip.name} ROM version $rom: uploading ${image.size} bytes of firmware 0x%08x".format(parsed.version))
            Realtek.upload(hci, image)
            reset()
            version = LocalVersion.parse(hci.commandOk(Hci.READ_LOCAL_VERSION))
            log("Firmware running: LMP sub 0x%04x".format(version.lmpSubversion))
            loaded = "%s firmware 0x%08x".format(chip.name, parsed.version)
        } else if (Realtek.isUnknownRom(version)) {
            log("Realtek chip without known firmware; trying its ROM as is")
        }
        val address = hci.commandOk(Hci.READ_BD_ADDR).address(0)
        val features = hci.commandOk(Hci.READ_LOCAL_FEATURES)
        val le = features.bit(38)
        val classic = !features.bit(37)
        if (!le) throw HciException("This adapter doesn't support Bluetooth Low Energy")
        val leFeatures = hci.commandOk(Hci.LE_READ_LOCAL_FEATURES)
        extended = leFeatures.bit(12)
        val coded = leFeatures.bit(11)
        // Linux's default mask, plus Extended Inquiry Result (bit 38).
        hci.commandOk(Hci.SET_EVENT_MASK, byteArrayOf(-1, -1, 0xfb.toByte(), -1, 0x47, 0xf8.toByte(), 0xbf.toByte(), 0x3d))
        // LE Advertising Report (bit 1) and LE Extended Advertising Report (bit 12).
        hci.commandOk(Hci.LE_SET_EVENT_MASK, byteArrayOf(0x02, 0x10, 0, 0, 0, 0, 0, 0))
        if (classic) runCatching { hci.commandOk(Hci.WRITE_INQUIRY_MODE, byteArrayOf(2)) }
        return ControllerInfo(version, address, extended, coded, classic, loaded)
    }

    /** Resets the controller, first emptying the event endpoint; retried, since a busy adapter may miss the first. */
    private fun reset() {
        repeat(3) { attempt ->
            hci.flush()
            try {
                hci.commandOk(Hci.RESET, timeoutMs = if (attempt == 0) 1000 else 3000)
                return
            } catch (e: HciException) {
                if (attempt == 2) throw e
                log("No answer to reset, trying again")
            }
        }
    }

    /** [coded] also listens for long-range (Coded PHY) advertising, when the adapter supports extended scanning. */
    fun startLe(active: Boolean, coded: Boolean) {
        val type: Byte = if (active) 1 else 0
        var own: Byte = 0
        if (active) {
            // A random static address: the two top bits set.
            val a = Random.nextBytes(6).also { it[5] = (it[5].u8 or 0xC0).toByte() }
            hci.commandOk(Hci.LE_SET_RANDOM_ADDRESS, a)
            own = 1
        }
        if (extended) {
            val phys = if (coded) 0x05 else 0x01
            val per = byteArrayOf(type) + le16(0x0060) + le16(if (coded) 0x0030 else 0x0060)
            hci.commandOk(Hci.LE_SET_EXT_SCAN_PARAMETERS, byteArrayOf(own, 0, phys.toByte()) + per + if (coded) per else ByteArray(0))
            hci.commandOk(Hci.LE_SET_EXT_SCAN_ENABLE, byteArrayOf(1, 0, 0, 0, 0, 0))
        } else {
            hci.commandOk(Hci.LE_SET_SCAN_PARAMETERS, byteArrayOf(type) + le16(0x0060) + le16(0x0060) + byteArrayOf(own, 0))
            hci.commandOk(Hci.LE_SET_SCAN_ENABLE, byteArrayOf(1, 0))
        }
    }

    /** General inquiry for about 10 s; [InquiryComplete] says when to start the next one. */
    fun startInquiry() {
        hci.commandOk(Hci.INQUIRY, byteArrayOf(0x33, 0x8b.toByte(), 0x9e.toByte(), 0x08, 0x00))
    }

    fun stop() {
        runCatching { hci.command(Hci.INQUIRY_CANCEL) }
        runCatching {
            if (extended) hci.command(Hci.LE_SET_EXT_SCAN_ENABLE, byteArrayOf(0, 0, 0, 0, 0, 0))
            else hci.command(Hci.LE_SET_SCAN_ENABLE, byteArrayOf(0, 0))
        }
    }

    /** Every event as it arrives (code, parameters), before decoding: for sniffowireless's own event parser. */
    var onEvent: ((Int, ByteArray) -> Unit)? = null

    /** Waits up to [timeoutMs] for the next event and decodes it; empty when it wasn't about a device. */
    fun poll(timeoutMs: Int): List<Heard> = hci.nextEvent(timeoutMs)?.let { e -> onEvent?.invoke(e.code, e.params); decode(e) }.orEmpty()

    internal fun decode(e: Event): List<Heard> = try {
        val p = e.params
        when (e.code) {
            Hci.EVT_LE_META -> when (p[0].u8) {
                0x02 -> legacyReports(p)
                0x0D -> extendedReports(p)
                else -> emptyList()
            }
            Hci.EVT_EXTENDED_INQUIRY_RESULT ->
                listOf(InquiryResponse(p.address(1), rssi(p[14]), p.u24(9), AdData.parse(p.copyOfRange(15, p.size))))
            Hci.EVT_INQUIRY_RESULT_RSSI ->
                (0 until p[0].u8).map { i -> val o = 1 + 14 * i; InquiryResponse(p.address(o), rssi(p[o + 13]), p.u24(o + 8), null) }
            Hci.EVT_INQUIRY_RESULT ->
                (0 until p[0].u8).map { i -> val o = 1 + 14 * i; InquiryResponse(p.address(o), null, p.u24(o + 9), null) }
            Hci.EVT_INQUIRY_COMPLETE -> listOf(InquiryComplete)
            else -> emptyList()
        }
    } catch (x: IndexOutOfBoundsException) {
        log("Malformed $e")
        emptyList()
    }

    private fun legacyReports(p: ByteArray): List<Advert> {
        val out = ArrayList<Advert>()
        var o = 2
        repeat(p[1].u8) {
            val type = p[o].u8
            val len = p[o + 8].u8
            out += Advert(p.address(o + 2), p[o + 1].u8, rssi(p[o + 9 + len]), null,
                connectable = type == 0 || type == 1, scanResponse = type == 4, legacy = true,
                primaryPhy = 1, secondaryPhy = 0, data = p.copyOfRange(o + 9, o + 9 + len))
            o += 10 + len
        }
        return out
    }

    private fun extendedReports(p: ByteArray): List<Advert> {
        val out = ArrayList<Advert>()
        var o = 2
        // Each report: type(2) address type(1) address(6) primary PHY, secondary PHY, SID, TX power, RSSI (1 each),
        // periodic interval(2) direct address type(1) direct address(6) data length(1) data.
        repeat(p[1].u8) {
            val b = o
            val type = p.u16(b)
            val address = p.address(b + 3)
            val len = p[b + 23].u8
            val data = p.copyOfRange(b + 24, b + 24 + len)
            o += 24 + len
            // Long advertising data arrives in several reports: status 1 means more follows.
            val key = "$address/${p[b + 11].u8}"
            val whole = (fragments.remove(key) ?: ByteArray(0)) + data
            if ((type shr 5) and 3 == 1) { fragments[key] = whole; return@repeat }
            out += Advert(address, p[b + 2].u8, rssi(p[b + 13]), p[b + 12].toInt().takeIf { it != 127 },
                connectable = type and 1 != 0, scanResponse = type and 8 != 0, legacy = type and 0x10 != 0,
                primaryPhy = p[b + 9].u8, secondaryPhy = p[b + 10].u8, data = whole)
        }
        return out
    }

    private fun rssi(b: Byte) = b.toInt().takeIf { it != 127 }

    private fun ByteArray.bit(n: Int) = n / 8 < size && (this[n / 8].u8 shr (n % 8)) and 1 == 1
}
