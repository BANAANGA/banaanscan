package com.example.banaanscan.zigbee

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/** Sequencing, acknowledgement and retransmission for ASH. One DATA frame is in flight at a time. */
internal class AshSession(private val link: SerialLink) {
    private val parser = AshCodec.Parser()
    private val received = ArrayDeque<ByteArray>()
    private val buffer = ByteArray(256)
    private var txSequence = 0
    private var rxSequence = 0
    private var pendingAck: Int? = null
    private var pendingNak = false
    private var sawRStack = false

    /** Reboots the dongle's firmware (stored network data is untouched) and waits for it to answer. */
    suspend fun reset(timeoutMs: Long): Boolean {
        sawRStack = false
        link.write(AshCodec.encodeReset())
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            currentCoroutineContext().ensureActive()
            pump()
            if (sawRStack) {
                sawRStack = false
                txSequence = 0
                rxSequence = 0
                received.clear()
                return true
            }
        }
        return false
    }

    suspend fun send(payload: ByteArray) {
        val number = txSequence
        txSequence = (txSequence + 1) % 8
        val expectedAck = (number + 1) % 8
        repeat(MAX_ATTEMPTS) { attempt ->
            pendingAck = null
            pendingNak = false
            link.write(AshCodec.encodeData(number, attempt > 0, rxSequence, payload))
            val deadline = System.currentTimeMillis() + ACK_TIMEOUT_MS
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                pump()
                if (pendingAck == expectedAck) return
                if (pendingNak) break
            }
        }
        throw ZigbeeException("The dongle stopped acknowledging commands.")
    }

    /** Next EZSP frame from the dongle, or null if none arrives by [deadlineMs]. */
    suspend fun receive(deadlineMs: Long): ByteArray? {
        while (received.isEmpty() && System.currentTimeMillis() < deadlineMs) {
            currentCoroutineContext().ensureActive()
            pump()
        }
        return received.removeFirstOrNull()
    }

    private fun pump() {
        val n = link.read(buffer, READ_SLICE_MS)
        if (n <= 0) return
        val parsed = parser.feed(buffer, n)
        if (parsed.corruptData > 0) link.write(AshCodec.encodeNak(rxSequence))
        for (frame in parsed.frames) {
            when (frame) {
                is AshCodec.Frame.Data -> {
                    pendingAck = frame.ackNumber
                    when {
                        frame.frameNumber == rxSequence -> {
                            rxSequence = (rxSequence + 1) % 8
                            link.write(AshCodec.encodeAck(rxSequence))
                            received.addLast(frame.payload)
                        }
                        frame.retransmit -> link.write(AshCodec.encodeAck(rxSequence))
                        else -> link.write(AshCodec.encodeNak(rxSequence))
                    }
                }
                is AshCodec.Frame.Ack -> pendingAck = frame.ackNumber
                is AshCodec.Frame.Nak -> pendingNak = true
                is AshCodec.Frame.RStack -> sawRStack = true
                is AshCodec.Frame.Error ->
                    throw ZigbeeException("The dongle reported a firmware error (code 0x%02X).".format(frame.code))
            }
        }
    }

    private companion object {
        const val MAX_ATTEMPTS = 5
        const val ACK_TIMEOUT_MS = 1_600L
        const val READ_SLICE_MS = 50
    }
}

/** EZSP frames changed shape twice, so headers depend on the negotiated protocol version. */
object EzspFrames {
    class Parsed(val sequence: Int, val frameId: Int, val parameters: ByteArray)

    fun encode(version: Int, sequence: Int, frameId: Int, parameters: ByteArray = ByteArray(0)): ByteArray {
        val header = when {
            version >= 8 -> byteArrayOf(sequence.toByte(), 0x00, 0x01, frameId.toByte(), (frameId shr 8).toByte())
            version >= 5 -> byteArrayOf(sequence.toByte(), 0x00, 0xFF.toByte(), 0x00, frameId.toByte())
            else -> byteArrayOf(sequence.toByte(), 0x00, frameId.toByte())
        }
        return header + parameters
    }

    fun decode(version: Int, data: ByteArray): Parsed? = when {
        version >= 8 && data.size >= 5 -> Parsed(data.u8(0), data.u16le(3), data.copyOfRange(5, data.size))
        version in 5..7 && data.size >= 5 -> Parsed(data.u8(0), data.u8(4), data.copyOfRange(5, data.size))
        version < 5 && data.size >= 3 -> Parsed(data.u8(0), data.u8(2), data.copyOfRange(3, data.size))
        else -> null
    }

    /** Parameters of networkFoundHandler: EmberZigbeeNetwork, last hop LQI, last hop RSSI. */
    fun parseNetworkFound(p: ByteArray): ZigbeeNetwork? {
        if (p.size < 16) return null
        return ZigbeeNetwork(
            extendedPanId = p.eui64At(3),
            panId = p.u16le(1),
            channel = p.u8(0),
            permitJoining = p.u8(11) != 0,
            stackProfile = p.u8(12),
            updateId = p.u8(13),
            lqi = p.u8(14),
            rssi = p[15].toInt(),
        )
    }

    /** Parameters of mfglibRxHandler. The last two bytes of a packet aren't a real checksum: drop them. */
    fun parseCapturedFrame(channel: Int, p: ByteArray): CapturedFrame? {
        if (p.size < 3) return null
        val length = minOf(p.u8(2), p.size - 3)
        if (length < 2) return null
        return CapturedFrame(channel, p.u8(0), p[1].toInt(), p.copyOfRange(3, 3 + length - 2))
    }
}

/** EmberZNet coordinators such as the Sonoff ZBDongle-E and Home Assistant SkyConnect / ZBT-1. */
class EzspDriver private constructor(
    private val ash: AshSession,
    private val version: Int,
    private var sequence: Int,
    override val info: DongleInfo,
) : ZigbeeDriver, FrameCapture {
    private val callbacks = ArrayDeque<EzspFrames.Parsed>()

    /**
     * Uses the firmware's manufacturing test library in receive-only mode. Nothing is transmitted
     * or stored; a restart afterwards puts the stick back exactly as it was.
     */
    override suspend fun capture(
        channels: List<Int>,
        dwellMs: Long,
        onChannel: (Int) -> Unit,
        onFrame: (CapturedFrame) -> Unit,
    ) {
        val noCaptureMode = "This stick's firmware has no capture mode (mfglib), so it can't sniff until it's flashed with firmware that does."
        val started = try {
            command(ID_MFGLIB_START, byteArrayOf(1))
        } catch (e: ZigbeeException) {
            throw ZigbeeException(noCaptureMode)
        }
        if (started.any { it.toInt() != 0 }) throw ZigbeeException(noCaptureMode)

        fun deliver(frame: EzspFrames.Parsed, channel: Int) {
            if (frame.frameId == ID_MFGLIB_RX) EzspFrames.parseCapturedFrame(channel, frame.parameters)?.let(onFrame)
        }

        try {
            var current: Int? = null
            var next = 0
            while (true) {
                currentCoroutineContext().ensureActive()
                val channel = channels[next % channels.size]
                next++
                if (channel != current) {
                    val status = command(ID_MFGLIB_SET_CHANNEL, byteArrayOf(channel.toByte()))
                    if (status.any { it.toInt() != 0 }) throw ZigbeeException("The dongle couldn't switch to channel $channel.")
                    // Anything queued while switching was heard on the old channel.
                    while (true) {
                        val queued = callbacks.removeFirstOrNull() ?: break
                        current?.let { deliver(queued, it) }
                    }
                    current = channel
                    onChannel(channel)
                }
                val deadline = System.currentTimeMillis() + dwellMs
                while (true) {
                    val frame = callbacks.removeFirstOrNull() ?: nextFrame(deadline) ?: break
                    deliver(frame, channel)
                }
            }
        } finally {
            // Also runs when the user presses Stop, and may fail if the stick was just unplugged.
            withContext(NonCancellable) {
                runCatching {
                    command(ID_MFGLIB_END, ByteArray(0))
                    ash.reset(RESTART_TIMEOUT_MS)
                }
            }
        }
    }

    override suspend fun scan(listener: ScanListener) {
        listener.onProgress("Measuring interference on each channel…")
        runScan(SCAN_ENERGY, ENERGY_EXPONENT, listener)
        listener.onProgress("Scanning all channels for Zigbee networks…")
        runScan(SCAN_ACTIVE, ACTIVE_EXPONENT, listener)
    }

    private suspend fun runScan(type: Int, exponent: Int, listener: ScanListener) {
        val parameters = byteArrayOf(type.toByte()) + u32leBytes(ALL_CHANNELS_MASK) + exponent.toByte()
        val status = command(ID_START_SCAN, parameters)
        // Status is one byte up to EZSP 13 and four after that; zero means success in both.
        if (status.any { it.toInt() != 0 }) {
            throw ZigbeeException("The dongle refused to scan (status 0x${status.joinToString("") { "%02X".format(it) }}).")
        }
        val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
        while (true) {
            val frame = callbacks.removeFirstOrNull() ?: nextFrame(deadline)
                ?: throw ZigbeeException("The scan did not finish in time.")
            when (frame.frameId) {
                ID_NETWORK_FOUND -> EzspFrames.parseNetworkFound(frame.parameters)?.let(listener::onNetwork)
                ID_ENERGY_RESULT -> if (frame.parameters.size >= 2) {
                    listener.onEnergy(ChannelEnergy(frame.parameters.u8(0), frame.parameters[1].toInt()))
                }
                ID_SCAN_COMPLETE -> return
            }
        }
    }

    private suspend fun nextFrame(deadlineMs: Long): EzspFrames.Parsed? {
        val data = ash.receive(deadlineMs) ?: return null
        return EzspFrames.decode(version, data)
            ?: throw ZigbeeException("The dongle sent a malformed frame.")
    }

    private suspend fun command(frameId: Int, parameters: ByteArray): ByteArray {
        val seq = sequence
        sequence = (sequence + 1) and 0xFF
        ash.send(EzspFrames.encode(version, seq, frameId, parameters))
        val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
        while (true) {
            val frame = nextFrame(deadline) ?: throw ZigbeeException("The dongle did not answer a command.")
            when {
                frame.sequence == seq && frame.frameId == frameId -> return frame.parameters
                frame.sequence == seq && frame.frameId == ID_INVALID_COMMAND ->
                    throw ZigbeeException("The dongle rejected a command as invalid.")
                else -> callbacks.addLast(frame)
            }
        }
    }

    companion object {
        private const val ID_VERSION = 0x00
        private const val ID_START_SCAN = 0x1A
        private const val ID_NETWORK_FOUND = 0x1B
        private const val ID_SCAN_COMPLETE = 0x1C
        private const val ID_ENERGY_RESULT = 0x48
        private const val ID_INVALID_COMMAND = 0x58
        private const val ID_MFGLIB_START = 0x83
        private const val ID_MFGLIB_END = 0x84
        private const val ID_MFGLIB_SET_CHANNEL = 0x8A
        private const val ID_MFGLIB_RX = 0x8E
        private const val RESTART_TIMEOUT_MS = 2_000L
        private const val SCAN_ENERGY = 0
        private const val SCAN_ACTIVE = 1
        private const val ENERGY_EXPONENT = 3 // about 0.14 s per channel, 2.2 s in total
        private const val ACTIVE_EXPONENT = 4 // about 0.26 s per channel, 4.2 s in total
        private const val LEGACY_VERSION = 4
        private const val COMMAND_TIMEOUT_MS = 3_000L
        private const val SCAN_TIMEOUT_MS = 30_000L

        /** Returns a driver if an EmberZNet dongle answers an ASH reset within [timeoutMs]. */
        suspend fun connect(link: SerialLink, timeoutMs: Long): EzspDriver? {
            val ash = AshSession(link)
            if (!ash.reset(timeoutMs)) return null

            // Always opens in the legacy format; the reply says which version to switch to.
            var sequence = 0
            suspend fun version(format: Int, desired: Int): ByteArray {
                val seq = sequence++
                ash.send(EzspFrames.encode(format, seq, ID_VERSION, byteArrayOf(desired.toByte())))
                val deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS
                while (true) {
                    val data = ash.receive(deadline) ?: throw ZigbeeException("The dongle did not report its EZSP version.")
                    val frame = EzspFrames.decode(format, data) ?: continue
                    if (frame.sequence == seq && frame.frameId == ID_VERSION && frame.parameters.size >= 4) return frame.parameters
                }
            }

            var reply = version(LEGACY_VERSION, LEGACY_VERSION)
            val negotiated = reply.u8(0)
            if (negotiated != LEGACY_VERSION) reply = version(negotiated, negotiated)
            val stack = reply.u16le(2)
            val stackVersion = "%d.%d.%d.%d".format((stack shr 12) and 0xF, (stack shr 8) and 0xF, (stack shr 4) and 0xF, stack and 0xF)
            val info = DongleInfo(DongleFamily.EZSP, "EmberZNet $stackVersion (EZSP v$negotiated)")
            return EzspDriver(ash, negotiated, sequence, info)
        }
    }
}
