package com.example.banaanscan.scan

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

/**
 * Minimal mDNS / DNS-SD browser. Queries are sent from an ephemeral port, so responders
 * answer by unicast ("legacy unicast") and the packet's source address tells us which
 * host owns each service — no need to bind port 5353 or resolve every instance.
 */
object MdnsScanner {

    data class HostInfo(
        val ip: String,
        val services: Set<String>,
        val instances: Set<String>,
        val hostnames: Set<String>,
        val txt: Map<String, String>,
    )

    private val SERVICE_TYPES = listOf(
        "_googlecast._tcp", "_airplay._tcp", "_raop._tcp", "_companion-link._tcp", "_apple-mobdev2._tcp",
        "_device-info._tcp", "_mediaremotetv._tcp", "_sleep-proxy._udp", "_ipp._tcp", "_ipps._tcp",
        "_printer._tcp", "_pdl-datastream._tcp", "_scanner._tcp", "_uscan._tcp", "_hap._tcp", "_hap._udp",
        "_matter._tcp", "_matterc._udp", "_hue._tcp", "_esphomelib._tcp", "_wled._tcp", "_shelly._tcp",
        "_spotify-connect._tcp", "_sonos._tcp", "_amzn-wplay._tcp", "_androidtvremote2._tcp",
        "_nvstream._tcp", "_rdp._tcp", "_rfb._tcp", "_smb._tcp", "_afpovertcp._tcp", "_adisk._tcp",
        "_ssh._tcp", "_sftp-ssh._tcp", "_workstation._tcp", "_http._tcp", "_home-assistant._tcp",
        "_plexmediasvr._tcp", "_miio._udp", "_meshcop._udp", "_adb-tls-connect._tcp",
    )

    private const val TYPE_A = 1
    private const val TYPE_PTR = 12
    private const val TYPE_TXT = 16
    private const val TYPE_SRV = 33
    private const val META_QUERY = "_services._dns-sd._udp.local"

    private class Record(
        val name: List<String>,
        val type: Int,
        val ptr: List<String> = emptyList(),
        val srvTarget: String? = null,
        val txt: List<String> = emptyList(),
        val a: String? = null,
    )

    private class Builder(val ip: String) {
        val services = LinkedHashSet<String>()
        val instances = LinkedHashSet<String>()
        val hostnames = LinkedHashSet<String>()
        val txt = LinkedHashMap<String, String>()
        fun snapshot() = HostInfo(ip, services.toSet(), instances.toSet(), hostnames.toSet(), txt.toMap())
    }

    suspend fun discover(durationMs: Long, onUpdate: (HostInfo) -> Unit) = withContext(Dispatchers.IO) {
        val hosts = HashMap<String, Builder>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val group = InetAddress.getByName("224.0.0.251")
            val asked = HashSet<String>()
            fun ask(name: String) {
                if (!asked.add(name.lowercase())) return
                val q = query(name)
                runCatching { socket.send(DatagramPacket(q, q.size, group, 5353)) }
            }
            ask(META_QUERY)
            SERVICE_TYPES.forEach { ask("$it.local") }

            val end = System.currentTimeMillis() + durationMs
            val buf = ByteArray(9000)
            while (isActive && System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val sender = packet.address?.hostAddress?.takeIf { it.contains('.') } ?: continue
                val records = runCatching { parse(packet.data, packet.length) }.getOrNull() ?: continue
                val host = hosts.getOrPut(sender) { Builder(sender) }
                for (r in records) {
                    val name = r.name.joinToString(".")
                    when (r.type) {
                        TYPE_PTR -> when {
                            name.equals(META_QUERY, ignoreCase = true) -> {
                                val type = r.ptr.joinToString(".")
                                host.services += type.removeSuffix(".local")
                                ask(type)
                            }
                            name.endsWith("in-addr.arpa", ignoreCase = true) ->
                                host.hostnames += r.ptr.joinToString(".").removeSuffix(".local")
                            r.name.firstOrNull()?.startsWith("_") == true -> {
                                host.services += name.removeSuffix(".local")
                                r.ptr.firstOrNull()?.let { host.instances += it }
                            }
                        }
                        TYPE_SRV -> r.srvTarget?.let { host.hostnames += it.removeSuffix(".local") }
                        TYPE_A -> if (r.a == sender) host.hostnames += name.removeSuffix(".local")
                        TYPE_TXT -> r.txt.forEach { kv ->
                            val key = kv.substringBefore('=', "").lowercase()
                            val value = kv.substringAfter('=', "")
                            if (key.isNotEmpty() && key.length <= 20 && value.isNotEmpty() &&
                                value.length <= 100 && host.txt.size < 40 && key !in host.txt
                            ) host.txt[key] = value
                        }
                    }
                }
                // Instance names of the _device-info record are just the host's name.
                host.services.remove("_device-info._tcp")
                onUpdate(host.snapshot())
            }
        }
    }

    private fun query(name: String): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        for (label in name.split('.')) {
            val bytes = label.toByteArray(Charsets.UTF_8)
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(byteArrayOf(0, TYPE_PTR.toByte(), 0, 1))
        return out.toByteArray()
    }

    private class Reader(val b: ByteArray, val len: Int) {
        var pos = 0

        fun u8(): Int {
            if (pos >= len) throw IndexOutOfBoundsException()
            return b[pos++].toInt() and 0xFF
        }

        fun u16(): Int = (u8() shl 8) or u8()

        fun name(): List<String> {
            val labels = ArrayList<String>()
            var p = pos
            var jumped = false
            var hops = 0
            while (true) {
                if (p >= len) throw IndexOutOfBoundsException()
                val l = b[p].toInt() and 0xFF
                when {
                    l == 0 -> { p++; break }
                    (l and 0xC0) == 0xC0 -> {
                        if (p + 1 >= len || ++hops > 64) throw IllegalStateException("bad pointer")
                        val target = ((l and 0x3F) shl 8) or (b[p + 1].toInt() and 0xFF)
                        if (!jumped) pos = p + 2
                        jumped = true
                        p = target
                    }
                    else -> {
                        if (p + 1 + l > len) throw IndexOutOfBoundsException()
                        labels += String(b, p + 1, l, Charsets.UTF_8)
                        p += l + 1
                    }
                }
            }
            if (!jumped) pos = p
            return labels
        }
    }

    private fun parse(b: ByteArray, len: Int): List<Record> {
        val r = Reader(b, len)
        r.pos = 4
        val qd = r.u16()
        val an = r.u16()
        val ns = r.u16()
        val ar = r.u16()
        repeat(qd) { r.name(); r.pos += 4 }
        val out = ArrayList<Record>()
        repeat(an + ns + ar) {
            val name = r.name()
            val type = r.u16()
            r.u16() // class
            r.pos += 4 // ttl
            val rdlen = r.u16()
            val start = r.pos
            if (start + rdlen > len) throw IndexOutOfBoundsException()
            val rec = when (type) {
                TYPE_PTR -> Record(name, type, ptr = r.name())
                TYPE_SRV -> {
                    r.pos += 6 // priority, weight, port
                    Record(name, type, srvTarget = r.name().joinToString("."))
                }
                TYPE_TXT -> {
                    val items = ArrayList<String>()
                    var p = start
                    while (p < start + rdlen) {
                        val l = b[p].toInt() and 0xFF
                        p++
                        if (l > 0) items += String(b, p, minOf(l, start + rdlen - p), Charsets.UTF_8)
                        p += l
                    }
                    Record(name, type, txt = items)
                }
                TYPE_A -> if (rdlen == 4) {
                    Record(name, type, a = (0 until 4).joinToString(".") { (b[start + it].toInt() and 0xFF).toString() })
                } else null
                else -> null
            }
            r.pos = start + rdlen
            rec?.let(out::add)
        }
        return out
    }
}
