package com.example.banaanscan.scan

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.TimeUnit

enum class PortState { OPEN, CLOSED, FILTERED }

object Probes {

    /** Ports probed on every host, with a human-readable label. */
    val COMMON_PORTS: Map<Int, String> = linkedMapOf(
        21 to "FTP", 22 to "SSH", 23 to "Telnet", 53 to "DNS", 80 to "HTTP", 139 to "NetBIOS",
        443 to "HTTPS", 445 to "SMB", 515 to "LPD printing", 548 to "AFP", 554 to "RTSP (camera)",
        631 to "IPP printing", 1400 to "Sonos", 1883 to "MQTT", 3389 to "RDP", 5000 to "UPnP / DSM",
        5001 to "DSM HTTPS", 5555 to "ADB", 5900 to "VNC", 6466 to "Android TV remote",
        6467 to "Android TV pairing", 7000 to "AirPlay", 8000 to "HTTP alt", 8008 to "Chromecast",
        8009 to "Chromecast", 8080 to "HTTP proxy/alt", 8123 to "Home Assistant", 8443 to "HTTPS alt",
        8883 to "MQTT TLS", 9100 to "JetDirect printing", 32400 to "Plex", 62078 to "iOS lockdown",
    )

    /** Small set used for the fast liveness sweep. */
    val SWEEP_PORTS = listOf(80, 443, 22, 445, 53, 62078, 8080, 7000)

    val HTTP_PORTS = listOf(80, 8080, 8000)

    /**
     * TCP connect probe. A refused connection still proves the host exists, which is what
     * makes this work as a ping replacement on non-rooted Android.
     */
    fun tcp(addr: InetAddress, port: Int, timeoutMs: Int): PortState = try {
        Socket().use { it.connect(InetSocketAddress(addr, port), timeoutMs) }
        PortState.OPEN
    } catch (e: ConnectException) {
        val msg = e.message.orEmpty()
        if (msg.contains("ECONNREFUSED") || msg.contains("refused", ignoreCase = true)) PortState.CLOSED else PortState.FILTERED
    } catch (e: IOException) {
        PortState.FILTERED
    }

    /** Returns (Server header, page title) of the device's web interface. */
    fun httpBanner(ip: String, port: Int): Pair<String?, String?>? = runCatching {
        val conn = URL("http://$ip:$port/").openConnection() as HttpURLConnection
        conn.connectTimeout = 1500
        conn.readTimeout = 1500
        conn.instanceFollowRedirects = false
        conn.setRequestProperty("User-Agent", "BanaanScan/0.1")
        try {
            val server = conn.getHeaderField("Server")
            val stream = if (conn.responseCode < 400) conn.inputStream else conn.errorStream
            val body = stream?.use { readLimited(it, 32 * 1024) }.orEmpty()
            val title = Regex("<title[^>]*>([^<]{1,120})</title>", RegexOption.IGNORE_CASE)
                .find(body)?.groupValues?.get(1)?.trim()
            if (server == null && title == null) null else server to title
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    fun readLimited(input: InputStream, limit: Int): String {
        val buf = ByteArray(limit)
        var total = 0
        while (total < limit) {
            val n = input.read(buf, total, limit - total)
            if (n <= 0) break
            total += n
        }
        return String(buf, 0, total, Charsets.UTF_8)
    }

    /**
     * IP -> MAC from the kernel neighbour table. Android 10+ blocks both sources for
     * normal apps, so this usually returns nothing on modern phones.
     */
    fun readArp(): Map<String, String> {
        val out = HashMap<String, String>()
        runCatching {
            File("/proc/net/arp").readLines().drop(1).forEach { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size >= 4 && parts[3] != "00:00:00:00:00:00") out[parts[0]] = parts[3].uppercase()
            }
        }
        if (out.isEmpty()) runCatching {
            val p = ProcessBuilder("ip", "neigh", "show").redirectErrorStream(true).start()
            if (p.waitFor(2, TimeUnit.SECONDS)) {
                p.inputStream.bufferedReader().readLines().forEach { line ->
                    val parts = line.trim().split(Regex("\\s+"))
                    val idx = parts.indexOf("lladdr")
                    if (idx > 0 && idx + 1 < parts.size && parts[0].contains('.') &&
                        parts.none { it == "FAILED" || it == "INCOMPLETE" }
                    ) out[parts[0]] = parts[idx + 1].uppercase()
                }
            } else {
                p.destroy()
            }
        }
        return out
    }
}
