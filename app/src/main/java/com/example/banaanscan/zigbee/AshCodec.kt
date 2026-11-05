package com.example.banaanscan.zigbee

/**
 * ASH, the reliable serial framing under Silicon Labs' EZSP (documented in their UG101):
 * payloads are XORed with a fixed pseudo-random sequence, CRC-protected, byte-stuffed and
 * terminated by a flag byte.
 */
object AshCodec {
    private const val FLAG = 0x7E
    private const val ESCAPE = 0x7D
    private const val XON = 0x11
    private const val XOFF = 0x13
    private const val SUBSTITUTE = 0x18
    private const val CANCEL = 0x1A
    private const val MAX_BUFFER = 1024
    private val RESERVED = setOf(FLAG, ESCAPE, XON, XOFF, SUBSTITUTE, CANCEL)

    private val PSEUDO_RANDOM: ByteArray = ByteArray(256).also { sequence ->
        var rand = 0x42
        for (i in sequence.indices) {
            sequence[i] = rand.toByte()
            rand = if (rand and 1 == 0) rand shr 1 else (rand shr 1) xor 0xB8
        }
    }

    sealed class Frame {
        data class Data(val frameNumber: Int, val retransmit: Boolean, val ackNumber: Int, val payload: ByteArray) : Frame()
        data class Ack(val ackNumber: Int) : Frame()
        data class Nak(val ackNumber: Int) : Frame()
        data class RStack(val version: Int, val resetCode: Int) : Frame()
        data class Error(val version: Int, val code: Int) : Frame()
    }

    /** CRC-16/CCITT-FALSE, sent most significant byte first. */
    fun crc16(data: ByteArray, length: Int = data.size): Int {
        var crc = 0xFFFF
        for (i in 0 until length) {
            crc = crc xor (data.u8(i) shl 8)
            repeat(8) {
                crc = if (crc and 0x8000 != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc
    }

    private fun randomize(data: ByteArray): ByteArray =
        ByteArray(data.size) { (data[it].toInt() xor PSEUDO_RANDOM[it].toInt()).toByte() }

    private fun wire(frame: ByteArray): ByteArray {
        val crc = crc16(frame)
        val out = ArrayList<Byte>(frame.size + 6)
        for (byte in frame + byteArrayOf((crc shr 8).toByte(), crc.toByte())) {
            val value = byte.toInt() and 0xFF
            if (value in RESERVED) {
                out += ESCAPE.toByte()
                out += (value xor 0x20).toByte()
            } else {
                out += byte
            }
        }
        out += FLAG.toByte()
        return out.toByteArray()
    }

    /** The cancel bytes abort anything half-received; the dongle reboots and answers with RSTACK. */
    fun encodeReset(): ByteArray = ByteArray(32) { CANCEL.toByte() } + wire(byteArrayOf(0xC0.toByte()))

    fun encodeAck(ackNumber: Int): ByteArray = wire(byteArrayOf((0x80 or ackNumber).toByte()))

    fun encodeNak(ackNumber: Int): ByteArray = wire(byteArrayOf((0xA0 or ackNumber).toByte()))

    fun encodeRStack(resetCode: Int): ByteArray = wire(byteArrayOf(0xC1.toByte(), 0x02, resetCode.toByte()))

    fun encodeData(frameNumber: Int, retransmit: Boolean, ackNumber: Int, payload: ByteArray): ByteArray {
        val control = (frameNumber shl 4) or (if (retransmit) 0x08 else 0) or ackNumber
        return wire(byteArrayOf(control.toByte()) + randomize(payload))
    }

    /** Returns the frame in unstuffed [data], or null if it is corrupt or not one a host expects. */
    fun decode(data: ByteArray): Frame? {
        if (data.size < 3) return null
        val crc = (data.u8(data.size - 2) shl 8) or data.u8(data.size - 1)
        if (crc16(data, data.size - 2) != crc) return null
        val control = data.u8(0)
        val body = data.copyOfRange(1, data.size - 2)
        return when {
            control and 0x80 == 0 -> Frame.Data((control shr 4) and 7, control and 0x08 != 0, control and 7, randomize(body))
            control and 0xE0 == 0x80 -> Frame.Ack(control and 7)
            control and 0xE0 == 0xA0 -> Frame.Nak(control and 7)
            control == 0xC1 && body.size == 2 -> Frame.RStack(body.u8(0), body.u8(1))
            control == 0xC2 && body.size == 2 -> Frame.Error(body.u8(0), body.u8(1))
            else -> null
        }
    }

    /** Result of [Parser.feed]: good frames, plus how many data frames arrived damaged. */
    class Parsed(val frames: List<Frame>, val corruptData: Int)

    class Parser {
        private val current = ArrayList<Byte>()
        private var discarding = false

        fun feed(data: ByteArray, length: Int = data.size): Parsed {
            val frames = ArrayList<Frame>()
            var corrupt = 0
            for (i in 0 until length) {
                when (val value = data.u8(i)) {
                    XON, XOFF -> Unit // Flow control, not frame content.
                    CANCEL -> current.clear()
                    SUBSTITUTE -> {
                        current.clear()
                        discarding = true
                    }
                    FLAG -> {
                        if (!discarding && current.isNotEmpty()) {
                            val raw = unstuff(current.toByteArray())
                            val frame = raw?.let(::decode)
                            when {
                                frame != null -> frames += frame
                                raw != null && raw.isNotEmpty() && raw.u8(0) and 0x80 == 0 -> corrupt++
                            }
                        }
                        current.clear()
                        discarding = false
                    }
                    else -> if (!discarding && current.size < MAX_BUFFER) current += value.toByte()
                }
            }
            return Parsed(frames, corrupt)
        }

        private fun unstuff(data: ByteArray): ByteArray? {
            val out = ArrayList<Byte>(data.size)
            var escaped = false
            for (byte in data) {
                val value = byte.toInt() and 0xFF
                when {
                    escaped -> {
                        val original = value xor 0x20
                        if (original !in RESERVED) return null
                        out += original.toByte()
                        escaped = false
                    }
                    value == ESCAPE -> escaped = true
                    else -> out += byte
                }
            }
            return out.toByteArray()
        }
    }
}
