package com.example.banaanscan.zigbee

import java.io.Closeable

/** Everything in this package except [ZigbeeScanner] is free of Android classes, so it runs in JVM tests. */
interface SerialLink : Closeable {
    fun write(data: ByteArray)

    /** Reads into [buffer]; returns the number of bytes read, or 0 if [timeoutMs] passes first. */
    fun read(buffer: ByteArray, timeoutMs: Int): Int

    fun setPins(dtr: Boolean, rts: Boolean)
}

enum class DongleFamily(val label: String) {
    ZSTACK("TI Z-Stack"),
    EZSP("Silicon Labs EZSP"),
}

data class DongleInfo(val family: DongleFamily, val firmware: String, val notes: List<String> = emptyList())

/** One Zigbee network heard during an active scan. Fields a dongle doesn't report are null. */
data class ZigbeeNetwork(
    /** MSB first, colon separated: the way other Zigbee tools display it. */
    val extendedPanId: String,
    val panId: Int,
    val channel: Int,
    val permitJoining: Boolean,
    val stackProfile: Int,
    val updateId: Int,
    val lqi: Int? = null,
    val rssi: Int? = null,
    val routerCapacity: Boolean? = null,
    val deviceCapacity: Boolean? = null,
    val protocolVersion: Int? = null,
    /** Short addresses of the routers that answered. Silicon Labs dongles don't report these. */
    val routers: Set<Int> = emptySet(),
    /** Vendor of the extended PAN ID's OUI. Only a hint: many coordinators use a random ID. */
    val possibleVendor: String? = null,
) {
    val key: String get() = "$extendedPanId/$panId/$channel"

    /** Several routers of one network each answer a scan: combine them into one entry. */
    fun mergedWith(earlier: ZigbeeNetwork?): ZigbeeNetwork = if (earlier == null) this else copy(
        lqi = listOfNotNull(lqi, earlier.lqi).maxOrNull(),
        rssi = listOfNotNull(rssi, earlier.rssi).maxOrNull(),
        permitJoining = permitJoining || earlier.permitJoining,
        routers = routers + earlier.routers,
    )
}

/** Strongest signal measured on a channel, in dBm. */
data class ChannelEnergy(val channel: Int, val rssi: Int)

interface ScanListener {
    fun onNetwork(network: ZigbeeNetwork)
    fun onEnergy(energy: ChannelEnergy)
    fun onProgress(message: String)
}

/**
 * A connected coordinator. Implementations only use commands that leave the dongle's stored
 * settings alone, so a scan (or a yanked cable) can't damage the Zigbee network it coordinates.
 */
interface ZigbeeDriver {
    val info: DongleInfo
    suspend fun scan(listener: ScanListener)
}

/** Passive raw frame capture, for firmware that offers it. Runs until cancelled. */
interface FrameCapture {
    suspend fun capture(channels: List<Int>, dwellMs: Long, onChannel: (Int) -> Unit, onFrame: (CapturedFrame) -> Unit)
}

class ZigbeeException(message: String) : Exception(message)

internal const val ALL_CHANNELS_MASK = 0x07FFF800L

internal fun ByteArray.u8(index: Int): Int = this[index].toInt() and 0xFF

internal fun ByteArray.u16le(index: Int): Int = u8(index) or (u8(index + 1) shl 8)

internal fun u32leBytes(value: Long): ByteArray =
    ByteArray(4) { ((value shr (8 * it)) and 0xFF).toInt().toByte() }

/** Zigbee sends EUI-64s least significant byte first but displays them most significant first. */
internal fun ByteArray.eui64At(index: Int): String =
    (7 downTo 0).joinToString(":") { "%02X".format(u8(index + it)) }

/** Channels 11-26 present in a channel bitmask. */
internal fun channelsIn(mask: Long): List<Int> = (11..26).filter { (mask shr it) and 1L == 1L }
