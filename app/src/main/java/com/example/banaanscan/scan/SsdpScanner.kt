package com.example.banaanscan.scan

import com.example.banaanscan.data.SsdpInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL

/** UPnP / SSDP discovery: M-SEARCH, then fetch each device's description XML. */
object SsdpScanner {

    private const val SEARCH = "M-SEARCH * HTTP/1.1\r\n" +
        "HOST: 239.255.255.250:1900\r\n" +
        "MAN: \"ssdp:discover\"\r\n" +
        "MX: 2\r\n" +
        "ST: ssdp:all\r\n\r\n"

    suspend fun discover(durationMs: Long, onUpdate: (String, SsdpInfo) -> Unit) = withContext(Dispatchers.IO) {
        val found = HashMap<String, SsdpInfo>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 250
            val msg = SEARCH.toByteArray()
            val group = InetAddress.getByName("239.255.255.250")
            repeat(2) { runCatching { socket.send(DatagramPacket(msg, msg.size, group, 1900)) } }

            val end = System.currentTimeMillis() + durationMs
            val buf = ByteArray(4096)
            while (isActive && System.currentTimeMillis() < end) {
                val packet = DatagramPacket(buf, buf.size)
                try {
                    socket.receive(packet)
                } catch (e: SocketTimeoutException) {
                    continue
                }
                val ip = packet.address?.hostAddress?.takeIf { it.contains('.') } ?: continue
                val headers = String(packet.data, 0, packet.length).lines().drop(1).mapNotNull { line ->
                    val i = line.indexOf(':')
                    if (i <= 0) null else line.substring(0, i).trim().lowercase() to line.substring(i + 1).trim()
                }.toMap()
                val prev = found[ip] ?: SsdpInfo()
                val merged = prev.copy(
                    server = prev.server ?: headers["server"],
                    location = prev.location ?: headers["location"],
                    searchTargets = prev.searchTargets + listOfNotNull(headers["st"] ?: headers["nt"]),
                )
                found[ip] = merged
                onUpdate(ip, merged)
            }
        }
        coroutineScope {
            found.map { (ip, info) -> async { ip to describe(ip, info) } }.awaitAll()
        }.forEach { (ip, info) -> if (info != null) onUpdate(ip, info) }
    }

    private fun describe(ip: String, info: SsdpInfo): SsdpInfo? {
        val url = info.location?.let { runCatching { URL(it) }.getOrNull() } ?: return null
        // Only fetch descriptions served by the device that answered.
        if (url.protocol != "http" || url.host != ip) return null
        return runCatching {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 1500
            conn.readTimeout = 1500
            try {
                val xml = conn.inputStream.use { Probes.readLimited(it, 64 * 1024) }
                fun tag(name: String) = Regex("<$name>\\s*([^<]{1,200}?)\\s*</$name>", RegexOption.IGNORE_CASE)
                    .find(xml)?.groupValues?.get(1)?.trim()
                info.copy(
                    friendlyName = tag("friendlyName"),
                    manufacturer = tag("manufacturer"),
                    modelName = listOfNotNull(tag("modelName"), tag("modelNumber")).distinct().joinToString(" ").ifBlank { null },
                    deviceType = tag("deviceType"),
                )
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }
}
