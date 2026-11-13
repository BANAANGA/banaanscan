// Ported from the Linux mt76 driver (mt76_connac_mcu.c/.h, mt76_connac2_mac.h, mt792x_usb.c, mt7921/usb.c),
// Copyright (C) 2018 Lorenzo Bianconi and (C) 2020-2023 MediaTek Inc. BSD-3-Clause-Clear: see NOTICE.
package com.example.banaanscan.mt7921u

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** The firmware's command and event formats for MT7921 over USB ("connac2" in MediaTek's terms). */
object Mcu {
    // Command IDs (MCU_CMD_*)
    const val TARGET_ADDRESS_LEN_REQ = 0x01
    const val FW_START_REQ = 0x02
    const val NIC_POWER_CTRL = 0x04
    const val PATCH_START_REQ = 0x05
    const val PATCH_FINISH_REQ = 0x07
    const val PATCH_SEM_CONTROL = 0x10
    const val GET_NIC_CAPAB = 0x8a // a "CE" command
    const val EXT_CID = 0xed
    const val CE_SET_RX_FILTER = 0x0a
    const val CE_CHIP_CONFIG = 0xca

    // Extended commands, sent as EXT_CID with this second ID
    const val EXT_EFUSE_BUFFER_MODE = 0x21
    const val EXT_MAC_INIT_CTRL = 0x46

    // Unified commands
    const val UNI_DEV_INFO_UPDATE = 0x01
    const val UNI_BSS_INFO_UPDATE = 0x02
    const val UNI_SNIFFER = 0x24

    // Patch semaphore replies
    const val PATCH_NOT_DL_SEM_FAIL = 0
    const val PATCH_IS_DL = 1
    const val PATCH_NOT_DL_SEM_SUCCESS = 2
    const val PATCH_REL_SEM_SUCCESS = 3

    const val TXD_SIZE = 64      // hardware TXD (32) + MCU header (32)
    const val UNI_TXD_SIZE = 48  // hardware TXD (32) + unified MCU header (16)
    private const val UNI_EXT_ACK = 0x07
    const val RXD_SIZE = 36      // hardware RXD (24) + MCU event header (12)
    private const val PKT_ID = 0xa0
    private const val Q_SET = 1
    private const val Q_NA = 3
    private const val TX_TYPE_CMD = 2
    private const val HDR_FORMAT_CMD = 1
    private const val MCU_PORT_RX_Q0 = 0x20
    private const val PORT_IDX_MCU = 1
    private const val PKT_TYPE_EVENT = 7

    private fun le(size: Int) = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    /** Adds the 4-byte USB header and the tail padding the device expects (mt7921u_mcu_send_message). */
    private fun usbFrame(body: ByteArray): ByteArray {
        val len = 4 + body.size
        val pad = ((len + 3) and 3.inv()) + 4 - len
        return le(len + pad).putInt(body.size).put(body).array()
    }

    /** A command for the MCU, sent on the in-band command endpoint. */
    fun command(cid: Int, seq: Int, payload: ByteArray = ByteArray(0), extCid: Int = 0, ce: Boolean = false): ByteArray {
        val b = le(TXD_SIZE + payload.size)
        b.putInt((TXD_SIZE + payload.size) or (TX_TYPE_CMD shl 23) or (MCU_PORT_RX_Q0 shl 25))
        b.putInt((1 shl 31) or (HDR_FORMAT_CMD shl 16))
        b.position(32)
        b.putShort((TXD_SIZE - 32 + payload.size).toShort())
        b.putShort(((PORT_IDX_MCU shl 15) or (MCU_PORT_RX_Q0 shl 10)).toShort())
        b.put(cid.toByte())
        b.put(PKT_ID.toByte())
        b.put((if (extCid != 0 || ce) Q_SET else Q_NA).toByte())
        b.put(seq.toByte())
        b.put(0)                  // uc_d2b0_rev
        b.put(extCid.toByte())
        b.put(0)                  // s2d_index: host to N9
        b.put((if (extCid != 0) 1 else 0).toByte())
        b.position(TXD_SIZE)
        b.put(payload)
        return usbFrame(b.array())
    }

    /** A command in the newer "unified" format, which has a 16-bit command ID and no set/query field. */
    fun uniCommand(cid: Int, seq: Int, payload: ByteArray): ByteArray {
        val b = le(UNI_TXD_SIZE + payload.size)
        b.putInt((UNI_TXD_SIZE + payload.size) or (TX_TYPE_CMD shl 23) or (MCU_PORT_RX_Q0 shl 25))
        b.putInt((1 shl 31) or (HDR_FORMAT_CMD shl 16))
        b.position(32)
        b.putShort((UNI_TXD_SIZE - 32 + payload.size).toShort())
        b.putShort(cid.toShort())
        b.put(0)
        b.put(PKT_ID.toByte())
        b.put(0)                  // frag_n
        b.put(seq.toByte())
        b.putShort(0)             // checksum
        b.put(0)                  // s2d_index: host to N9
        b.put(UNI_EXT_ACK.toByte())
        b.position(UNI_TXD_SIZE)
        b.put(payload)
        return usbFrame(b.array())
    }

    /** A firmware chunk: no MCU header at all, sent on the AC_BE data endpoint. */
    fun scatter(chunk: ByteArray): ByteArray = usbFrame(chunk)

    fun u32s(vararg values: Long): ByteArray {
        val b = le(4 * values.size)
        values.forEach { b.putInt(it.toInt()) }
        return b.array()
    }

    class Event(val eid: Int, val seq: Int, val extEid: Int, val raw: ByteArray) {
        /** Payload after the 36-byte header. */
        val body: ByteArray get() = raw.copyOfRange(minOf(RXD_SIZE, raw.size), raw.size)

        /** Patch semaphore and finish replies carry a status byte straight after the 32-byte header. */
        val status: Int get() = if (raw.size > 32) raw[32].toInt() and 0xff else -1
    }

    /** Parses one received buffer. Returns null for things that aren't MCU events, such as Wi-Fi frames. */
    fun parseEvent(buffer: ByteArray, length: Int): Event? {
        if (length < 32) return null
        val b = ByteBuffer.wrap(buffer, 0, length).order(ByteOrder.LITTLE_ENDIAN)
        val rxd0 = b.getInt(0)
        val size = rxd0 and 0xffff
        if ((rxd0 ushr 27) != PKT_TYPE_EVENT || size < 32 || size > length) return null
        return Event(eid = buffer[28].toInt() and 0xff, seq = buffer[29].toInt() and 0xff,
            extEid = buffer[32].toInt() and 0xff, raw = buffer.copyOfRange(0, size))
    }
}
