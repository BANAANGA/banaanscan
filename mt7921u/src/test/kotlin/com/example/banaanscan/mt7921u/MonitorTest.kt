package com.example.banaanscan.mt7921u

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

/** Answers every command with an empty event carrying the same sequence number, and records what was sent. */
private class FakeUsb : UsbIo {
    val sent = ArrayList<ByteArray>()
    private val replies = ArrayDeque<ByteArray>()

    override fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int) = ByteArray(length)
    override fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int) = data.size

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        sent += data
        val seq = data[4 + 39]
        replies += ByteArray(40).also {
            ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(0, 40 or (7 shl 27))
            it[28] = 1
            it[29] = seq
        }
        return data.size
    }

    override fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val r = replies.removeFirstOrNull() ?: return 0
        r.copyInto(buffer)
        return r.size
    }
}

/** Expected frames are what the Linux driver sent to enter monitor mode, from a USB trace of the real adapter. */
class MonitorTest {
    private val linux = mapOf(
        "efuse" to "44000000440000410000018000000000000000000000000000000000000000000000000024000080eda001030021000100000000000000000000000000000000000000000001000000000000",
        "macInit" to "44000000440000410000018000000000000000000000000000000000000000000000000024000080eda001050046000100000000000000000000000000000000000000000100000000000000",
        "dev" to "4000000040000041000001800000000000000000000000000000000000000000000000002000010000a0000900000007000000000000000000000c00010000000000000000000000",
        "bss" to "5400000054000041000001800000000000000000000000000000000000000000000000003400020000a0000a000000070000000000000000000020000100000002000100010000000000000000000000000000000000000000000000",
        "snifferOn" to "3c0000003c000041000001800000000000000000000000000000000000000000000000001c00240000a0000b000000070000000000000000000008000100000000000000",
        "rxFilter" to "840000008400004100000180000000000000000000000000000000000000000000000000640000800aa0010d000000000000000000000000000000000000000000000000000000000100000060000080000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
        "beaconFilterOff" to "840000008400004100000180000000000000000000000000000000000000000000000000640000800aa00102000000000000000000000000000000000000000000000000000000000200000000000000000800000200000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000000",
    ).mapValues { hex(it.value) }

    /** Linux's sequence numbers differ from ours; everything else must match. */
    private fun withoutSeq(frame: ByteArray, uni: Boolean) = frame.copyOf().also { it[4 + 39] = 0 }

    @Test fun `monitor start sends the same commands as Linux`() {
        val usb = FakeUsb()
        Monitor(Mt7921u(usb)).start(Channel(Channel.Band.GHZ_2_4, 6))
        val order = listOf("efuse", "macInit", null, "dev", "bss", "snifferOn", null, "rxFilter", "beaconFilterOff")
        assertEquals(order.size, usb.sent.size)
        order.forEachIndexed { i, name ->
            if (name != null) assertArrayEquals(name, withoutSeq(linux.getValue(name), false), withoutSeq(usb.sent[i], false))
        }
        // The chip-config command carries its setting as text.
        assertTrue(String(usb.sent[2], 4 + 64 + 8, 13) == "KeepFullPwr 1")
    }

    @Test fun `tuning matches Linux on 2_4, 5 and 6 GHz`() {
        val cases = mapOf(
            Channel(Channel.Band.GHZ_2_4, 6) to "000000000100100000000100060006000100000000000000",
            Channel(Channel.Band.GHZ_5, 36) to "000000000100100000000200240024000100000000000000",
            Channel(Channel.Band.GHZ_6, 37) to "000000000100100000000300250025000100000000000000",
        )
        for ((channel, payload) in cases) {
            val usb = FakeUsb()
            Monitor(Mt7921u(usb)).tune(channel)
            assertArrayEquals(channel.toString(), hex(payload), usb.sent.single().copyOfRange(4 + 48, usb.sent.single().size))
        }
    }

    @Test fun `channels convert to and from frequencies`() {
        assertEquals(Channel(Channel.Band.GHZ_2_4, 6), Channel.fromFreq(2437))
        assertEquals(2484, Channel.fromFreq(2484).freqMhz)
        assertEquals(Channel(Channel.Band.GHZ_5, 36), Channel.fromFreq(5180))
        assertEquals(Channel(Channel.Band.GHZ_6, 37), Channel.fromFreq(6135))
        assertEquals(6135, RxDescriptor.channelToFreq(181 + 9))
        assertEquals(5180, RxDescriptor.channelToFreq(36))
        assertEquals(2437, RxDescriptor.channelToFreq(6))
    }

    private fun descriptor(groups: Int, channelCode: Int, rcpi: Int, frame: ByteArray, pad: Int = 0, type: Int = 2, fcsError: Boolean = false): ByteArray {
        var extra = 0
        if (groups and (1 shl 14) != 0) extra += 16
        if (groups and (1 shl 11) != 0) extra += 16
        if (groups and (1 shl 12) != 0) extra += 8
        val g3 = groups and (1 shl 13) != 0
        val g5 = groups and (1 shl 15) != 0
        val rxvAt = 24 + extra
        if (g3) extra += 8
        if (g3 && g5) extra += 24 + 48
        val size = 24 + extra + 2 * pad + frame.size
        val b = ByteBuffer.allocate(size + 4).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0, size or (type shl 27))
        b.putInt(4, groups or if (fcsError) 1 shl 27 else 0)
        b.putInt(8, pad shl 14)
        b.putInt(12, channelCode shl 8)
        if (g3 && !g5) b.putInt(rxvAt + 4, rcpi)
        if (g3 && g5) b.putInt(rxvAt + 8 + 24, rcpi)
        b.position(24 + extra + 2 * pad)
        b.put(frame)
        return b.array()
    }

    private val beacon = hex("80000000ffffffffffff0011223344550011223344550000")

    @Test fun `received frames are cut out of their descriptor`() {
        val raw = descriptor(groups = (1 shl 13) or (1 shl 15) or (1 shl 12), channelCode = 36, rcpi = 0x0000_8c86, frame = beacon, pad = 1)
        val f = RxDescriptor.parse(raw, raw.size)!!
        assertEquals(5180, f.freqMhz)
        assertEquals(-40, f.rssi) // the stronger antenna: RCPI 0x8c = 140 -> (140 - 220) / 2
        assertArrayEquals(beacon, f.data)
    }

    @Test fun `6 GHz frames and frames without signal readings parse`() {
        val raw = descriptor(groups = 1 shl 14, channelCode = 190, rcpi = 0, frame = beacon, fcsError = true)
        val f = RxDescriptor.parse(raw, raw.size)!!
        assertEquals(6135, f.freqMhz)
        assertNull(f.rssi)
        assertTrue(f.fcsError)
    }

    @Test fun `events and damaged buffers are not frames`() {
        val event = descriptor(groups = 0, channelCode = 6, rcpi = 0, frame = beacon, type = 7)
        assertNull(RxDescriptor.parse(event, event.size))
        val truncated = descriptor(groups = 1 shl 13, channelCode = 6, rcpi = 0, frame = beacon)
        assertNull(RxDescriptor.parse(truncated, 30))
        assertNull(RxDescriptor.parse(ByteArray(10), 10))
    }
}
