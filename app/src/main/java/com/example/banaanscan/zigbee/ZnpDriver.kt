package com.example.banaanscan.zigbee

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Framing of TI's Monitor & Test protocol: FE, length, cmd0, cmd1, data, XOR checksum. */
object ZnpCodec {
    const val SOF = 0xFE
    private const val MAX_DATA = 250
    private const val BEACON_SIZE = 21

    class Frame(val cmd0: Int, val cmd1: Int, val data: ByteArray)

    fun encode(cmd0: Int, cmd1: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(data.size + 5)
        out[0] = SOF.toByte()
        out[1] = data.size.toByte()
        out[2] = cmd0.toByte()
        out[3] = cmd1.toByte()
        data.copyInto(out, 4)
        var fcs = 0
        for (i in 1 until out.size - 1) fcs = fcs xor out.u8(i)
        out[out.size - 1] = fcs.toByte()
        return out
    }

    /** Reassembles frames from arbitrary chunks, resynchronising on the next SOF after bad data. */
    class Parser {
        private var buffer = ByteArray(0)

        fun feed(data: ByteArray, length: Int = data.size): List<Frame> {
            buffer += data.copyOf(length)
            val frames = ArrayList<Frame>()
            while (buffer.isNotEmpty()) {
                if (buffer.u8(0) != SOF) {
                    dropToNextSof()
                    continue
                }
                if (buffer.size < 2) break
                val size = buffer.u8(1)
                if (size > MAX_DATA) {
                    dropToNextSof()
                    continue
                }
                if (buffer.size < size + 5) break
                var fcs = 0
                for (i in 1 until size + 4) fcs = fcs xor buffer.u8(i)
                if (fcs != buffer.u8(size + 4)) {
                    dropToNextSof()
                    continue
                }
                frames += Frame(buffer.u8(2), buffer.u8(3), buffer.copyOfRange(4, size + 4))
                buffer = buffer.copyOfRange(size + 5, buffer.size)
            }
            return frames
        }

        private fun dropToNextSof() {
            val next = (1 until buffer.size).firstOrNull { buffer.u8(it) == SOF }
            buffer = if (next == null) ByteArray(0) else buffer.copyOfRange(next, buffer.size)
        }
    }

    /** ZDO_BEACON_NOTIFY_IND payload: a count, then fixed-size beacon records. */
    fun parseBeacons(data: ByteArray): List<ZigbeeNetwork> {
        if (data.isEmpty()) return emptyList()
        val available = (data.size - 1) / BEACON_SIZE
        return (0 until minOf(data.u8(0), available)).map { n ->
            val at = 1 + n * BEACON_SIZE
            ZigbeeNetwork(
                extendedPanId = data.eui64At(at + 13),
                panId = data.u16le(at + 2),
                channel = data.u8(at + 4),
                permitJoining = data.u8(at + 5) != 0,
                routerCapacity = data.u8(at + 6) != 0,
                deviceCapacity = data.u8(at + 7) != 0,
                protocolVersion = data.u8(at + 8),
                stackProfile = data.u8(at + 9),
                lqi = data.u8(at + 10),
                updateId = data.u8(at + 12),
                routers = setOf(data.u16le(at)),
            )
        }
    }
}

/** Z-Stack coordinators such as the Sonoff ZBDongle-P and other CC2652 sticks. */
class ZnpDriver private constructor(
    private val link: SerialLink,
    private val parser: ZnpCodec.Parser,
    override val info: DongleInfo,
) : ZigbeeDriver {

    override suspend fun scan(listener: ScanListener) {
        listener.onProgress("Scanning all channels for Zigbee networks…")
        val pending = ArrayList<ZnpCodec.Frame>()
        val response = request(link, parser, SREQ_ZDO, CMD_NWK_DISCOVERY, u32leBytes(ALL_CHANNELS_MASK) + SCAN_EXPONENT.toByte(), REQUEST_TIMEOUT_MS, pending)
            ?: throw ZigbeeException("The dongle did not answer the scan request.")
        if (response.data.isEmpty() || response.data.u8(0) != 0) {
            throw ZigbeeException(
                "The dongle refused to scan (status ${response.data.firstOrNull()}). This can happen when its network is " +
                    "already running: unplug it, plug it back in and try again.",
            )
        }

        val deadline = System.currentTimeMillis() + SCAN_TIMEOUT_MS
        val buffer = ByteArray(256)
        while (true) {
            currentCoroutineContext().ensureActive()
            for (frame in pending) {
                if (frame.cmd0 != AREQ_ZDO) continue
                when (frame.cmd1) {
                    CMD_BEACON_NOTIFY -> ZnpCodec.parseBeacons(frame.data).forEach(listener::onNetwork)
                    CMD_NWK_DISCOVERY_CNF -> return
                }
            }
            pending.clear()
            if (System.currentTimeMillis() > deadline) throw ZigbeeException("The scan did not finish in time.")
            val n = link.read(buffer, READ_SLICE_MS)
            if (n > 0) pending += parser.feed(buffer, n)
        }
    }

    companion object {
        private const val SREQ_SYS = 0x21
        private const val SRSP_SYS = 0x61
        private const val SREQ_ZDO = 0x25
        private const val AREQ_ZDO = 0x45
        private const val SRSP_RPC_ERROR = 0x60
        private const val CMD_PING = 0x01
        private const val CMD_VERSION = 0x02
        private const val CMD_NV_READ = 0x08
        private const val CMD_NWK_DISCOVERY = 0x26
        private const val CMD_BEACON_NOTIFY = 0xC5
        private const val CMD_NWK_DISCOVERY_CNF = 0xC7
        private const val NV_CHANLIST = 0x0084
        private const val SCAN_EXPONENT = 4 // about 0.26 s per channel, 4.2 s in total
        private const val REQUEST_TIMEOUT_MS = 3_000L
        private const val SCAN_TIMEOUT_MS = 30_000L
        private const val READ_SLICE_MS = 100

        /** Sends a request and returns its synchronous response. Other frames land in [pending]. */
        private suspend fun request(
            link: SerialLink,
            parser: ZnpCodec.Parser,
            cmd0: Int,
            cmd1: Int,
            data: ByteArray,
            timeoutMs: Long,
            pending: MutableList<ZnpCodec.Frame>? = null,
        ): ZnpCodec.Frame? {
            link.write(ZnpCodec.encode(cmd0, cmd1, data))
            val expected = (cmd0 and 0x1F) or 0x60
            val buffer = ByteArray(256)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (System.currentTimeMillis() < deadline) {
                currentCoroutineContext().ensureActive()
                val n = link.read(buffer, READ_SLICE_MS)
                if (n <= 0) continue
                for (frame in parser.feed(buffer, n)) {
                    if (frame.cmd0 == expected && frame.cmd1 == cmd1) return frame
                    // MT answers commands it doesn't know with an RPC error.
                    if (frame.cmd0 == SRSP_RPC_ERROR) return null
                    pending?.add(frame)
                }
            }
            return null
        }

        /** Returns a driver if a Z-Stack coordinator answers a ping within [timeoutMs]. */
        suspend fun connect(link: SerialLink, timeoutMs: Long): ZnpDriver? {
            val parser = ZnpCodec.Parser()
            val ping = request(link, parser, SREQ_SYS, CMD_PING, ByteArray(0), timeoutMs) ?: return null
            if (ping.cmd0 != SRSP_SYS) return null

            val notes = ArrayList<String>()
            val firmware = request(link, parser, SREQ_SYS, CMD_VERSION, ByteArray(0), REQUEST_TIMEOUT_MS)
                ?.let { describeVersion(it.data) } ?: "Z-Stack (version unknown)"
            notes += "Z-Stack can only measure channel energy with its network running, so BanaanScan skips that to stay read-only."

            // Reading is safe; other tools widen this list by rewriting it, which we refuse to do.
            val nv = request(link, parser, SREQ_SYS, CMD_NV_READ, byteArrayOf(NV_CHANLIST.toByte(), (NV_CHANLIST shr 8).toByte(), 0), REQUEST_TIMEOUT_MS)
            if (nv != null && nv.data.size >= 6 && nv.data.u8(0) == 0) {
                val mask = nv.data.u8(2).toLong() or (nv.data.u8(3).toLong() shl 8) or
                    (nv.data.u8(4).toLong() shl 16) or (nv.data.u8(5).toLong() shl 24)
                if (mask and ALL_CHANNELS_MASK != ALL_CHANNELS_MASK) {
                    notes += "This dongle is configured for channel ${channelsIn(mask).joinToString(", ")}. " +
                        "Z-Stack may only find networks there, so networks on other channels could be missing."
                }
            }
            return ZnpDriver(link, parser, DongleInfo(DongleFamily.ZSTACK, firmware, notes))
        }

        private fun describeVersion(data: ByteArray): String {
            if (data.size < 5) return "Z-Stack (version unknown)"
            val build = if (data.size >= 9) {
                data.u8(5).toLong() or (data.u8(6).toLong() shl 8) or (data.u8(7).toLong() shl 16) or (data.u8(8).toLong() shl 24)
            } else 0L
            return "Z-Stack ${data.u8(2)}.${data.u8(3)}.${data.u8(4)}" + if (build > 0) " (build $build)" else ""
        }
    }
}
