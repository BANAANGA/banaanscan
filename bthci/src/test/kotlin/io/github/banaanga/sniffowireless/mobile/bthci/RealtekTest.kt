package io.github.banaanga.sniffowireless.mobile.bthci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class RealtekTest {
    /** Two patches (chip ids 1 and 2), the trailer with project 14, laid out like rtl8761bu_fw.bin. */
    private fun firmware(): ByteArray {
        val head = hex("5265616c74656368 22d9c6df 0200 0100 0200 0600 0800 2200 0000 2800 0000")
        val pad = ByteArray(0x22 - head.size)
        val p1 = hex("a1a2 ffffffff")
        val p2 = hex("b1b2b3b4 eeeeeeee")
        return head + pad + p1 + p2 + hex("ff 0e 01 00 5104fd77")
    }

    @Test fun `parses patches and project`() {
        val f = RtlFirmware.parse(firmware())
        assertEquals(0xdfc6d922L, f.version)
        assertEquals(14, f.projectId)
        assertEquals(setOf(1, 2), f.chipIds)
        // ROM version 1 takes chip id 2; the last four bytes become the firmware version.
        assertArrayEquals(hex("b1b2b3b4 22d9c6df"), f.patchFor(1))
        assertArrayEquals(hex("a1a2 22d9c6df"), f.patchFor(0))
    }

    @Test fun `image is the patch followed by the config`() {
        val chip = Realtek.CHIPS.single()
        val img = Realtek.image(chip, RtlFirmware.parse(firmware()), hex("55ab23870000"), 1)
        assertArrayEquals(hex("b1b2b3b4 22d9c6df 55ab23870000"), img)
    }

    @Test(expected = HciException::class)
    fun `refuses firmware for another chip`() {
        val other = firmware().also { it[it.size - 7] = 0x33 }
        Realtek.image(Realtek.CHIPS.single(), RtlFirmware.parse(other), null, 1)
    }

    @Test fun `fragment numbering`() {
        assertEquals(0, Realtek.fragmentIndex(0, false))
        assertEquals(0x7f, Realtek.fragmentIndex(0x7f, false))
        assertEquals(1, Realtek.fragmentIndex(0x80, false))
        assertEquals(0x80 or 0x77, Realtek.fragmentIndex(0x77, true))
    }

    @Test fun `upload sends 252-byte fragments`() {
        val io = FakeIo { pkt -> listOf("0e 05 01 20fc 00 %02x".format(pkt[3].toInt() and 0xff)) }
        Realtek.upload(Hci(io), ByteArray(600) { it.toByte() })
        assertEquals(3, io.sent.size)
        assertArrayEquals(hex("20fc fd 00"), io.sent[0].copyOf(4))
        assertEquals(3 + 1 + 252, io.sent[1].size)
        assertArrayEquals(hex("20fc 61 82"), io.sent[2].copyOf(4))
        assertEquals(600 - 504, io.sent[2].size - 4)
    }
}
