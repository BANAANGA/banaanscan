package com.example.banaanscan.zigbee

import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** Debug builds can run the whole Zigbee flow against these, on emulators and in unit tests. */
object ZigbeeDebug {
    fun simulatedLink(kind: String): SerialLink? = when (kind) {
        "znp" -> SimulatedZnpDongle()
        "ezsp" -> SimulatedEzspDongle()
        else -> null
    }
}

/** Delivers replies in small chunks, like a real serial port splits them up. */
abstract class SimulatedLink(private val chunkSize: Int = 7) : SerialLink {
    private val output = LinkedBlockingQueue<Byte>()

    protected fun send(data: ByteArray) = data.forEach(output::put)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int {
        val first = output.poll(timeoutMs.toLong(), TimeUnit.MILLISECONDS) ?: return 0
        buffer[0] = first
        var n = 1
        while (n < minOf(chunkSize, buffer.size)) buffer[n++] = output.poll() ?: break
        return n
    }

    override fun setPins(dtr: Boolean, rts: Boolean) = Unit

    override fun close() = Unit
}

/** A link that never answers, like a dongle with other firmware. */
class SilentLink : SimulatedLink() {
    override fun write(data: ByteArray) = Unit
}

internal fun hex(text: String): ByteArray =
    text.trim().split(" ").map { it.toInt(16).toByte() }.toByteArray()

class SimulatedZnpDongle(
    private val configuredChannels: Long = ALL_CHANNELS_MASK,
    private val scanStatus: Int = 0,
) : SimulatedLink() {
    private val parser = ZnpCodec.Parser()

    /** (cmd0, cmd1) of every command received, so tests can prove nothing was written. */
    val received = ArrayList<Pair<Int, Int>>()

    override fun write(data: ByteArray) {
        for (frame in parser.feed(data)) {
            received += frame.cmd0 to frame.cmd1
            when (frame.cmd0 to frame.cmd1) {
                0x21 to 0x01 -> send(ZnpCodec.encode(0x61, 0x01, hex("D9 07")))
                0x21 to 0x02 -> send(ZnpCodec.encode(0x61, 0x02, hex("02 01 02 07 01 9B 95 34 01 00")))
                0x21 to 0x08 -> send(ZnpCodec.encode(0x61, 0x08, hex("00 04") + u32leBytes(configuredChannels)))
                0x25 to 0x26 -> {
                    send(ZnpCodec.encode(0x65, 0x26, byteArrayOf(scanStatus.toByte())))
                    if (scanStatus == 0) {
                        // Two routers of the same network, then a second network.
                        send(ZnpCodec.encode(0x45, 0xC5, byteArrayOf(2) + BEACON_HOME_COORDINATOR + BEACON_HOME_ROUTER))
                        send(ZnpCodec.encode(0x45, 0xC5, byteArrayOf(1) + BEACON_NEIGHBOUR))
                        send(ZnpCodec.encode(0x45, 0xC7, byteArrayOf(0)))
                    }
                }
            }
        }
    }

    companion object {
        // Src, PAN, channel, permit, router cap, device cap, protocol, profile, LQI, depth, update, ext PAN (LSB first)
        val BEACON_HOME_COORDINATOR = hex("00 00 34 12 0F 00 01 01 02 02 96 00 05 88 77 66 55 44 33 22 11")
        val BEACON_HOME_ROUTER = hex("CD AB 34 12 0F 01 01 01 02 02 DC 01 05 88 77 66 55 44 33 22 11")
        val BEACON_NEIGHBOUR = hex("00 00 CD AB 14 00 00 00 02 02 28 00 00 01 EF CD AB 90 78 56 34")
    }
}

class SimulatedEzspDongle(
    private val ezspVersion: Int = 13,
    private val statusBytes: Int = if (ezspVersion >= 14) 4 else 1,
    /** Ignore this many DATA frames to exercise retransmission. */
    private var dropFrames: Int = 0,
    private val scanStatus: Int = 0,
    private val hasCaptureMode: Boolean = true,
) : SimulatedLink() {
    private val parser = AshCodec.Parser()
    private var txFrame = 0
    private var rxFrame = 0

    /** EZSP frame IDs of every command received, so tests can prove nothing was written. */
    val received = ArrayList<Int>()
    var retransmissions = 0
        private set
    var resets = 0
        private set

    override fun write(data: ByteArray) {
        // The cancel bytes are only sent with a reset.
        if (data.isNotEmpty() && data[0] == 0x1A.toByte()) {
            txFrame = 0
            rxFrame = 0
            resets++
            send(AshCodec.encodeRStack(0x0B))
            return
        }
        for (frame in parser.feed(data).frames) {
            if (frame !is AshCodec.Frame.Data) continue
            if (dropFrames > 0) {
                dropFrames--
                continue
            }
            if (frame.retransmit) retransmissions++
            rxFrame = (frame.frameNumber + 1) % 8
            send(AshCodec.encodeAck(rxFrame))
            handleCommand(frame.payload)
        }
    }

    private fun handleCommand(payload: ByteArray) {
        // A byte of 0x01 at this position is how the v8+ header identifies itself.
        val format = when {
            payload.size >= 5 && payload.u8(2) == 0x01 -> 8
            payload.size >= 5 && payload.u8(2) == 0xFF -> 5
            else -> 4
        }
        val command = EzspFrames.decode(format, payload) ?: return
        received += command.frameId
        fun reply(id: Int, parameters: ByteArray) {
            send(AshCodec.encodeData(txFrame, false, rxFrame, EzspFrames.encode(format, command.sequence, id, parameters)))
            txFrame = (txFrame + 1) % 8
        }
        when (command.frameId) {
            0x00 -> reply(0x00, byteArrayOf(ezspVersion.toByte(), 2, 0x30, 0x74))
            0x1A -> {
                reply(0x1A, ByteArray(statusBytes).also { it[0] = scanStatus.toByte() })
                if (scanStatus != 0) return
                if (command.parameters.u8(0) == 0) {
                    for (channel in 11..26) reply(0x48, byteArrayOf(channel.toByte(), (-90 + channel).toByte()))
                } else {
                    reply(0x1B, NETWORK_HOME)
                    reply(0x1B, NETWORK_NEIGHBOUR)
                }
                reply(0x1C, byteArrayOf(0, 0))
            }
            0x83, 0x84 -> if (hasCaptureMode) reply(command.frameId, ByteArray(statusBytes)) else reply(0x58, byteArrayOf(0x31))
            0x8A -> {
                reply(0x8A, ByteArray(statusBytes))
                // lqi, rssi, length, frame, then the two filler bytes real firmware appends.
                CAPTURES[command.parameters.u8(0)]?.forEach { frame ->
                    reply(0x8E, byteArrayOf(0xC8.toByte(), (-58).toByte(), (frame.size + 2).toByte()) + frame + byteArrayOf(0x7F, 0x00))
                }
            }
            else -> reply(0x58, byteArrayOf(0x31))
        }
    }

    companion object {
        // Channel, PAN, ext PAN (LSB first), allowing join, profile, update ID, LQI, RSSI
        val NETWORK_HOME = hex("0F 34 12 88 77 66 55 44 33 22 11 01 02 05 C8 C4")
        val NETWORK_NEIGHBOUR = hex("14 CD AB 01 EF CD AB 90 78 56 34 00 02 00 28 A6")

        val FRAME_ZIGBEE_DATA = hex("41 88 2A 34 12 FF FF 00 00 08 02 FD FF 00 00 1E 7B")
        val FRAME_ZIGBEE_BEACON = hex("00 80 2B 34 12 00 00 FF CF 00 00 00 22 84 11 22 33 44 55 66 77 88")
        val FRAME_ENCRYPTED = hex("69 D8 11 CD AB 01 00 08 07 06 05 04 03 02 01 05 00 00 00 00 AA BB CC DD")
        val FRAME_ACK = hex("02 00 2A")

        /** What the simulated stick "hears" on each channel. */
        val CAPTURES = mapOf(
            15 to listOf(FRAME_ZIGBEE_BEACON, FRAME_ZIGBEE_DATA, FRAME_ACK),
            20 to listOf(FRAME_ENCRYPTED, FRAME_ENCRYPTED, hex("FF")),
        )
    }
}
