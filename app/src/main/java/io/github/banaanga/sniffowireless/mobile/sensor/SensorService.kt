package io.github.banaanga.sniffowireless.mobile.sensor

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.banaanga.sniffowireless.mobile.MainActivity
import io.github.banaanga.sniffowireless.mobile.R
import io.github.banaanga.sniffowireless.mobile.core.Channels
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sensor mode: keeps the chosen sources capturing with the screen off, and sends what they hear to the sniffowireless
 * server every 2 seconds, the way one of its remote sensors does.
 */
class SensorService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private val jobs = ArrayList<Job>()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSensor()
            return START_NOT_STICKY
        }
        val hub = applicationContext.hub
        val target = hub.settings.target
        if (target == null) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundCompat(notification("Connecting to ${target.serverUrl}…"))
        if (hub.sending) return START_STICKY
        hub.settings.enabled = true
        hub.setSending(true)
        hub.log("INFO", "sniffowireless.mobile", "Sensor mode on: sending to ${target.serverUrl}")
        acquireLocks()
        startSources(hub)
        jobs += scope.launch { uploadLoop(hub) }
        return START_STICKY
    }

    private fun startSources(hub: Hub) {
        val s = hub.settings
        if (s.phoneWifi) jobs += scope.launch(Dispatchers.Main) { hub.wifi.runForSensor() }
        if (s.phoneBt) jobs += scope.launch(Dispatchers.Main) { hub.bt.runForSensor() }
        if (s.usbWifi && hub.wifiMonitor.adapter.value != null && !hub.wifiMonitor.running.value) {
            val own = runCatching { Channels.check(s.wifiChannels) }.getOrElse { Channels.parse(SensorSettings.DEFAULT_WIFI_CHANNELS) }
            jobs += scope.launch { hub.wifiMonitor.run(own) }
        }
        if (s.usbBt && hub.btUsb.adapter.value != null && !hub.btUsb.running.value) {
            jobs += scope.launch { hub.btUsb.run(passive = s.btPassive) }
        }
        // LoRa boards and Zigbee sticks can have the same USB serial chip: find the lora-sniffers first (they answer PING),
        // then give Zigbee a stick that isn't one.
        if (s.lora || s.zigbee) jobs += scope.launch {
            if (s.lora && !hub.lora.running.value) {
                hub.lora.boards.value.forEach { hub.lora.probe(it) }
                hub.lora.boards.value.firstOrNull { it.device.deviceName in hub.loraSniffers }?.let { board ->
                    launch { hub.lora.run(board, forSensor = true) }
                }
            }
            if (s.zigbee && !hub.zigbee.running.value) {
                hub.zigbee.refresh()
                hub.zigbee.dongles.value.firstOrNull { it.device != null && it.blocker == null }?.let { dongle ->
                    launch { hub.zigbee.runForSensor(dongle) }
                }
            }
        }
    }

    private suspend fun uploadLoop(hub: Hub) {
        val f = hub.forwarder
        while (true) {
            delay(f.delayMs())
            withContext(Dispatchers.IO) { f.step() }
            val text = f.lastError?.let { "Retrying: $it" }
                ?: "Sent %,d observations · %d interfaces".format(f.sent, hub.status.snapshot().size)
            getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification(text))
        }
    }

    private fun stopSensor() {
        val hub = applicationContext.hub
        hub.settings.enabled = false
        jobs.forEach { it.cancel() }
        jobs.clear()
        scope.launch {
            // Send what is left, with the sources' status entries gone, so the server shows them as stopped at once.
            delay(300)
            withContext(Dispatchers.IO) { runCatching { hub.forwarder.step() } }
            hub.setSending(false)
            hub.log("INFO", "sniffowireless.mobile", "Sensor mode off")
            releaseLocks()
            ServiceCompat.stopForeground(this@SensorService, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    override fun onDestroy() {
        val hub = applicationContext.hub
        if (hub.sending) hub.setSending(false)
        releaseLocks()
        scope.cancel()
        super.onDestroy()
    }

    private fun acquireLocks() {
        wakeLock = getSystemService(PowerManager::class.java)?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "sniffowireless:sensor")
            ?.apply { setReferenceCounted(false); acquire() }
        @Suppress("DEPRECATION")
        wifiLock = applicationContext.getSystemService(WifiManager::class.java)
            ?.createWifiLock(if (Build.VERSION.SDK_INT >= 29) WifiManager.WIFI_MODE_FULL_HIGH_PERF else WifiManager.WIFI_MODE_FULL, "sniffowireless:sensor")
            ?.apply { setReferenceCounted(false); acquire() }
    }

    private fun releaseLocks() {
        runCatching { wakeLock?.takeIf { it.isHeld }?.release() }
        runCatching { wifiLock?.takeIf { it.isHeld }?.release() }
        wakeLock = null
        wifiLock = null
    }

    private fun startForegroundCompat(n: Notification) {
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION) || granted(Manifest.permission.ACCESS_COARSE_LOCATION)) {
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            }
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, types)
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notification(text: String): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26 && nm?.getNotificationChannel(CHANNEL) == null) {
            nm?.createNotificationChannel(NotificationChannel(CHANNEL, "Sensor mode", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Shown while the phone sends to a sniffowireless server"
            })
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_SENSOR, true)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(this, 1, Intent(this, SensorService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sensor)
            .setContentTitle("Sensor for sniffowireless")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "Stop", stop)
            .build()
    }

    companion object {
        private const val CHANNEL = "sensor"
        private const val NOTIFICATION_ID = 7
        private const val ACTION_STOP = "stop"

        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, SensorService::class.java))

        fun stop(context: Context) {
            context.startService(Intent(context, SensorService::class.java).setAction(ACTION_STOP))
        }
    }
}
