// Ported from the Linux mt76 driver (mt76_connac_mcu.c/.h), Copyright (C) 2020 MediaTek Inc.
// BSD-3-Clause-Clear: see NOTICE.
package com.example.banaanscan.mt7921u

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** One piece of firmware to copy into the chip: where it goes, its bytes, and how the chip should treat them. */
class FirmwareRegion(val address: Long, val data: ByteArray, val mode: Long)

private const val DL_MODE_ENCRYPT = 1L shl 0
private const val DL_MODE_RESET_SEC_IV = 1L shl 3
private const val DL_CONFIG_ENCRY_MODE_SEL = 1L shl 6
private const val DL_MODE_NEED_RSP = 1L shl 31

/** The ROM patch file, WIFI_MT7961_patch_mcu_1_2_hdr.bin. Big-endian header, 96 bytes, then 64-byte sections. */
class PatchFile(bytes: ByteArray) {
    val buildDate: String
    val hwSwVersion: Long
    val regions: List<FirmwareRegion>

    init {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        require(bytes.size > 96) { "Patch file is too short" }
        buildDate = String(bytes, 0, 16, Charsets.US_ASCII).takeWhile { it in ' '..'~' }.trimEnd()
        hwSwVersion = b.getInt(20).toLong() and 0xffffffffL
        val count = b.getInt(32 + 12)
        require(count in 1..16) { "Unexpected number of patch sections: $count" }
        regions = (0 until count).map { i ->
            val at = 96 + i * 64
            require((b.getInt(at) and 0xffff) == 2) { "Patch section $i isn't an info section" }
            val offset = b.getInt(at + 4)
            val address = b.getInt(at + 12).toLong() and 0xffffffffL
            val length = b.getInt(at + 16)
            val info = b.getInt(at + 20).toLong() and 0xffffffffL
            require(offset >= 0 && length >= 0 && offset + length <= bytes.size) { "Patch section $i is out of range" }
            FirmwareRegion(address, bytes.copyOfRange(offset, offset + length), patchMode(info))
        }
    }

    private fun patchMode(info: Long): Long {
        if (info == 0xffffffffL) return DL_MODE_NEED_RSP
        return when ((info ushr 24) and 0xff) {
            0L -> DL_MODE_NEED_RSP
            1L -> DL_MODE_NEED_RSP or DL_MODE_ENCRYPT or (((info and 0xff) shl 1) and 0b110) or DL_MODE_RESET_SEC_IV
            2L -> DL_MODE_NEED_RSP or DL_MODE_ENCRYPT or DL_CONFIG_ENCRY_MODE_SEL or DL_MODE_RESET_SEC_IV
            else -> throw IllegalArgumentException("Unsupported patch encryption type")
        }
    }
}

/** The RAM firmware, WIFI_RAM_CODE_MT7961_1.bin: region data first, then 40-byte region records and a 36-byte trailer. */
class RamFile(bytes: ByteArray) {
    val version: String
    val buildDate: String
    val regions: List<FirmwareRegion>
    /** Where the firmware starts running, if a region asks to override the default. */
    val startAddress: Long?

    init {
        require(bytes.size > 36) { "Firmware file is too short" }
        val trailer = bytes.size - 36
        val count = bytes[trailer + 2].toInt() and 0xff
        version = String(bytes, trailer + 7, 10, Charsets.US_ASCII).trimEnd('\u0000')
        buildDate = String(bytes, trailer + 17, 15, Charsets.US_ASCII).trimEnd('\u0000')
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        var offset = 0
        var override: Long? = null
        val list = ArrayList<FirmwareRegion>()
        for (i in 0 until count) {
            val at = trailer - (count - i) * 40
            require(at >= 0) { "Firmware region table is out of range" }
            val address = b.getInt(at + 16).toLong() and 0xffffffffL
            val length = b.getInt(at + 20)
            val feature = bytes[at + 24].toInt() and 0xff
            require(length >= 0 && offset + length <= bytes.size) { "Firmware region $i is out of range" }
            if (feature and 0x40 == 0) { // FW_FEATURE_NON_DL: nothing to send
                if (feature and 0x20 != 0) override = address
                var mode = DL_MODE_NEED_RSP or ((((feature shr 1) and 3) shl 1).toLong())
                if (feature and 1 != 0) mode = mode or DL_MODE_ENCRYPT or DL_MODE_RESET_SEC_IV
                if (feature and 0x10 != 0) mode = mode or DL_CONFIG_ENCRY_MODE_SEL
                list += FirmwareRegion(address, bytes.copyOfRange(offset, offset + length), mode)
            }
            offset += length
        }
        regions = list
        startAddress = override
    }
}
