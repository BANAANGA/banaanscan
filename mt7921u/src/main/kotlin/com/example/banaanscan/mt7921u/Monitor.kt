// Ported from the Linux mt76 driver (mt7921/mcu.c, mt7921/main.c, mt7921/mac.c, mt76_connac2_mac.h, mt792x.h),
// Copyright (C) 2020-2023 MediaTek Inc. BSD-3-Clause-Clear: see NOTICE.
package com.example.banaanscan.mt7921u

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One received 802.11 frame, without its checksum. */
class RxFrame(val freqMhz: Int, val rssi: Int?, val fcsError: Boolean, val data: ByteArray)

/** A channel the sniffer can tune to, 20 MHz wide. */
data class Channel(val band: Band, val number: Int) {
    enum class Band(val code: Int) { GHZ_2_4(1), GHZ_5(2), GHZ_6(3) }

    val freqMhz: Int get() = when (band) {
        Band.GHZ_2_4 -> if (number == 14) 2484 else 2407 + 5 * number
        Band.GHZ_5 -> 5000 + 5 * number
        Band.GHZ_6 -> 5950 + 5 * number
    }

    companion object {
        fun fromFreq(mhz: Int): Channel = when {
            mhz == 2484 -> Channel(Band.GHZ_2_4, 14)
            mhz in 2412..2472 -> Channel(Band.GHZ_2_4, (mhz - 2407) / 5)
            mhz in 5160..5885 -> Channel(Band.GHZ_5, (mhz - 5000) / 5)
            mhz in 5955..7115 -> Channel(Band.GHZ_6, (mhz - 5950) / 5)
            else -> throw IllegalArgumentException("Not a Wi-Fi channel: $mhz MHz")
        }
    }
}

/** Decodes the receive descriptor the chip puts in front of every frame (mt7921_mac_fill_rx). */
object RxDescriptor {
    private const val PKT_TYPE_NORMAL = 2
    private const val GROUP_1 = 1 shl 11
    private const val GROUP_2 = 1 shl 12
    private const val GROUP_3 = 1 shl 13
    private const val GROUP_4 = 1 shl 14
    private const val GROUP_5 = 1 shl 15
    private const val FCS_ERR = 1 shl 27
    private const val BAND_IDX = 1 shl 28
    private const val HDR_TRANS = 1 shl 13
    private const val MAX_LEN_ERROR = 1 shl 24

    /** Returns null for buffers that aren't received frames (events, status reports) or that look damaged. */
    fun parse(buffer: ByteArray, length: Int): RxFrame? {
        if (length < 24) return null
        val b = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val rxd0 = b.getInt(0)
        val size = rxd0 and 0xffff
        if ((rxd0 ushr 27) != PKT_TYPE_NORMAL || size < 24 || size > length) return null
        val rxd1 = b.getInt(4)
        val rxd2 = b.getInt(8)
        val rxd3 = b.getInt(12)
        if (rxd1 and BAND_IDX != 0 || rxd2 and MAX_LEN_ERROR != 0 || rxd2 and HDR_TRANS != 0) return null

        var at = 24
        if (rxd1 and GROUP_4 != 0) at += 16
        if (rxd1 and GROUP_1 != 0) at += 16
        if (rxd1 and GROUP_2 != 0) at += 8
        var rcpi: Int? = null
        if (rxd1 and GROUP_3 != 0) {
            if (at + 8 > size) return null
            rcpi = b.getInt(at + 4)
            at += 8
            if (rxd1 and GROUP_5 != 0) {
                at += 24
                if (at + 4 > size) return null
                rcpi = b.getInt(at) // monitor mode reports signal here instead
                at += 48
            }
        }
        val headerGap = at + 2 * ((rxd2 ushr 14) and 3)
        if (headerGap >= size) return null

        return RxFrame(
            freqMhz = channelToFreq((rxd3 ushr 8) and 0xff),
            rssi = rcpi?.let(::strongestSignal),
            fcsError = rxd1 and FCS_ERR != 0,
            data = buffer.copyOfRange(headerGap, size),
        )
    }

    /** The chip numbers 6 GHz channels from 181 upwards (mt792x_get_status_freq_info). */
    fun channelToFreq(code: Int): Int = when {
        code > 180 -> 5950 + 5 * ((code - 181) * 4 + 1)
        code > 14 -> 5000 + 5 * code
        code == 14 -> 2484
        else -> 2407 + 5 * code
    }

    /** RCPI per antenna, one byte each; dBm = (RCPI - 220) / 2. The MT7921 has two antennas. */
    private fun strongestSignal(rcpi: Int): Int? =
        listOf(rcpi and 0xff, (rcpi ushr 8) and 0xff).filter { it in 1..254 }.maxOrNull()?.let { (it - 220) / 2 }
}

/**
 * Monitor mode, the way Linux's mt7921 driver does it for a monitor interface: an interface record, the firmware's
 * sniffer switched on and tuned, and receive filters opened up. Nothing is transmitted.
 */
class Monitor(private val chip: Mt7921u) {
    private val buffer = ByteArray(32 * 1024)

    fun start(channel: Channel) {
        // Calibration from the chip's eFuse (buffer mode 0, whole-table format), then start the MAC.
        chip.send(Mcu.EXT_CID, byteArrayOf(0, 1, 0, 0), extCid = Mcu.EXT_EFUSE_BUFFER_MODE)
        chip.send(Mcu.EXT_CID, byteArrayOf(1, 0, 0, 0), extCid = Mcu.EXT_MAC_INIT_CTRL)
        // Keep the radio fully powered: power saving would make it doze between beacons.
        chip.send(Mcu.CE_CHIP_CONFIG, chipConfig("KeepFullPwr 1"), wait = false, ce = true)
        // A monitor interface, with no MAC address of its own.
        // Header (own-MAC index 0, band 0), then a 12-byte "basic" record: active, no address.
        chip.sendUni(Mcu.UNI_DEV_INFO_UPDATE, hex("00000000" + "0000" + "0c00" + "01" + "00" + "000000000000"))
        // Header, then a 32-byte "basic" network record: active, own-MAC 0, monitor-style network type. As Linux sends it.
        chip.sendUni(Mcu.UNI_BSS_INFO_UPDATE, hex("00000000" + "0000" + "2000" + "01000000" + "02000100" + "01000000" + "00".repeat(16)))
        chip.sendUni(Mcu.UNI_SNIFFER, hex("00000000" + "0000" + "0800" + "01000000"))
        tune(channel)
        // Let in control frames and other networks' traffic, and stop dropping other networks' beacons.
        chip.send(Mcu.CE_SET_RX_FILTER, rxFilter(mode = 1, flags = FILTER_ENABLE or FILTER_CONTROL or FILTER_OTHER_BSS), wait = false, ce = true)
        chip.send(Mcu.CE_SET_RX_FILTER, rxFilter(mode = 2, bitMap = DROP_OTHER_BEACON, bitOp = BIT_CLEAR), wait = false, ce = true)
        chip.log("monitor mode on ${channel.freqMhz} MHz")
    }

    /** Retunes the sniffer (mt7921_mcu_config_sniffer), 20 MHz wide, dropping frames that fail their checksum. */
    fun tune(channel: Channel) {
        val b = ByteBuffer.allocate(20).order(ByteOrder.LITTLE_ENDIAN)
        b.putInt(0)                       // band index 0
        b.putShort(1).putShort(16)        // config TLV
        b.putShort(0)                     // aid
        b.put(channel.band.code.toByte()).put(0)
        b.put(channel.number.toByte()).put(0)
        b.put(channel.number.toByte()).put(0)
        b.put(1)                          // drop frames with errors
        chip.sendUni(Mcu.UNI_SNIFFER, b.array())
    }

    /** The next received frame, or null if none arrives within [timeoutMs]. */
    fun receive(timeoutMs: Int): RxFrame? {
        chip.pendingFrames.removeFirstOrNull()?.let { raw -> RxDescriptor.parse(raw, raw.size)?.let { return it } }
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = (end - System.currentTimeMillis()).toInt()
            if (left <= 0) return null
            val n = chip.readRaw(buffer, minOf(left, 200))
            if (n > 0) RxDescriptor.parse(buffer, n)?.let { return it }
        }
    }

    private fun chipConfig(text: String): ByteArray = ByteArray(8 + 320).also { text.toByteArray().copyInto(it, 8) }

    private fun rxFilter(mode: Int, flags: Long = 0, bitMap: Long = 0, bitOp: Int = 0): ByteArray {
        val b = ByteBuffer.allocate(68).order(ByteOrder.LITTLE_ENDIAN)
        b.position(4)
        b.put(mode.toByte())
        b.position(8)
        b.putInt(flags.toInt()).putInt(bitMap.toInt()).put(bitOp.toByte())
        return b.array()
    }

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private companion object {
        const val FILTER_CONTROL = 1L shl 5
        const val FILTER_OTHER_BSS = 1L shl 6
        const val FILTER_ENABLE = 1L shl 31
        const val DROP_OTHER_BEACON = 1L shl 11
        const val BIT_CLEAR = 1 shl 1
    }
}
