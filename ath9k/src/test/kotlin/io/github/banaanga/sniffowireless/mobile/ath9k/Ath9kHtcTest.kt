package io.github.banaanga.sniffowireless.mobile.ath9k

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A USB link that answers WMI commands like the firmware and hands out prepared receive transfers. */
private class FakeUsb(val rxTransfers: ArrayDeque<ByteArray> = ArrayDeque()) : UsbIo {
    val regIn = ArrayDeque<ByteArray>()
    val sent = ArrayList<ByteArray>()

    override fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? = null
    override fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int) = data.size

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        sent += data
        if (endpoint == Htc.EP_REG_OUT && data.size >= 12 && data[0].toInt() != 0) {
            // A WMI command: answer with the same id and sequence number, and 4 bytes per register read.
            val payload = data.copyOfRange(8, data.size)
            val cmd = be16(payload, 0)
            val answer = ByteBuffer.allocate(4 + if (cmd == Htc.WMI_REG_READ) payload.size - 4 else 4).order(ByteOrder.BIG_ENDIAN)
            answer.putShort(cmd.toShort()).putShort(be16(payload, 2).toShort())
            if (cmd == Htc.WMI_REG_READ) for (i in 4 until payload.size step 4) answer.putInt(be32(payload, i) + 1) else answer.putInt(0x01020101)
            regIn += htcFrame(data[0].toInt(), answer.array())
        }
        return data.size
    }

    override fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val next = (if (endpoint == Htc.EP_REG_IN) regIn else rxTransfers).removeFirstOrNull() ?: return 0
        next.copyInto(buffer)
        return next.size
    }
}

private fun htcFrame(ep: Int, payload: ByteArray) =
    ByteBuffer.allocate(8 + payload.size).order(ByteOrder.BIG_ENDIAN).put(ep.toByte()).put(0).putShort(payload.size.toShort()).putInt(0).put(payload).array()

/** One received frame as the firmware sends it: HTC header, ath_htc_rx_status, frame + FCS. */
private fun rxPacket(frame: ByteArray, rssi: Int = 40, rate: Int = 0x0b, status: Int = 0, flags: Int = 0, moreAggr: Boolean = false): ByteArray {
    val st = ByteBuffer.allocate(40).order(ByteOrder.BIG_ENDIAN)
    st.putLong(123456789L).putShort((frame.size + 4).toShort()).put(status.toByte()).put(0).put(rssi.toByte())
    st.position(20)
    st.put(rate.toByte())
    st.position(24)
    st.put(if (moreAggr) 1 else 0)
    st.position(26)
    st.put(flags.toByte())
    return htcFrame(2, st.array() + frame + byteArrayOf(1, 2, 3, 4))
}

/** The USB receive stream: [len LE][0x4e00 LE][packet][pad to 4]. */
private fun stream(vararg packets: ByteArray): ByteArray {
    val out = java.io.ByteArrayOutputStream()
    for (p in packets) {
        out.write(p.size and 0xFF); out.write(p.size ushr 8); out.write(0x00); out.write(0x4e)
        out.write(p)
        repeat((4 - p.size % 4) % 4) { out.write(0) }
    }
    return out.toByteArray()
}

class Ath9kHtcTest {
    private val beacon = ByteArray(60) { if (it == 0) 0x80.toByte() else it.toByte() }

    @Test fun `frames come out of the stream without their FCS, with signal and rate`() {
        val probe = ByteArray(33) { 0x40 }
        val usb = FakeUsb(ArrayDeque(listOf(stream(rxPacket(beacon), rxPacket(probe, rssi = 20, rate = 0x85, flags = 0x0c)))))
        val dev = Ath9kHtc(usb)
        val a = dev.receive(100)!!
        assertArrayEquals(beacon, a.data)
        assertEquals(-55, a.rssi)
        assertEquals(6.0, a.legacyMbps!!, 0.0)
        assertNull(a.mcs)
        assertEquals(123456789L, a.tsf)
        val b = dev.receive(100)!!
        assertEquals(33, b.data.size)
        assertEquals(5, b.mcs)
        assertTrue(b.ht40)
        assertTrue(b.shortGi)
        assertNull(dev.receive(10))
    }

    @Test fun `A-MPDU subframes before the last have no signal`() {
        val usb = FakeUsb(ArrayDeque(listOf(stream(rxPacket(beacon, moreAggr = true), rxPacket(beacon, rssi = 50)))))
        val dev = Ath9kHtc(usb)
        assertNull(dev.receive(100)!!.rssi)
        assertEquals(-45, dev.receive(100)!!.rssi)
    }

    @Test fun `a packet split over two transfers is joined`() {
        val s = stream(rxPacket(beacon), rxPacket(beacon.copyOf(200)))
        val usb = FakeUsb(ArrayDeque(listOf(s.copyOfRange(0, 150), s.copyOfRange(150, s.size))))
        val dev = Ath9kHtc(usb)
        assertEquals(60, dev.receive(100)!!.data.size)
        assertEquals(200, dev.receive(100)!!.data.size)
    }

    @Test fun `CRC errors are flagged and PHY errors dropped`() {
        val usb = FakeUsb(ArrayDeque(listOf(stream(rxPacket(beacon, status = 0x02), rxPacket(beacon, status = 0x01)))))
        val f = Ath9kHtc(usb).receive(100)!!
        assertTrue(f.fcsError)
    }

    @Test fun `a transfer with a bad tag is dropped`() {
        val bad = stream(rxPacket(beacon)).also { it[3] = 0x4f }
        val usb = FakeUsb(ArrayDeque(listOf(bad, stream(rxPacket(beacon)))))
        val f = Ath9kHtc(usb).receive(100)!!
        assertFalse(f.fcsError)
    }

    @Test fun `register writes are batched and flushed before a read`() {
        val usb = FakeUsb()
        val htc = Htc(usb)
        usb.regIn += htcFrame(0, ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN).putShort(3).putShort(0x0100).put(0).put(1).putShort(0x40).put(0).put(0).array())
        assertEquals(1, htc.connectService(Htc.WMI_CONTROL_SVC))
        val regs = WmiRegs(htc)
        repeat(70) { regs.write(0x1000 + 4 * it, it) }
        assertEquals(1, usb.sent.count { it[0].toInt() == 1 })  // the first 62 went out as one command
        assertEquals(0x9875, regs.read(0x9874))
        val cmds = usb.sent.filter { it[0].toInt() == 1 }.map { be16(it, 8) }
        assertEquals(listOf(Htc.WMI_REG_WRITE, Htc.WMI_REG_WRITE, Htc.WMI_REG_READ), cmds)
        assertEquals(8 + 4 + 8 * 8, usb.sent.last { be16(it, 8) == Htc.WMI_REG_WRITE }.size)
    }
}
