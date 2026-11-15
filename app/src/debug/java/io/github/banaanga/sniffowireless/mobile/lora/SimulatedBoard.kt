package io.github.banaanga.sniffowireless.mobile.lora

import io.github.banaanga.sniffowireless.mobile.core.LineLink
import io.github.banaanga.sniffowireless.mobile.core.Meshtastic
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayDeque

/** Debug builds: a lora-sniffer board made up in software, for the emulator (no USB host there). All names are invented. */
object LoraDebug {
    fun simulatedBoard(): (() -> LineLink)? = { SimulatedBoard() }
}

private class SimulatedBoard : LineLink {
    private val out = ArrayDeque<String>()
    private var sync = 0
    private var freq = "0"
    private var bw = "0"
    private var sf = 0
    private var n = 0
    private var stat = 0

    override fun writeLine(text: String) {
        when {
            text == "PING" -> out += "PONG"
            text == "INFO" -> out += "INFO,lora-sniffer,1,heltec_v3,sx1262,idle,0.0000,0.0,0,0x00,0,0,2"
            text.startsWith("CFG,") -> {
                val f = text.split(",")
                freq = "%.4f".format(java.util.Locale.ROOT, f[1].toDouble())
                bw = "%.1f".format(java.util.Locale.ROOT, f[2].toDouble())
                sf = f[3].toInt()
                sync = f[5].removePrefix("0x").toInt(16)
                out += "OK,${text}"
            }
        }
    }

    override fun readLine(timeoutMs: Long): String? {
        out.pollFirst()?.let { return it }
        Thread.sleep(minOf(timeoutMs, 900L))
        if (++stat % 12 == 0) return "STAT,${stat * 1000},$n,${stat / 12}"
        val frames = when (sync) {
            0x2B -> MESHTASTIC
            0x12 -> MESHCORE
            0x34 -> LORAWAN
            else -> return null
        }
        val frame = frames[n++ % frames.size]
        val rssi = -70.0 - (n * 7 % 45)
        return "RX,${stat * 1000},$freq,$bw,$sf,0x%02X,%.1f,%.2f,-850,${frame.size},%s".format(java.util.Locale.ROOT, sync, rssi, 9.5 - (n % 12), frame.hex())
    }

    private companion object {
        fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        fun le(vararg ints: Int) = ByteBuffer.allocate(4 * ints.size).order(ByteOrder.LITTLE_ENDIAN).apply { ints.forEach { putInt(it) } }.array()

        fun varint(v: Long): ByteArray {
            val o = ByteArrayOutputStream()
            var x = v
            while (true) {
                val b = (x and 0x7F).toInt()
                x = x ushr 7
                o.write(b or if (x != 0L) 0x80 else 0)
                if (x == 0L) return o.toByteArray()
            }
        }

        fun pb(num: Int, v: Any): ByteArray = when (v) {
            is Int -> varint((num shl 3).toLong()) + varint(v.toLong())
            is String -> pb(num, v.toByteArray())
            is ByteArray -> varint((num shl 3 or 2).toLong()) + varint(v.size.toLong()) + v
            is Float -> varint((num shl 3 or 5).toLong()) + ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putFloat(v).array()
            else -> error("type")
        }

        fun fixed32(num: Int, v: Int) = varint((num shl 3 or 5).toLong()) + le(v)

        /** A Meshtastic packet on the public default channel (encrypted with the key everyone has). */
        fun mesh(from: Int, id: Int, port: Int, payload: ByteArray, key: Boolean = true): ByteArray {
            val plain = pb(1, port) + pb(2, payload)
            val nonce = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(id.toLong() and 0xFFFFFFFFL).putInt(from).putInt(0).array()
            val body = if (key) Meshtastic.aesCtr(hexKey, nonce, plain) else plain
            return le(-1, from, id) + byteArrayOf((3 or (3 shl 5)).toByte(), 8, 0, (from and 0xFF).toByte()) + body
        }

        val hexKey = "d4f1bb3a20290759f0bcffabcf4e6901".chunked(2).map { it.toInt(16).toByte() }.toByteArray()

        val MESHTASTIC = listOf(
            mesh(0x1A2B3C4D, 101, 4, pb(1, "!1a2b3c4d") + pb(2, "Garden node") + pb(3, "GRD") + pb(5, 43) + pb(7, 0)),
            mesh(0x1A2B3C4D, 102, 1, "Anyone on the mesh tonight?".toByteArray()),
            mesh(0x0C0FFEE0, 103, 4, pb(1, "!0c0ffee0") + pb(2, "Rooftop relay") + pb(3, "RTR") + pb(5, 9) + pb(7, 2)),
            mesh(0x0C0FFEE0, 104, 67, pb(1, 1700000000) + pb(2, pb(1, 91) + pb(2, 4.12f) + pb(3, 7.5f) + pb(5, 86400))),
            mesh(0x5EED1234, 105, 3, fixed32(1, 521000000) + fixed32(2, 49000000) + pb(3, 4) + pb(19, 9)),
            // a private channel: header readable, contents not
            le(-1, 0x7777AAAA, 106) + byteArrayOf((3 or (3 shl 5)).toByte(), 0x5A, 0, 0xAA.toByte()) + ByteArray(24) { (it * 37 + 11).toByte() },
        )

        fun advert(name: String, type: Int, key: Int): ByteArray {
            val flags = type or 0x10 or 0x80
            val body = ByteArray(32) { (key + it).toByte() } + le(1_700_000_000) + ByteArray(64) + byteArrayOf(flags.toByte()) + ByteArray(8) + name.toByteArray()
            return byteArrayOf(((4 shl 2) or 1).toByte(), 0) + body
        }

        val MESHCORE = listOf(
            advert("Harbour Repeater", 2, 0x40),
            advert("Kitchen companion", 1, 0x90),
            byteArrayOf(((2 shl 2) or 1).toByte(), 2, 0x10, 0x20, 0xA1.toByte(), 0x4F, 0xBE.toByte(), 0xEF.toByte()) + ByteArray(32),
        )

        fun uplink(addr: Int, fcnt: Int) = byteArrayOf(0x40) + le(addr) + byteArrayOf(0x80.toByte(), (fcnt and 0xFF).toByte(), (fcnt shr 8).toByte(), 1) +
            byteArrayOf(1, 2, 3, 4, 5) + byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte())

        val LORAWAN = listOf(
            uplink(0x26011BDA, 1234),
            byteArrayOf(0) + "70b3d57ed0000000".chunked(2).reversed().map { it.toInt(16).toByte() } +
                "0004a30b001a2b3c".chunked(2).reversed().map { it.toInt(16).toByte() } + byteArrayOf(0x12, 0x34, 0, 0x11, 0x22, 0x33),
            uplink(0x260B77F1, 88),
        )

        operator fun ByteArray.plus(other: List<Byte>) = this + other.toByteArray()
    }
}
