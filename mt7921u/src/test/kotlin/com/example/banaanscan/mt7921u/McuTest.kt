package com.example.banaanscan.mt7921u

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Expected bytes are what the Linux driver sent and received, taken from a USB trace of the real adapter. */
class McuTest {
    @Test fun `patch semaphore request matches Linux`() {
        val linux = hex("4400000044000041000001800000000000000000000000000000000000000000000000002400008010a00306" +
            "0000000000000000000000000000000000000000000000000100000000000000")
        assertArrayEquals(linux, Mcu.command(Mcu.PATCH_SEM_CONTROL, 6, Mcu.u32s(1)))
    }

    @Test fun `patch start request matches Linux`() {
        val linux = hex("4c0000004c000041000001800000000000000000000000000000000000000000000000002c00008005a00307" +
            "00000000000000000000000000000000000000000000000000009000806701000000008000000000")
        assertArrayEquals(linux, Mcu.command(Mcu.PATCH_START_REQ, 7, Mcu.u32s(0x900000, 0x16780, 0x80000000)))
    }

    @Test fun `firmware start request matches Linux`() {
        val linux = hex("4800000048000041000001800000000000000000000000000000000000000000000000002800008002a0030f" +
            "000000000000000000000000000000000000000000000000010000000050910000000000")
        assertArrayEquals(linux, Mcu.command(Mcu.FW_START_REQ, 15, Mcu.u32s(1, 0x915000)))
    }

    @Test fun `CE commands are sent as set requests`() {
        val frame = Mcu.command(Mcu.GET_NIC_CAPAB, 1, ce = true)
        assertEquals(0x8a, frame[4 + 36].toInt() and 0xff)
        assertEquals(1, frame[4 + 38].toInt())
        assertEquals(0, frame.size % 4)
    }

    @Test fun `scatter chunks get only the USB header and padding`() {
        val frame = Mcu.scatter(ByteArray(4096) { 7 })
        assertEquals(4 + 4096 + 4, frame.size)
        assertEquals(4096, ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN).getInt(0))
        assertEquals(4 + 5 + 3 + 4, Mcu.scatter(ByteArray(5)).size)
    }

    @Test fun `semaphore reply from the chip parses`() {
        val linux = hex("2400003800000000000000000000000000000000000000000c0007000406000002000000")
        val e = Mcu.parseEvent(linux, linux.size)!!
        assertEquals(0x04, e.eid)
        assertEquals(6, e.seq)
        assertEquals(Mcu.PATCH_NOT_DL_SEM_SUCCESS, e.status)
    }

    @Test fun `non-event buffers are ignored`() {
        assertNull(Mcu.parseEvent(ByteArray(40), 40))
        assertNull(Mcu.parseEvent(ByteArray(10), 10))
        val wrongLength = hex("ff000038000000000000000000000000000000000000000000000000040600000200000000")
        assertNull(Mcu.parseEvent(wrongLength, wrongLength.size))
    }
}

class FirmwareTest {
    private fun patchFile(): ByteArray {
        val data = ByteArray(32) { it.toByte() }
        val b = ByteBuffer.allocate(96 + 64 + data.size).order(ByteOrder.BIG_ENDIAN)
        b.put("20260224110909a\u0000".toByteArray()).put("ALPS".toByteArray()).putInt(0x8a108a10.toInt())
        b.position(44).putInt(1)
        b.position(96).putInt(2).putInt(160).putInt(data.size).putInt(0x900000).putInt(data.size).putInt(0)
        b.position(160).put(data)
        return b.array()
    }

    @Test fun `patch header and sections parse`() {
        val p = PatchFile(patchFile())
        assertEquals("20260224110909a", p.buildDate)
        assertEquals(0x8a108a10L, p.hwSwVersion)
        assertEquals(1, p.regions.size)
        assertEquals(0x900000L, p.regions[0].address)
        assertEquals(32, p.regions[0].data.size)
        assertEquals(0x80000000L, p.regions[0].mode)
    }

    @Test fun `RAM firmware regions and start address parse`() {
        val a = ByteArray(16) { 1 }
        val skip = ByteArray(8) { 2 }
        val c = ByteArray(24) { 3 }
        val b = ByteBuffer.allocate(48 + 3 * 40 + 36).order(ByteOrder.LITTLE_ENDIAN)
        b.put(a).put(skip).put(c)
        fun region(addr: Int, len: Int, feature: Int) {
            val at = b.position()
            b.position(at + 16).putInt(addr).putInt(len).put(feature.toByte())
            b.position(at + 40)
        }
        region(0x915000, 16, 0x20)  // override start address
        region(0, 8, 0x40)          // nothing to download
        region(0xe0002800.toInt(), 24, 0x01)
        b.put(0x61).put(0).put(3).put(1).put(0).put(0).put(0)
        b.put("____010000".toByteArray()).put("20260224110949\u0000".toByteArray())
        val ram = RamFile(b.array())
        assertEquals("____010000", ram.version)
        assertEquals("20260224110949", ram.buildDate)
        assertEquals(listOf(0x915000L, 0xe0002800L), ram.regions.map { it.address })
        assertArrayEquals(c, ram.regions[1].data)
        assertEquals(0x915000L, ram.startAddress)
        assertEquals(0x80000000L, ram.regions[0].mode)
        assertEquals(0x80000009L, ram.regions[1].mode)
    }

    @Test fun `broken files are rejected`() {
        val bad = patchFile().also { it[44 + 3] = 99 }
        assertTrue(runCatching { PatchFile(bad) }.isFailure)
        assertTrue(runCatching { RamFile(ByteArray(10)) }.isFailure)
    }
}
