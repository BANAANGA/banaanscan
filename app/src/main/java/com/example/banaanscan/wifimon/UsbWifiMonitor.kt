package com.example.banaanscan.wifimon

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.banaanscan.data.OuiDatabase
import com.example.banaanscan.mt7921u.Channel
import com.example.banaanscan.mt7921u.Dot11
import com.example.banaanscan.mt7921u.Monitor
import com.example.banaanscan.mt7921u.Mt7921Exception
import com.example.banaanscan.mt7921u.Mt7921u
import com.example.banaanscan.mt7921u.PatchFile
import com.example.banaanscan.mt7921u.RamFile
import com.example.banaanscan.mt7921u.UsbIo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.DataOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android's USB host API behind the shared driver's [UsbIo]. */
private class AndroidUsbIo(private val connection: UsbDeviceConnection, iface: UsbInterface) : UsbIo {
    private val endpoints: Map<Int, UsbEndpoint> =
        (0 until iface.endpointCount).map { iface.getEndpoint(it) }.associateBy { it.address }

    private fun ep(address: Int) = endpoints[address] ?: throw Mt7921Exception("The adapter has no endpoint 0x%02x".format(address))

    override fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        val buf = ByteArray(length)
        return if (connection.controlTransfer(requestType, request, value, index, buf, length, timeoutMs) == length) buf else null
    }

    override fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = connection.controlTransfer(requestType, request, value, index, data.takeIf { it.isNotEmpty() }, data.size, timeoutMs)
        if (n < 0) throw Mt7921Exception("Control transfer to the adapter failed")
        return n
    }

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = connection.bulkTransfer(ep(endpoint), data, data.size, timeoutMs)
        if (n < 0) throw Mt7921Exception("Sending to the adapter failed (endpoint 0x%02x)".format(endpoint))
        return n
    }

    /** Android reports a timeout and an error the same way (-1), so both count as "nothing yet". */
    override fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(ep(endpoint), buffer, buffer.size, timeoutMs).coerceAtLeast(0)
}

/** Writes frames as a pcap file with a small radiotap header, readable by Wireshark. */
private class PcapWriter(file: File) : AutoCloseable {
    private val out = DataOutputStream(file.outputStream().buffered())

    init {
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0).putInt(65535).putInt(127).array())
    }

    fun write(freqMhz: Int, rssi: Int?, frame: ByteArray) {
        val rt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        rt.put(0).put(0).putShort(16).putInt((1 shl 1) or (1 shl 3) or (if (rssi != null) 1 shl 5 else 0))
        rt.put(0).put(0).putShort(freqMhz.toShort()).putShort((if (freqMhz < 3000) 0x0080 else 0x0100).toShort())
        rt.put((rssi ?: 0).toByte())
        val now = System.currentTimeMillis()
        out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((now / 1000).toInt()).putInt(((now % 1000) * 1000).toInt()).putInt(16 + frame.size).putInt(16 + frame.size).array())
        out.write(rt.array())
        out.write(frame)
    }

    override fun close() = out.close()
}

data class HeardNetwork(val bssid: String, val ssid: String?, val freqMhz: Int, val rssi: Int?, val beacons: Int,
                        val clients: Int, val vendor: String?, val lastSeen: Long)

data class HeardClient(val mac: String, val bssid: String?, val freqMhz: Int, val rssi: Int?, val frames: Int,
                       val probes: Set<String>, val randomized: Boolean, val vendor: String?, val lastSeen: Long)

data class ChannelCount(val freqMhz: Int, val frames: Int)

enum class ChannelPlan(val label: String, val freqs: List<Int>) {
    BAND_24("2.4 GHz", listOf(2412, 2437, 2462)),
    BAND_5("5 GHz", listOf(5180, 5200, 5220, 5240, 5745, 5765, 5785, 5805)),
    BAND_6("6 GHz", (5..229 step 16).map { 5950 + 5 * it }), // preferred scanning channels
    ALL("All", BAND_24.freqs + BAND_5.freqs + BAND_6.freqs),
}

/**
 * Wi-Fi monitor mode through an MT7921U USB adapter, using BanaanScan's own driver (no root). Only listens: the
 * driver never transmits. The firmware is bundled in assets/mt7921u under MediaTek's licence (LICENCE.mediatek there);
 * a copy put on the phone with tools/mt7921u/push-firmware.sh takes precedence, for trying other versions.
 */
class UsbWifiMonitor(context: Context) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    val firmwareDir = File(app.filesDir, "mt7921u")
    /** Readable after a session with `adb shell run-as io.github.banaanga.banaanscan cat files/mt7921u/log.txt`. */
    val logFile get() = File(firmwareDir, "log.txt")

    val adapter = MutableStateFlow<UsbDevice?>(null)
    val firmwarePresent = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val currentFreq = MutableStateFlow<Int?>(null)
    private val _networks = MutableStateFlow<List<HeardNetwork>>(emptyList())
    val networks: StateFlow<List<HeardNetwork>> = _networks.asStateFlow()
    private val _clients = MutableStateFlow<List<HeardClient>>(emptyList())
    val clients: StateFlow<List<HeardClient>> = _clients.asStateFlow()
    private val _channels = MutableStateFlow<List<ChannelCount>>(emptyList())
    val channels: StateFlow<List<ChannelCount>> = _channels.asStateFlow()
    val frames = MutableStateFlow(0)

    private val hotplug = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    init {
        ContextCompat.registerReceiver(
            app, hotplug,
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        refresh()
    }

    fun refresh() {
        adapter.value = usb?.deviceList?.values?.firstOrNull { it.vendorId == Mt7921u.VENDOR_ID && it.productId == Mt7921u.PRODUCT_ID }
        firmwarePresent.value = FIRMWARE.all { File(firmwareDir, it).isFile || isBundled(it) }
    }

    private fun isBundled(name: String) = runCatching { app.assets.open("mt7921u/$name").close() }.isSuccess

    /** The firmware file and where it came from: a pushed copy wins over the bundled one. */
    private fun firmware(name: String): Pair<ByteArray, String> {
        val pushed = File(firmwareDir, name)
        if (pushed.isFile) return pushed.readBytes() to "pushed copy"
        return app.assets.open("mt7921u/$name").use { it.readBytes() } to "bundled"
    }

    fun close() {
        runCatching { app.unregisterReceiver(hotplug) }
    }

    suspend fun run(plan: ChannelPlan, dwellMs: Long = 400) {
        val device = adapter.value ?: return
        running.value = true
        error.value = null
        frames.value = 0
        _networks.value = emptyList()
        _clients.value = emptyList()
        _channels.value = emptyList()
        firmwareDir.mkdirs()
        val log = PrintWriter(logFile.outputStream().bufferedWriter(), true)
        fun note(msg: String) {
            log.println(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + msg)
            status.value = msg
        }
        try {
            if (!firmwarePresent.value) {
                throw Mt7921Exception("Firmware missing from this build. Copy it to the phone with tools/mt7921u/push-firmware.sh, then try again.")
            }
            note("Waiting for USB permission…")
            if (!ensurePermission(device)) throw Mt7921Exception("USB permission was denied.")
            withContext(Dispatchers.IO) { capture(device, plan, dwellMs, ::note) }
        } catch (e: CancellationException) {
            note("Stopped")
            throw e
        } catch (e: Exception) {
            note("Failed: ${e.message}")
            error.value = e.message ?: e.toString()
        } finally {
            running.value = false
            currentFreq.value = null
            log.close()
        }
    }

    private suspend fun capture(device: UsbDevice, plan: ChannelPlan, dwellMs: Long, note: (String) -> Unit) {
        val manager = usb ?: throw Mt7921Exception("This phone has no USB host support.")
        val connection = manager.openDevice(device) ?: throw Mt7921Exception("Couldn't open the adapter. Does the phone supply enough power over USB-OTG?")
        val iface = device.getInterface(0)
        try {
            if (!connection.claimInterface(iface, true)) throw Mt7921Exception("Couldn't claim the adapter's USB interface.")
            note("Loading firmware…")
            val chip = Mt7921u(AndroidUsbIo(connection, iface)) { note(it) }
            val started = System.currentTimeMillis()
            val (patchBytes, patchSource) = firmware(FIRMWARE[0])
            val (ramBytes, ramSource) = firmware(FIRMWARE[1])
            note("Firmware: patch $patchSource, RAM $ramSource")
            val info = chip.bringUp(PatchFile(patchBytes), RamFile(ramBytes))
            note("Firmware ${info.firmware} running after ${System.currentTimeMillis() - started} ms; adapter ${info.capabilities.macAddress}")
            val channels = plan.freqs.map(Channel::fromFreq)
            val monitor = Monitor(chip)
            monitor.start(channels.first())
            val pcap = File(firmwareDir, "capture.pcap")
            PcapWriter(pcap).use { writer ->
                val collector = Collector()
                var index = 0
                var lastPublish = 0L
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val channel = channels[index % channels.size]
                    if (index > 0 && channels.size > 1) monitor.tune(channel)
                    currentFreq.value = channel.freqMhz
                    status.value = "Listening on %d MHz".format(channel.freqMhz)
                    val end = System.currentTimeMillis() + dwellMs
                    while (System.currentTimeMillis() < end) {
                        val f = monitor.receive(100) ?: continue
                        if (f.fcsError) continue
                        writer.write(f.freqMhz, f.rssi, f.data)
                        collector.add(f.freqMhz, f.rssi, f.data)
                    }
                    if (System.currentTimeMillis() - lastPublish > 500) {
                        lastPublish = System.currentTimeMillis()
                        _networks.value = collector.networks()
                        _clients.value = collector.clients()
                        _channels.value = collector.channels()
                        frames.value = collector.total
                    }
                    if (index % 20 == 0) note("${collector.total} frames, ${collector.networkCount} networks so far")
                    index++
                }
            }
        } finally {
            runCatching { connection.releaseInterface(iface) }
            connection.close()
        }
    }

    /** Turns frames into networks and the devices talking in them. */
    private class Collector {
        var total = 0
        private val nets = HashMap<String, HeardNetwork>()
        private val stations = HashMap<String, HeardClient>()
        private val perChannel = HashMap<Int, Int>()
        val networkCount get() = nets.size

        fun add(freq: Int, rssi: Int?, data: ByteArray) {
            total++
            perChannel[freq] = (perChannel[freq] ?: 0) + 1
            val f = Dot11.parse(data) ?: return
            val now = System.currentTimeMillis()
            val frameBssid = f.bssid
            if ((f.isBeacon || f.isProbeResponse) && frameBssid != null) {
                val old = nets[frameBssid]
                nets[frameBssid] = HeardNetwork(frameBssid, f.ssid ?: old?.ssid, freq, rssi ?: old?.rssi,
                    (old?.beacons ?: 0) + if (f.isBeacon) 1 else 0, old?.clients ?: 0,
                    old?.vendor ?: vendor(frameBssid), now)
                return
            }
            val sender = f.transmitter ?: return
            if (sender == f.bssid || nets.containsKey(sender)) return // the access point itself
            if (f.isProbeRequest || f.isData) {
                val old = stations[sender]
                val bssid = if (f.isData) f.bssid else old?.bssid
                val probes = old?.probes.orEmpty() + listOfNotNull(f.ssid?.takeIf { f.isProbeRequest && it.isNotEmpty() })
                stations[sender] = HeardClient(sender, bssid, freq, rssi ?: old?.rssi, (old?.frames ?: 0) + 1, probes.take(10).toSet(),
                    OuiDatabase.isLocallyAdministered(sender), old?.vendor ?: vendor(sender), now)
                if (bssid != null && old?.bssid != bssid) nets[bssid]?.let { nets[bssid] = it.copy(clients = it.clients + 1) }
            }
        }

        private fun vendor(mac: String) = if (OuiDatabase.isLocallyAdministered(mac)) null else OuiDatabase.lookup(mac)

        fun networks() = nets.values.sortedByDescending { it.rssi ?: -127 }
        fun clients() = stations.values.sortedByDescending { it.frames }.take(200)
        fun channels() = perChannel.map { ChannelCount(it.key, it.value) }.sortedBy { it.freqMhz }
    }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val answered = CompletableDeferred<Boolean>()
        val action = "${app.packageName}.WIFI_ADAPTER_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                answered.complete(manager.hasPermission(device))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(app, 1, Intent(action).setPackage(app.packageName), flags))
            return answered.await()
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        val FIRMWARE = listOf("WIFI_MT7961_patch_mcu_1_2_hdr.bin", "WIFI_RAM_CODE_MT7961_1.bin")
    }
}
