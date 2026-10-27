package com.example.banaanscan.scan

import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import kotlin.random.Random

/**
 * NetBIOS node-status (NBSTAT) query on UDP 137. Windows PCs, Samba servers and many
 * NAS boxes answer with their computer name, workgroup and — usefully, since Android
 * hides the ARP table — their MAC address.
 */
object NetBios {
    data class Info(val name: String?, val group: String?, val mac: String?)

    fun query(ip: String, timeoutMs: Int = 800): Info? = try {
        DatagramSocket().use { socket ->
            socket.soTimeout = timeoutMs
            val req = request()
            socket.send(DatagramPacket(req, req.size, InetAddress.getByName(ip), 137))
            val buf = ByteArray(1024)
            val packet = DatagramPacket(buf, buf.size)
            socket.receive(packet)
            parse(buf, packet.length)
        }
    } catch (e: Exception) {
        null
    }

    private fun request(): ByteArray {
        val out = ByteArrayOutputStream()
        val id = Random.nextInt(0x10000)
        out.write(id shr 8)
        out.write(id and 0xFF)
        out.write(byteArrayOf(0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        // Name "*" padded to 16 bytes, first-level encoded (each nibble + 'A').
        out.write(0x20)
        val name = ByteArray(16).also { it[0] = '*'.code.toByte() }
        for (b in name) {
            val v = b.toInt() and 0xFF
            out.write('A'.code + (v shr 4))
            out.write('A'.code + (v and 0x0F))
        }
        out.write(0)
        out.write(byteArrayOf(0, 0x21, 0, 1)) // type NBSTAT, class IN
        return out.toByteArray()
    }

    private fun parse(b: ByteArray, len: Int): Info? {
        var off = 12
        if (off >= len) return null
        off += if ((b[off].toInt() and 0xC0) == 0xC0) {
            2
        } else {
            var o = off
            while (o < len && b[o].toInt() != 0) o += (b[o].toInt() and 0xFF) + 1
            o + 1 - off
        }
        off += 10 // type, class, ttl, rdlength
        if (off >= len) return null
        val count = b[off].toInt() and 0xFF
        off++
        var name: String? = null
        var group: String? = null
        repeat(count) {
            if (off + 18 > len) return@repeat
            val n = String(b, off, 15, Charsets.US_ASCII).trim()
            val suffix = b[off + 15].toInt() and 0xFF
            val flags = ((b[off + 16].toInt() and 0xFF) shl 8) or (b[off + 17].toInt() and 0xFF)
            val isGroup = (flags and 0x8000) != 0
            if (suffix == 0x00) {
                if (isGroup) { if (group == null) group = n } else if (name == null) name = n
            }
            off += 18
        }
        val mac = if (off + 6 <= len) {
            (0 until 6).joinToString(":") { "%02X".format(b[off + it].toInt() and 0xFF) }
                .takeIf { it != "00:00:00:00:00:00" }
        } else null
        if (name == null && mac == null) return null
        return Info(name, group, mac)
    }
}
