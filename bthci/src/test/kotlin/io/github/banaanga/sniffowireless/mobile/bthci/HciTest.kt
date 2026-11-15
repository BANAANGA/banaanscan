package io.github.banaanga.sniffowireless.mobile.bthci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

internal fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Answers commands from a script and hands out queued event bytes in USB-sized pieces. */
internal class FakeIo(private val chunk: Int = 16, val answer: (ByteArray) -> List<String>) : HciIo {
    val sent = ArrayList<ByteArray>()
    private var queue = ByteArray(0)

    fun queue(event: String) { queue += hex(event) }

    override fun sendCommand(packet: ByteArray, timeoutMs: Int) {
        sent += packet
        answer(packet).forEach(::queue)
    }

    override fun readEvents(buffer: ByteArray, timeoutMs: Int): Int {
        val n = minOf(chunk, queue.size)
        queue.copyInto(buffer, 0, 0, n)
        queue = queue.copyOfRange(n, queue.size)
        return n
    }
}

class HciTest {
    @Test fun `events are rebuilt from split and joined transfers`() {
        val r = EventReassembler()
        val bytes = hex("0e 04 01 03 0c 00" + "3e 13 02 01 00 00 665544332211 07 02010603094142 c4")
        r.feed(bytes.copyOf(4), 4)
        assertNull(r.poll())
        r.feed(bytes.copyOfRange(4, bytes.size), bytes.size - 4)
        assertEquals(0x0e, r.poll()!!.code)
        val adv = r.poll()!!
        assertEquals(0x3e, adv.code)
        assertEquals(0x13, adv.params.size)
        assertNull(r.poll())
    }

    @Test fun `command waits for its own answer and keeps other events`() {
        val io = FakeIo { pkt ->
            if (pkt.u16(0) == Hci.RESET) listOf("3e 03 99 00 00", "0e 04 01 03 0c 00") else listOf("0f 04 0c 01 01 04")
        }
        val hci = Hci(io)
        assertEquals(0, hci.commandOk(Hci.RESET).size)
        assertArrayEquals(hex("030c00"), io.sent[0])
        assertEquals(0x3e, hci.nextEvent(10)!!.code)
        assertArrayEquals(byteArrayOf(0x0c), hci.command(Hci.INQUIRY, ByteArray(5)))
    }

    @Test(expected = HciException::class)
    fun `non-zero status is an error`() {
        Hci(FakeIo { listOf("0e 04 01 03 0c 12") }).commandOk(Hci.RESET)
    }

    @Test fun `local version`() {
        val v = LocalVersion.parse(hex("0a 0b00 0a 5d00 6187"))
        assertEquals(LocalVersion(10, 0x000b, 10, 0x005d, 0x8761), v)
        assertEquals("RTL8761BU", Realtek.chipNeedingFirmware(v)!!.name)
        assertNull(Realtek.chipNeedingFirmware(v.copy(lmpSubversion = 0xd922)))
        assertFalse(Realtek.isUnknownRom(v.copy(lmpSubversion = 0xd922)))
        assertTrue(Realtek.isUnknownRom(v.copy(hciRevision = 0x000e)))
    }

    @Test fun `address is written most significant byte first`() {
        assertEquals("11:22:33:44:55:66", hex("665544332211").address(0))
    }
}
