package com.example.banaanscan.zigbee

/** A frame as the radio heard it, without its checksum. */
class CapturedFrame(val channel: Int, val lqi: Int, val rssi: Int, val data: ByteArray)

enum class FrameType(val label: String) {
    BEACON("Beacon"), DATA("Data"), ACK("Ack"), COMMAND("MAC command"), OTHER("Other"),
}

/** What a frame's unencrypted parts suggest. Never proof: nothing here reads encrypted content. */
enum class ProtocolHint(val label: String) {
    UNKNOWN("Unknown protocol"),
    ENCRYPTED("Encrypted at the radio layer, protocol unknown"),
    THREAD("Looks like Thread / 6LoWPAN"),
    ZIGBEE("Looks like Zigbee"),
}

data class MacAddress(val short: Int? = null, val extended: String? = null) {
    val text: String get() = extended ?: "0x%04X".format(short ?: 0)
    val isBroadcast: Boolean get() = short != null && short >= 0xFFFC
}

class MacFrame(
    val type: FrameType,
    val secured: Boolean,
    val sequence: Int?,
    val destinationPan: Int?,
    val destination: MacAddress?,
    val sourcePan: Int?,
    val source: MacAddress?,
    val payload: ByteArray,
) {
    val hint: ProtocolHint
        get() = when {
            type == FrameType.BEACON -> beaconHint()
            secured -> ProtocolHint.ENCRYPTED // Zigbee encrypts above this layer, not here.
            type == FrameType.DATA && payload.size >= 8 && looksLikeZigbeeNwk() -> ProtocolHint.ZIGBEE
            type == FrameType.DATA && payload.isNotEmpty() && payload.u8(0) and 0xE0 == 0x60 -> ProtocolHint.THREAD
            else -> ProtocolHint.UNKNOWN
        }

    private fun looksLikeZigbeeNwk(): Boolean {
        val frameType = payload.u8(0) and 0x03
        val version = (payload.u8(0) shr 2) and 0x0F
        return frameType <= 1 && version == 2
    }

    private fun beaconHint(): ProtocolHint {
        if (payload.size < 4) return ProtocolHint.UNKNOWN
        val gtsCount = payload.u8(2) and 0x07
        val gtsDirections = if (gtsCount > 0) 1 else 0
        val at = 3 + gtsDirections + gtsCount * 3
        if (at >= payload.size) return ProtocolHint.UNKNOWN
        val pending = payload.u8(at)
        val body = at + 1 + (pending and 0x07) * 2 + ((pending shr 4) and 0x07) * 8
        return when (payload.getOrNull(body)?.toInt()?.and(0xFF)) {
            0x00 -> ProtocolHint.ZIGBEE
            0x03 -> ProtocolHint.THREAD
            else -> ProtocolHint.UNKNOWN
        }
    }
}

/** Decodes the unencrypted IEEE 802.15.4 MAC header (2006 and 2015 revisions). */
object Ieee802154 {
    private const val ADDRESS_NONE = 0
    private const val ADDRESS_SHORT = 2
    private const val ADDRESS_EXTENDED = 3

    /** Returns null for frames too short or malformed to trust. */
    fun parse(data: ByteArray): MacFrame? {
        if (data.size < 2) return null
        val control = data.u16le(0)
        val type = when (control and 0x07) {
            0 -> FrameType.BEACON
            1 -> FrameType.DATA
            2 -> FrameType.ACK
            3 -> FrameType.COMMAND
            else -> FrameType.OTHER
        }
        val secured = control and 0x08 != 0
        val compressed = control and 0x40 != 0
        val noSequence = control and 0x0100 != 0
        val version = (control shr 12) and 0x03
        val destinationMode = (control shr 10) and 0x03
        val sourceMode = (control shr 14) and 0x03
        if (destinationMode == 1 || sourceMode == 1 || version == 3) return null

        var at = 2
        val sequence = if (noSequence && version == 2) null else {
            if (at >= data.size) return null
            data.u8(at++)
        }
        // Multipurpose and other frame types have a different layout, so stop at what we know.
        if (type == FrameType.OTHER) {
            return MacFrame(type, secured, sequence, null, null, null, null, ByteArray(0))
        }

        val (destinationPanPresent, sourcePanPresent) =
            panIdsPresent(version, destinationMode, sourceMode, compressed)

        fun pan(): Int? {
            if (at + 2 > data.size) return null
            return data.u16le(at).also { at += 2 }
        }

        fun address(mode: Int): MacAddress? = when (mode) {
            ADDRESS_SHORT -> if (at + 2 > data.size) null else MacAddress(short = data.u16le(at)).also { at += 2 }
            ADDRESS_EXTENDED -> if (at + 8 > data.size) null else MacAddress(extended = data.eui64At(at)).also { at += 8 }
            else -> null
        }

        val destinationPan = if (destinationPanPresent) pan() ?: return null else null
        val destination = if (destinationMode != ADDRESS_NONE) address(destinationMode) ?: return null else null
        // A compressed source shares the destination's PAN.
        val sourcePan = when {
            sourcePanPresent -> pan() ?: return null
            sourceMode != ADDRESS_NONE && compressed -> destinationPan
            else -> null
        }
        val source = if (sourceMode != ADDRESS_NONE) address(sourceMode) ?: return null else null
        return MacFrame(type, secured, sequence, destinationPan, destination, sourcePan, source, data.copyOfRange(at, data.size))
    }

    private fun panIdsPresent(version: Int, destinationMode: Int, sourceMode: Int, compressed: Boolean): Pair<Boolean, Boolean> {
        val hasDestination = destinationMode != ADDRESS_NONE
        val hasSource = sourceMode != ADDRESS_NONE
        if (version < 2) return hasDestination to (hasSource && !compressed)
        // The 2015 revision's table: compression means something different for each combination.
        return when {
            !hasDestination && !hasSource -> compressed to false
            hasDestination && !hasSource -> !compressed to false
            !hasDestination -> false to !compressed
            destinationMode == ADDRESS_EXTENDED && sourceMode == ADDRESS_EXTENDED -> !compressed to false
            else -> true to !compressed
        }
    }
}

data class ChannelActivity(val channel: Int, val frames: Int, val strongestRssi: Int?)

/** A radio that appeared as sender or receiver. Short addresses only mean something within a PAN. */
data class SniffedDevice(
    val pan: Int?,
    val address: MacAddress,
    val channels: Set<Int>,
    val framesSent: Int,
    val framesReceived: Int,
    val encryptedFrames: Int,
    val types: Set<FrameType>,
    val hint: ProtocolHint,
    val rssi: Int?,
    val lqi: Int?,
    val possibleVendor: String?,
    val firstSeen: Long,
    val lastSeen: Long,
) {
    val key: String get() = "${pan ?: "?"}/${address.text}"
    /** Only ever seen as a destination, so the signal readings aren't its own. */
    val heardDirectly: Boolean get() = framesSent > 0
}

/** Turns a stream of captured frames into per-channel activity and a list of radios. */
class SnifferLog(
    private val vendorOf: (String) -> String? = { null },
    private val maxDevices: Int = 500,
) {
    private val activity = HashMap<Int, ChannelActivity>()
    private val devices = LinkedHashMap<String, SniffedDevice>()
    var totalFrames = 0
        private set
    var undecodedFrames = 0
        private set

    fun add(frame: CapturedFrame, now: Long = System.currentTimeMillis()) {
        totalFrames++
        val previous = activity[frame.channel]
        activity[frame.channel] = ChannelActivity(
            frame.channel,
            (previous?.frames ?: 0) + 1,
            listOfNotNull(previous?.strongestRssi, frame.rssi).maxOrNull(),
        )
        val mac = Ieee802154.parse(frame.data)
        if (mac == null) {
            undecodedFrames++
            return
        }
        mac.source?.let { record(mac.sourcePan ?: mac.destinationPan, it, mac, frame, now, sent = true) }
        mac.destination?.takeUnless { it.isBroadcast }?.let {
            record(mac.destinationPan ?: mac.sourcePan, it, mac, frame, now, sent = false)
        }
    }

    private fun record(pan: Int?, address: MacAddress, mac: MacFrame, frame: CapturedFrame, now: Long, sent: Boolean) {
        val key = "${pan ?: "?"}/${address.text}"
        val old = devices[key]
        if (old == null && devices.size >= maxDevices) return
        // Keep the most specific evidence: enum order runs from least to most telling.
        val hint = listOfNotNull(old?.hint, mac.hint).maxByOrNull { it.ordinal } ?: mac.hint
        devices[key] = SniffedDevice(
            pan = pan,
            address = address,
            channels = old?.channels.orEmpty() + frame.channel,
            framesSent = (old?.framesSent ?: 0) + if (sent) 1 else 0,
            framesReceived = (old?.framesReceived ?: 0) + if (sent) 0 else 1,
            encryptedFrames = (old?.encryptedFrames ?: 0) + if (sent && mac.secured) 1 else 0,
            types = old?.types.orEmpty() + mac.type,
            hint = hint,
            rssi = if (sent) frame.rssi else old?.rssi,
            lqi = if (sent) frame.lqi else old?.lqi,
            possibleVendor = old?.possibleVendor ?: address.extended?.let(vendorOf),
            firstSeen = old?.firstSeen ?: now,
            lastSeen = now,
        )
    }

    fun channels(): List<ChannelActivity> = activity.values.sortedBy { it.channel }

    fun devices(): List<SniffedDevice> =
        devices.values.sortedByDescending { it.framesSent + it.framesReceived }
}
