package io.github.banaanga.sniffowireless.mobile.sensor

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.os.Build
import android.os.Debug
import android.os.Process
import android.os.StatFs
import android.os.SystemClock
import android.util.Log
import io.github.banaanga.sniffowireless.mobile.BuildConfig
import io.github.banaanga.sniffowireless.mobile.btusb.UsbBluetoothScanner
import io.github.banaanga.sniffowireless.mobile.core.Forwarder
import io.github.banaanga.sniffowireless.mobile.core.LogBuffer
import io.github.banaanga.sniffowireless.mobile.core.Sink
import io.github.banaanga.sniffowireless.mobile.core.StatusBoard
import io.github.banaanga.sniffowireless.mobile.core.Target
import io.github.banaanga.sniffowireless.mobile.core.WifiControl
import io.github.banaanga.sniffowireless.mobile.core.ZigbeeControl
import io.github.banaanga.sniffowireless.mobile.data.DeviceIndex
import io.github.banaanga.sniffowireless.mobile.scan.BluetoothScanner
import io.github.banaanga.sniffowireless.mobile.scan.LanScanner
import io.github.banaanga.sniffowireless.mobile.scan.WifiScanner
import io.github.banaanga.sniffowireless.mobile.wifimon.UsbWifiMonitor
import io.github.banaanga.sniffowireless.mobile.zigbee.ZigbeeScanner
import kotlinx.coroutines.flow.MutableStateFlow

class SniffoApplication : Application() {
    val hub: Hub by lazy { Hub(this) }
}

val Context.hub: Hub get() = (applicationContext as SniffoApplication).hub

/** What sensor mode sends, and where to: kept in SharedPreferences. */
class SensorSettings(context: Context) {
    private val p = context.getSharedPreferences("sensor", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = p.getString("server_url", "") ?: ""
        set(v) = p.edit().putString("server_url", v.trim()).apply()
    var token: String
        get() = p.getString("token", "") ?: ""
        set(v) = p.edit().putString("token", v.trim()).apply()
    var phoneWifi: Boolean
        get() = p.getBoolean("phone_wifi", true)
        set(v) = p.edit().putBoolean("phone_wifi", v).apply()
    var phoneBt: Boolean
        get() = p.getBoolean("phone_bt", true)
        set(v) = p.edit().putBoolean("phone_bt", v).apply()
    var usbWifi: Boolean
        get() = p.getBoolean("usb_wifi", true)
        set(v) = p.edit().putBoolean("usb_wifi", v).apply()
    var usbBt: Boolean
        get() = p.getBoolean("usb_bt", true)
        set(v) = p.edit().putBoolean("usb_bt", v).apply()
    var zigbee: Boolean
        get() = p.getBoolean("zigbee", true)
        set(v) = p.edit().putBoolean("zigbee", v).apply()
    /** USB Bluetooth: listen only (no scan requests, no classic inquiry). */
    var btPassive: Boolean
        get() = p.getBoolean("bt_passive", false)
        set(v) = p.edit().putBoolean("bt_passive", v).apply()
    /**
     * The MT7921U's channels when the server sets none: a sniffowireless channel list ("default", "all", "1,6,11").
     * Starts as [DEFAULT_WIFI_CHANNELS], which adds the 6 GHz preferred channels to sniffowireless's "default". Up to
     * 1.0.0 the app saved plain "default" on every start, so that value counts as never chosen and moves to the new one.
     */
    var wifiChannels: String
        get() {
            val v = p.getString("wifi_channels", null)
            return if (v == null || (v == "default" && !p.getBoolean("wifi_channels_chosen", false))) DEFAULT_WIFI_CHANNELS else v
        }
        set(v) = p.edit().putString("wifi_channels", v.trim()).putBoolean("wifi_channels_chosen", true).apply()
    /** Was sensor mode on when the app last ran (restarted after a reboot or a crash). */
    var enabled: Boolean
        get() = p.getBoolean("enabled", false)
        set(v) = p.edit().putBoolean("enabled", v).apply()

    val target: Target? get() = if (serverUrl.isNotBlank() && token.isNotBlank()) Target(serverUrl, token) else null

    companion object {
        const val DEFAULT_WIFI_CHANNELS = "default,6ghz"
        /**
         * The server address and token from what sniffowireless's Settings → Sensors → Add remote sensor shows: its docker
         * command (with -e SERVER_URL=… -e SENSOR_TOKEN=…), a URL, a token, or both on separate lines.
         */
        fun parsePaste(text: String): Pair<String?, String?> {
            val url = Regex("""SERVER_URL=["']?(\S+?)["']?(\s|\\|$)""").find(text)?.groupValues?.get(1)
                ?: Regex("""https?://[^\s"'\\]+""").find(text)?.value
            val token = Regex("""SENSOR_TOKEN=["']?(\S+?)["']?(\s|\\|$)""").find(text)?.groupValues?.get(1)
                ?: Regex("""sniffowireless_[A-Za-z0-9_\-]+""").find(text)?.value
            return url?.trimEnd('/') to token
        }
    }
}

/**
 * Everything the screens and the sensor service share: the scanners, and what sensor mode needs to act like one of
 * sniffowireless's remote sensors (status entries, log lines, the forwarder and the settings the server pushes).
 */
class Hub(private val app: Application) {
    val settings = SensorSettings(app)
    val status = StatusBoard()
    val logs = LogBuffer()
    val wifiControl = WifiControl(UsbWifiMonitor.IFACE) { level, msg -> log(level, "sniffowireless.channels", msg) }
    val zigbeeControl = ZigbeeControl { level, msg -> log(level, "sniffowireless.zigbee", msg) }
    val forwarder = Forwarder(null, status, logs, version(), system = ::system)

    /** True while sensor mode runs: observations go to the forwarder only then. */
    @Volatile var sending = false
        private set
    val sensorRunning = MutableStateFlow(false)
    val sink = Sink { if (sending) forwarder.put(it) }

    val wifi = WifiScanner(app, this)
    val bt = BluetoothScanner(app, this)
    val lan = LanScanner(app)
    val zigbee = ZigbeeScanner(app, this)
    val wifiMonitor = UsbWifiMonitor(app, this)
    val btUsb = UsbBluetoothScanner(app, this)
    val index = DeviceIndex(app)

    init {
        forwarder.onChannelConfig = wifiControl::applyRemote
        forwarder.onZigbeeConfig = zigbeeControl::applyRemote
    }

    fun setSending(on: Boolean) {
        sending = on
        sensorRunning.value = on
        forwarder.target = if (on) settings.target else null
    }

    fun log(level: String, logger: String, msg: String) {
        logs.add(level, logger, msg)
        Log.println(when (level) { "ERROR" -> Log.ERROR; "WARNING" -> Log.WARN; "DEBUG" -> Log.DEBUG; else -> Log.INFO }, "sniffo", "$logger: $msg")
    }

    fun now(): Double = System.currentTimeMillis() / 1000.0

    private fun version(): Map<String, Any?> = linkedMapOf(
        "version" to "mobile ${BuildConfig.VERSION_NAME}".take(40),
        "commit" to "",
        "built" to "",
        "arch" to "${Build.SUPPORTED_ABIS.firstOrNull() ?: "?"} · Android ${Build.VERSION.RELEASE}".take(40),
    )

    private var lastCpu = Process.getElapsedCpuTime() to SystemClock.elapsedRealtime()

    /** Load and memory for the Sensors page, in sniffowireless's sysinfo shape (no load average: Android hides it). */
    private fun system(): Map<String, Any?> {
        val am = app.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val cores = Runtime.getRuntime().availableProcessors()
        val cpu = Process.getElapsedCpuTime() to SystemClock.elapsedRealtime()
        val wall = (cpu.second - lastCpu.second).coerceAtLeast(1)
        val ours = (100.0 * (cpu.first - lastCpu.first) / wall / cores).coerceIn(0.0, 100.0)
        lastCpu = cpu
        val disk = runCatching { StatFs(app.filesDir.path) }.getOrNull()
        return linkedMapOf(
            "cpu" to linkedMapOf("cores" to cores, "sniffowireless_percent" to Math.round(ours * 10) / 10.0),
            "memory" to linkedMapOf("total" to mem.totalMem, "used" to mem.totalMem - mem.availMem, "sniffowireless_rss" to Debug.getPss() * 1024L),
            "disk" to disk?.let { linkedMapOf("total" to it.totalBytes, "used" to it.totalBytes - it.availableBytes, "free" to it.availableBytes) },
        )
    }
}
