package io.github.banaanga.sniffowireless.mobile.bthci

/** What HCI Read Local Version Information says about the controller. */
data class LocalVersion(val hciVersion: Int, val hciRevision: Int, val lmpVersion: Int, val manufacturer: Int, val lmpSubversion: Int) {
    companion object {
        fun parse(r: ByteArray) = LocalVersion(r[0].u8, r.u16(1), r[3].u8, r.u16(4), r.u16(6))
    }
}

/**
 * A Realtek Bluetooth firmware file ("Realtech" epatch, as shipped in linux-firmware rtl_bt/). It holds one patch per
 * ROM version of the chip, plus a trailer with the project (chip family) number.
 *
 * Layout: "Realtech", u32 version, u16 count, then count × u16 chip id, count × u16 length, count × u32 offset.
 * The trailer ends the file: options read backwards from the 4-byte signature 51 04 fd 77, each as data, length,
 * opcode; opcode 0 holds the project number, 0xff ends the list.
 */
class RtlFirmware(val version: Long, val projectId: Int?, private val patches: Map<Int, ByteArray>) {
    val chipIds: Set<Int> get() = patches.keys

    /** The patch for a chip with this ROM version, with the firmware version written into its last four bytes. */
    fun patchFor(romVersion: Int): ByteArray? = patches[romVersion + 1]?.copyOf()?.also { p ->
        for (i in 0 until 4) p[p.size - 4 + i] = (version shr (8 * i)).toByte()
    }

    companion object {
        private val SIGNATURE = "Realtech".toByteArray()
        private val TRAILER = byteArrayOf(0x51, 0x04, 0xfd.toByte(), 0x77)

        fun parse(f: ByteArray): RtlFirmware {
            if (f.size < 14 + 4 || !f.copyOf(8).contentEquals(SIGNATURE)) throw HciException("Not a Realtek Bluetooth firmware file")
            if (!f.copyOfRange(f.size - 4, f.size).contentEquals(TRAILER)) throw HciException("The firmware file has no trailer")
            val version = f.u32(8).toLong() and 0xFFFFFFFFL
            val count = f.u16(12)
            val table = 14
            if (table + count * 8 > f.size) throw HciException("The firmware file is cut short")
            val patches = HashMap<Int, ByteArray>()
            for (i in 0 until count) {
                val chip = f.u16(table + 2 * i)
                val len = f.u16(table + 2 * count + 2 * i)
                val off = f.u32(table + 4 * count + 4 * i)
                if (off < 0 || len < 4 || off + len > f.size) throw HciException("Firmware patch $i lies outside the file")
                patches[chip] = f.copyOfRange(off, off + len)
            }
            var projectId: Int? = null
            var at = f.size - 5
            while (at >= 2) {
                val op = f[at].u8
                val len = f[at - 1].u8
                if (op == 0xff) break
                if (op == 0 && len >= 1) projectId = f[at - 1 - len].u8
                at -= 2 + len
            }
            return RtlFirmware(version, projectId, patches)
        }
    }
}

/** Loads firmware into Realtek USB Bluetooth chips, which start from a ROM that isn't fit for use on its own. */
object Realtek {
    const val MANUFACTURER = 0x005D
    private const val READ_ROM_VERSION = 0xFC6D
    private const val DOWNLOAD = 0xFC20
    private const val FRAGMENT = 252

    /** A chip we know the firmware for. [project] guards against a mismatched file. */
    class Chip(val name: String, val lmpSubversion: Int, val hciRevision: Int, val project: Int, val firmware: String, val config: String?)

    val CHIPS = listOf(
        Chip("RTL8761BU", 0x8761, 0x000b, 14, "rtl8761bu_fw.bin", "rtl8761bu_config.bin"),
    )

    /** The chip still runs its ROM and needs firmware; null when it isn't one we know, or is already patched. */
    fun chipNeedingFirmware(v: LocalVersion): Chip? =
        if (v.manufacturer != MANUFACTURER) null
        else CHIPS.firstOrNull { it.lmpSubversion == v.lmpSubversion && it.hciRevision == v.hciRevision }

    /** True when this is a Realtek ROM we don't have firmware for. */
    fun isUnknownRom(v: LocalVersion) =
        v.manufacturer == MANUFACTURER && chipNeedingFirmware(v) == null && (v.lmpSubversion shr 12) == 0x8

    /** The bytes to upload: the patch for this chip's ROM, followed by the config file as is. */
    fun image(chip: Chip, firmware: RtlFirmware, config: ByteArray?, romVersion: Int): ByteArray {
        if (firmware.projectId != null && firmware.projectId != chip.project) {
            throw HciException("${chip.firmware} is for project ${firmware.projectId}, not ${chip.name}")
        }
        val patch = firmware.patchFor(romVersion)
            ?: throw HciException("${chip.firmware} has no patch for ROM version $romVersion (has ${firmware.chipIds.sorted()})")
        return patch + (config ?: ByteArray(0))
    }

    /** Fragment numbers count 0..0x7f, then wrap to 1..0x80; the last fragment has bit 7 set. */
    internal fun fragmentIndex(i: Int, last: Boolean): Int {
        val idx = if (i > 0x7f) (i and 0x7f) + 1 else i
        return if (last) idx or 0x80 else idx
    }

    fun readRomVersion(hci: Hci): Int = hci.commandOk(READ_ROM_VERSION)[0].u8

    fun upload(hci: Hci, image: ByteArray, progress: (Int) -> Unit = {}) {
        val count = (image.size + FRAGMENT - 1) / FRAGMENT
        for (i in 0 until count) {
            val part = image.copyOfRange(i * FRAGMENT, minOf(image.size, (i + 1) * FRAGMENT))
            val index = fragmentIndex(i, i == count - 1)
            hci.commandOk(DOWNLOAD, byteArrayOf(index.toByte()) + part, timeoutMs = 3000)
            progress(100 * (i + 1) / count)
        }
    }
}
