package com.example.banaanscan.scan

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import java.net.Inet4Address

data class LocalNetwork(
    val ip: String,
    val prefix: Int,
    val gateway: String?,
    val dns: List<String>,
    val iface: String?,
    val ssid: String?,
    val bssid: String?,
    val transport: String,
) {
    val cidr: String
        get() {
            val mask = NetworkInfo.maskOf(prefix)
            return "${NetworkInfo.longToIp(NetworkInfo.ipToLong(ip) and mask)}/$prefix"
        }
}

object NetworkInfo {
    /** Never sweep more than a /22 (1022 hosts), even on larger subnets. */
    const val MIN_PREFIX = 22

    fun current(context: Context): LocalNetwork? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val candidates = buildList<Network> {
            cm.activeNetwork?.let(::add)
            @Suppress("DEPRECATION")
            addAll(cm.allNetworks)
        }
        val net = candidates.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
            } == true
        } ?: return null
        val caps = cm.getNetworkCapabilities(net) ?: return null
        val lp = cm.getLinkProperties(net) ?: return null
        val link = lp.linkAddresses.firstOrNull { it.address is Inet4Address } ?: return null
        val gateway = lp.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }?.gateway?.hostAddress
        val isWifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)

        var ssid: String? = null
        var bssid: String? = null
        if (isWifi) {
            val wm = context.applicationContext.getSystemService(WifiManager::class.java)
            @Suppress("DEPRECATION")
            val info = runCatching { wm?.connectionInfo }.getOrNull()
            ssid = info?.ssid?.removeSurrounding("\"")?.takeUnless { it == "<unknown ssid>" }
            bssid = info?.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        }
        return LocalNetwork(
            ip = link.address.hostAddress ?: return null,
            prefix = link.prefixLength,
            gateway = gateway,
            dns = lp.dnsServers.mapNotNull { it.hostAddress },
            iface = lp.interfaceName,
            ssid = ssid,
            bssid = bssid,
            transport = if (isWifi) "Wi-Fi" else "Ethernet",
        )
    }

    fun hostsToScan(net: LocalNetwork): List<String> {
        val prefix = net.prefix.coerceIn(MIN_PREFIX, 30)
        val mask = maskOf(prefix)
        val base = ipToLong(net.ip) and mask
        val count = 1L shl (32 - prefix)
        return (1L until count - 1).map { longToIp(base + it) }.filter { it != net.ip }
    }

    fun isInSubnet(ip: String, net: LocalNetwork): Boolean {
        if (!ip.contains('.')) return false
        val mask = maskOf(net.prefix.coerceAtLeast(MIN_PREFIX))
        return (ipToLong(ip) and mask) == (ipToLong(net.ip) and mask)
    }

    fun maskOf(prefix: Int): Long = (0xFFFFFFFFL shl (32 - prefix)) and 0xFFFFFFFFL

    fun ipToLong(ip: String): Long =
        ip.split('.').fold(0L) { acc, part -> (acc shl 8) or (part.toLongOrNull() ?: 0L) }

    fun longToIp(v: Long): String = "${(v shr 24) and 255}.${(v shr 16) and 255}.${(v shr 8) and 255}.${v and 255}"
}
