package io.github.banaanga.sniffowireless.mobile.scan

import android.os.SystemClock
import io.github.banaanga.sniffowireless.mobile.core.AttrGates
import io.github.banaanga.sniffowireless.mobile.core.Elt
import io.github.banaanga.sniffowireless.mobile.core.Gate
import io.github.banaanga.sniffowireless.mobile.core.WifiFrames
import io.github.banaanga.sniffowireless.mobile.sensor.Hub

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import io.github.banaanga.sniffowireless.mobile.classify.DeviceClassifier
import io.github.banaanga.sniffowireless.mobile.data.OuiDatabase
import io.github.banaanga.sniffowireless.mobile.data.WifiNetwork
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@SuppressLint("MissingPermission")
class WifiScanner(context: Context, private val hub: Hub? = null) {
    private val app = context.applicationContext
    private val wifi: WifiManager? = app.getSystemService(WifiManager::class.java)
    private val fpGate = Gate()
    private val gates = AttrGates()
    /** Newest scan result already sent to the server (ScanResult.timestamp, µs since boot). */
    private var lastSent = 0L
    private var sentCount = 0L

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
        hub?.takeIf { it.sending }?.let { sendToServer(it, raw) }
    }

    /** sniffowireless "wifi_ap" observations for scan results the server hasn't had yet. */
    private fun sendToServer(hub: Hub, raw: List<ScanResult>) {
        val bootUs = SystemClock.elapsedRealtimeNanos() / 1000
        val nowS = hub.now()
        var newest = lastSent
        for (r in raw) {
            if (r.timestamp <= lastSent) continue
            newest = maxOf(newest, r.timestamp)
            val ts = nowS - (bootUs - r.timestamp) / 1e6
            val elts = if (Build.VERSION.SDK_INT >= 30) {
                r.informationElements.map { ie ->
                    val body = ByteArray(ie.bytes.remaining()).also { ie.bytes.duplicate().get(it) }
                    // Android splits element 255's extension id off its body; sniffowireless reads them together.
                    if (ie.id == 255) Elt(255, byteArrayOf(ie.idExt.toByte()) + body) else Elt(ie.id, body)
                }
            } else null
            @Suppress("DEPRECATION")
            WifiFrames.phoneAp(r.BSSID.orEmpty(), r.SSID.orEmpty().removeSurrounding("\""), r.level, r.frequency, elts,
                r.capabilities.orEmpty(), ts, fpGate, gates)?.let {
                hub.sink.put(it)
                sentCount++
            }
        }
        lastSent = newest
        hub.status.update(STATUS, "packets" to sentCount, "state" to "scanning")
    }

    /** Sensor mode: report this source on the server's Sensors page, and scan every [intervalMs] while it runs. */
    suspend fun runForSensor(intervalMs: Long = 30_000) {
        val h = hub ?: return
        h.status.register(STATUS, "type" to "wifi", "iface" to "phone", "state" to "scanning", "hw_driver" to "Android Wi-Fi scan",
            "note" to "The phone's own Wi-Fi scan: access points only, no clients (that needs a USB adapter in monitor mode)")
        lastSent = SystemClock.elapsedRealtimeNanos() / 1000 - 60_000_000 // results up to a minute old count
        try {
            while (true) {
                scan()
                if (status.value?.startsWith("Scan request rejected") == true) {
                    h.status.update(STATUS, "warning" to "Android throttles Wi-Fi scans; turn off Developer options → Wi-Fi scan throttling for more")
                }
                kotlinx.coroutines.delay(intervalMs)
            }
        } finally {
            h.status.remove(STATUS)
        }
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
        const val STATUS = "wifi:phone"

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
