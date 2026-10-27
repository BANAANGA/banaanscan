package com.example.banaanscan.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.banaanscan.classify.DeviceClassifier
import com.example.banaanscan.data.OuiDatabase
import com.example.banaanscan.data.WifiNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class WifiScanner(context: Context) {
    private val app = context.applicationContext
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)

    private val _results = MutableStateFlow<List<WifiNetwork>>(emptyList())
    val results: StateFlow<List<WifiNetwork>> = _results.asStateFlow()
    val status = MutableStateFlow<String?>(null)
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            val fresh = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)
            status.value = if (fresh) null else "Android returned cached results (scan throttled)"
            publish()
        }
    }

    fun start() {
        if (!registered) {
            ContextCompat.registerReceiver(
                app, receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            registered = true
        }
        publish()
    }

    fun scan() {
        start()
        if (wifi == null) {
            status.value = "This device has no Wi-Fi"
            return
        }
        if (!wifi.isWifiEnabled) {
            status.value = "Wi-Fi is off – turn it on to scan"
            return
        }
        @Suppress("DEPRECATION")
        val accepted = try { wifi.startScan() } catch (e: SecurityException) { false }
        status.value = if (accepted) "Scanning…"
        else "Scan request rejected – Android allows 4 scans per 2 minutes. Showing last results."
    }

    fun stop() {
        if (registered) {
            runCatching { app.unregisterReceiver(receiver) }
            registered = false
        }
    }

    @Suppress("DEPRECATION")
    private fun publish() {
        val raw = try {
            wifi?.scanResults.orEmpty()
        } catch (e: SecurityException) {
            status.value = "Location / nearby-devices permission is required"
            emptyList()
        }
        val connected = runCatching { wifi?.connectionInfo?.bssid }.getOrNull()
        _results.value = raw.map { r -> toNetwork(r, connected) }.sortedByDescending { it.rssi }
    }

    @Suppress("DEPRECATION")
    private fun toNetwork(r: ScanResult, connectedBssid: String?): WifiNetwork {
        val ssid = r.SSID.orEmpty().removeSurrounding("\"")
        val bssid = r.BSSID.orEmpty().uppercase()
        val random = OuiDatabase.isLocallyAdministered(bssid)
        val vendor = OuiDatabase.lookup(bssid)
        val standard = if (Build.VERSION.SDK_INT >= 30) standardName(r.wifiStandard, r.frequency) else null
        return WifiNetwork(
            ssid = ssid,
            bssid = bssid,
            rssi = r.level,
            frequency = r.frequency,
            channel = channelOf(r.frequency),
            band = bandOf(r.frequency),
            security = securityOf(r.capabilities.orEmpty()),
            standard = standard,
            channelWidthMhz = widthOf(r.channelWidth),
            vendor = vendor,
            randomizedBssid = random,
            isConnected = connectedBssid != null && connectedBssid.equals(bssid, ignoreCase = true),
            guess = DeviceClassifier.classifyAp(ssid, vendor, random),
        )
    }

    companion object {
        fun channelOf(freq: Int): Int = when {
            freq == 2484 -> 14
            freq in 2412..2472 -> (freq - 2407) / 5
            freq in 5000..5895 -> (freq - 5000) / 5
            freq in 5925..7125 -> (freq - 5950) / 5
            else -> 0
        }

        fun bandOf(freq: Int): String = when {
            freq < 3000 -> "2.4 GHz"
            freq < 5925 -> "5 GHz"
            freq < 7200 -> "6 GHz"
            else -> "60 GHz"
        }

        fun securityOf(caps: String): String = when {
            caps.contains("SAE") && caps.contains("PSK") -> "WPA2/WPA3"
            caps.contains("SAE") -> "WPA3"
            caps.contains("EAP") -> "Enterprise"
            caps.contains("RSN") || caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            caps.contains("OWE") -> "Enhanced Open"
            else -> "Open"
        }

        private fun widthOf(code: Int): Int? = when (code) {
            0 -> 20; 1 -> 40; 2 -> 80; 3 -> 160; 4 -> 160; 5 -> 320
            else -> null
        }

        private fun standardName(std: Int, freq: Int): String? = when (std) {
            1 -> "802.11a/b/g"
            4 -> "Wi-Fi 4 (n)"
            5 -> "Wi-Fi 5 (ac)"
            6 -> if (freq >= 5925) "Wi-Fi 6E (ax)" else "Wi-Fi 6 (ax)"
            7 -> "802.11ad"
            8 -> "Wi-Fi 7 (be)"
            else -> null
        }
    }
}
