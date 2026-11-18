package io.github.banaanga.sniffowireless.mobile.wifimon

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
import io.github.banaanga.sniffowireless.mobile.data.OuiDatabase
import io.github.banaanga.sniffowireless.mobile.mt7921u.Channel
import io.github.banaanga.sniffowireless.mobile.mt7921u.Dot11
import io.github.banaanga.sniffowireless.mobile.mt7921u.Monitor
import io.github.banaanga.sniffowireless.mobile.mt7921u.Mt7921Exception
import io.github.banaanga.sniffowireless.mobile.mt7921u.Mt7921u
import io.github.banaanga.sniffowireless.mobile.mt7921u.PatchFile
import io.github.banaanga.sniffowireless.mobile.mt7921u.RamFile
import io.github.banaanga.sniffowireless.mobile.mt7921u.RxPhy
import io.github.banaanga.sniffowireless.mobile.mt7921u.UsbIo
import io.github.banaanga.sniffowireless.mobile.core.Recorder
import io.github.banaanga.sniffowireless.mobile.ath9k.Ath9kHtc
import io.github.banaanga.sniffowireless.mobile.core.AttrGates
import io.github.banaanga.sniffowireless.mobile.core.Channels
import io.github.banaanga.sniffowireless.mobile.core.Gate
import io.github.banaanga.sniffowireless.mobile.core.JoinWatcher
import io.github.banaanga.sniffowireless.mobile.core.LinkCounter
import io.github.banaanga.sniffowireless.mobile.core.PhyRates
import io.github.banaanga.sniffowireless.mobile.core.WifiFrames
import io.github.banaanga.sniffowireless.mobile.sensor.Hub
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import io.github.banaanga.sniffowireless.mobile.core.WifiControl
import java.io.DataOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Android's USB host API behind both drivers' UsbIo. bulkTransfer also serves the AR9271's interrupt endpoints. */
private class AndroidUsbIo(private val connection: UsbDeviceConnection, iface: UsbInterface) : UsbIo, io.github.banaanga.sniffowireless.mobile.ath9k.UsbIo {
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

    @Synchronized
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

/** The USB Wi-Fi adapters sniffowireless-mobile has its own driver for. */
enum class AdapterKind(val label: String, val iface: String, val bands: List<String>, val ids: List<Pair<Int, Int>>, val firmware: List<String>) {
    MT7921U("MediaTek MT7921U", "mt7921u", listOf("2.4", "5", "6"), listOf(Mt7921u.VENDOR_ID to Mt7921u.PRODUCT_ID),
        listOf("WIFI_MT7961_patch_mcu_1_2_hdr.bin", "WIFI_RAM_CODE_MT7961_1.bin")),
    AR9271("Atheros AR9271", "ar9271", listOf("2.4"), Ath9kHtc.USB_IDS, listOf("htc_9271-1.4.0.fw")),
    ;

    /** The asset folder its firmware is bundled in. */
    val assets get() = iface
    fun matches(d: UsbDevice) = ids.any { it.first == d.vendorId && it.second == d.productId }
    /** [freqs] reduced to the bands this adapter covers; the 2.4 GHz channels 1, 6 and 11 if none are left. */
    fun usable(freqs: List<Int>) = freqs.filter { Channels.bandOf(it) in bands }.ifEmpty { listOf(2412, 2437, 2462) }

    companion object {
        fun of(d: UsbDevice) = entries.firstOrNull { it.matches(d) }
    }
}

/** A received frame, from either driver. [rate] in Mb/s and [mcs] as sniffowireless labels it ("HT MCS 7 1SS 20"). */
private class Heard(val freqMhz: Int, val rssi: Int?, val fcsError: Boolean, val data: ByteArray, val rate: Double?, val mcs: String?)

/** What the capture loop needs from an adapter, whichever chip it has. */
private interface Radio : AutoCloseable {
    val firmware: String
    fun start(freq: Int)
    fun tune(freq: Int)
    fun receive(timeoutMs: Int): Heard?
}

private class Mt7921Radio(private val chip: Mt7921u, override val firmware: String) : Radio {
    private val monitor = Monitor(chip)
    override fun start(freq: Int) = monitor.start(Channel.fromFreq(freq))
    override fun tune(freq: Int) = monitor.tune(Channel.fromFreq(freq))
    override fun receive(timeoutMs: Int): Heard? = monitor.receive(timeoutMs)?.let { f ->
        val (rate, mcs) = rateOf(f.phy)
        Heard(f.freqMhz, f.rssi, f.fcsError, f.data, rate, mcs)
    }
    override fun close() {}
}

private class Ar9271Radio(private val dev: Ath9kHtc) : Radio {
    override val firmware get() = "htc_9271 " + dev.firmwareVersion
    override fun start(freq: Int) = dev.startMonitor(io.github.banaanga.sniffowireless.mobile.ath9k.Channel(freq))
    override fun tune(freq: Int) = dev.setChannel(io.github.banaanga.sniffowireless.mobile.ath9k.Channel(freq))
    override fun receive(timeoutMs: Int): Heard? = dev.receive(timeoutMs)?.let { f ->
        val ht = f.mcs?.let { PhyRates.ht(it, if (f.ht40) 40 else 20, f.shortGi) }
        Heard(f.freqMhz, f.rssi, f.fcsError, f.data, ht?.first ?: f.legacyMbps, ht?.second)
    }
    /** Back into the boot ROM: the next start loads the firmware again, as after plugging it in. */
    override fun close() = dev.close()
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

/** A supported Wi-Fi adapter that is plugged in. [name] is how it is reported to sniffowireless ("mt7921u", "ar9271"; a
 * second one of the same kind "mt7921u-2"), and keys its channel settings. */
data class WifiAdapter(val device: UsbDevice, val kind: AdapterKind, val name: String) {
    /** Whether it can tune [freq]. */
    fun can(freq: Int) = Channels.bandOf(freq) in kind.bands
}

/** What one adapter is doing, for the Wi-Fi page. */
data class AdapterState(val status: String? = null, val error: String? = null, val freq: Int? = null, val channels: List<Int> = emptyList())

/**
 * Wi-Fi monitor mode through USB adapters (MT7921U and AR9271), using sniffowireless-mobile's own drivers (no root).
 * Several adapters run at once, each hopping over its share of the channels (see [WifiControl.plan]). Only listens: the
 * drivers never transmit. The firmware is bundled in assets/ under the makers' licences; a copy put on the phone with
 * tools/mt7921u/push-firmware.sh takes precedence, for trying other versions.
 */
class UsbWifiMonitor(context: Context, private val hub: Hub? = null) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    val firmwareDir = File(app.filesDir, "mt7921u")
    /** Readable after a session with `adb shell run-as io.github.banaanga.sniffowireless.mobile.debug cat files/mt7921u/log.txt`. */
    val logFile get() = File(firmwareDir, "log.txt")

    /** Every supported adapter plugged in. */
    val adapters = MutableStateFlow<List<WifiAdapter>>(emptyList())
    /** The first of [adapters], for places that only name one. */
    val adapter = MutableStateFlow<UsbDevice?>(null)
    val kind = MutableStateFlow<AdapterKind?>(null)
    val firmwarePresent = MutableStateFlow(false)
    val running = MutableStateFlow(false)
    /** Messages about the session as a whole (permission, firmware); each adapter's are in [states]. */
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    /** Per adapter, by [WifiAdapter.name]. */
    val states = MutableStateFlow<Map<String, AdapterState>>(emptyMap())
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
        val found = usb?.deviceList?.values.orEmpty().mapNotNull { d -> AdapterKind.of(d)?.let { d to it } }.sortedBy { it.first.deviceName }
        val seen = HashMap<AdapterKind, Int>()
        val list = found.map { (d, k) ->
            val n = (seen[k] ?: 0) + 1
            seen[k] = n
            WifiAdapter(d, k, if (n == 1) k.iface else "${k.iface}-$n")
        }
        adapters.value = list
        adapter.value = list.firstOrNull()?.device
        kind.value = list.firstOrNull()?.kind
        firmwarePresent.value = list.all { a -> a.kind.firmware.all { File(firmwareDir, it).isFile || isBundled(a.kind, it) } }
    }

    private fun isBundled(k: AdapterKind, name: String) = runCatching { app.assets.open("${k.assets}/$name").close() }.isSuccess

    /** The firmware file and where it came from: a pushed copy wins over the bundled one. */
    private fun firmware(k: AdapterKind, name: String): Pair<ByteArray, String> {
        val pushed = File(firmwareDir, name)
        if (pushed.isFile) return pushed.readBytes() to "pushed copy"
        return app.assets.open("${k.assets}/$name").use { it.readBytes() } to "bundled"
    }

    fun close() {
        runCatching { app.unregisterReceiver(hotplug) }
    }

    /** This phone's own channel list for one adapter (Wi-Fi page / Sensor page), or null for the automatic share. */
    private fun ownList(a: WifiAdapter): List<Int>? =
        hub?.settings?.wifiChannelsFor(a.name)?.takeIf { it.isNotBlank() }?.let { runCatching { Channels.check(it) }.getOrNull() }

    /**
     * Which channels each adapter hops over and how long it stays on each: [ownFreqs] split over the adapters, an
     * adapter's own list where one is set, and in sensor mode the server's settings on top.
     */
    fun plan(ownFreqs: List<Int>, dwellMs: Long, list: List<WifiAdapter> = adapters.value): Pair<Map<String, List<Int>>, Long> {
        val sensor = hub
        val control = if (sensor != null && sensor.sending) sensor.wifiControl else LOCAL
        val own = list.mapNotNull { a -> ownList(a)?.let { a.name to it } }.toMap()
        val p = control.plan(ownFreqs, own, list.associate { a -> a.name to a::can })
        return list.associate { a -> a.name to a.kind.usable(p[a.name].orEmpty()) } to (control.hopInterval(dwellMs / 1000.0) * 1000).toLong()
    }

    suspend fun run(plan: ChannelPlan, dwellMs: Long = 400) = run(plan.freqs, dwellMs)

    /**
     * Listens with every adapter plugged in, [dwellMs] per channel, the channels split between them ([plan], re-read on
     * every hop). One adapter failing or being unplugged doesn't stop the others.
     */
    suspend fun run(freqs: List<Int>, dwellMs: Long = 400) {
        val list = adapters.value
        if (list.isEmpty()) return
        running.value = true
        error.value = null
        frames.value = 0
        _networks.value = emptyList()
        _clients.value = emptyList()
        _channels.value = emptyList()
        states.value = list.associate { it.name to AdapterState(status = "Waiting") }
        firmwareDir.mkdirs()
        val log = PrintWriter(logFile.outputStream().bufferedWriter(), true)
        val time = SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT)
        fun note(msg: String) {
            synchronized(log) { log.println(time.format(Date()) + " " + msg) }
            status.value = msg
        }
        try {
            if (!firmwarePresent.value) {
                throw Mt7921Exception("Firmware missing from this build. Copy it to the phone with tools/mt7921u/push-firmware.sh, then try again.")
            }
            val allowed = list.filter { a ->
                note("Waiting for USB permission for the ${a.kind.label}…")
                ensurePermission(a.device).also { ok -> if (!ok) setState(a) { it.copy(error = "USB permission was denied.") } }
            }
            if (allowed.isEmpty()) throw Mt7921Exception("USB permission was denied.")
            status.value = null
            val collector = Collector()
            PcapWriter(File(firmwareDir, "capture.pcap")).use { writer ->
                withContext(Dispatchers.IO) {
                    coroutineScope {
                        for (a in allowed) launch {
                            val tag: (String) -> Unit = { msg ->
                                synchronized(log) { log.println(time.format(Date()) + " [" + a.name + "] " + msg) }
                                setState(a) { it.copy(status = msg) }
                            }
                            try {
                                capture(a, allowed, freqs, dwellMs, collector, writer, tag)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // a driver problem shows on the page (Errors too) and leaves the other adapters running
                                tag("Failed: ${e.message ?: e.javaClass.simpleName}")
                                setState(a) { it.copy(error = e.message ?: e.toString(), freq = null) }
                            }
                        }
                        launch {
                            while (true) {
                                delay(500)
                                _networks.value = collector.networks()
                                _clients.value = collector.clients()
                                _channels.value = collector.channels()
                                frames.value = collector.total
                                if (states.value.values.all { it.error != null }) break
                            }
                        }
                    }
                }
            }
        } catch (e: CancellationException) {
            note("Stopped")
            throw e
        } catch (e: Throwable) {
            note("Failed: ${e.message ?: e.javaClass.simpleName}")
            error.value = e.message ?: e.toString()
        } finally {
            running.value = false
            states.value = states.value.mapValues { it.value.copy(freq = null) }
            log.close()
        }
    }

    private fun setState(a: WifiAdapter, change: (AdapterState) -> AdapterState) {
        synchronized(states) { states.value = states.value + (a.name to change(states.value[a.name] ?: AdapterState())) }
    }

    private suspend fun capture(a: WifiAdapter, all: List<WifiAdapter>, ownFreqs: List<Int>, dwellMs: Long,
                                collector: Collector, writer: PcapWriter, note: (String) -> Unit) {
        val device = a.device
        val kind = a.kind
        val manager = usb ?: throw Mt7921Exception("This phone has no USB host support.")
        val connection = manager.openDevice(device) ?: throw Mt7921Exception("Couldn't open the adapter. Does the phone supply enough power over USB-OTG?")
        val iface = device.getInterface(0)
        val sensor = hub
        val statusName = "wifi:${a.name}"
        var radio: Radio? = null
        try {
            if (!connection.claimInterface(iface, true)) throw Mt7921Exception("Couldn't claim the adapter's USB interface.")
            note("Loading firmware…")
            val io = AndroidUsbIo(connection, iface)
            val started = System.currentTimeMillis()
            radio = when (kind) {
                AdapterKind.MT7921U -> {
                    val chip = Mt7921u(io) { note(it) }
                    val (patchBytes, patchSource) = firmware(kind, kind.firmware[0])
                    val (ramBytes, ramSource) = firmware(kind, kind.firmware[1])
                    note("Firmware: patch $patchSource, RAM $ramSource")
                    val info = chip.bringUp(PatchFile(patchBytes), RamFile(ramBytes))
                    note("Firmware ${info.firmware} running after ${System.currentTimeMillis() - started} ms; adapter ${info.capabilities.macAddress}")
                    Mt7921Radio(chip, info.firmware.toString())
                }
                AdapterKind.AR9271 -> {
                    val (fw, source) = firmware(kind, kind.firmware[0])
                    note("Firmware: $source")
                    val dev = Ath9kHtc(io, { note(it) })
                    dev.bringUp(fw)
                    note("Firmware ${dev.firmwareVersion} running after ${System.currentTimeMillis() - started} ms")
                    Ar9271Radio(dev)
                }
            }
            fun mine(): Pair<List<Int>, Long> = plan(ownFreqs, dwellMs, all).let { (p, d) -> p.getValue(a.name) to d }
            var (freqs, dwell) = mine()
            radio.start(freqs.first())
            setState(a) { it.copy(channels = freqs) }
            sensor?.status?.register(statusName, "type" to "wifi", "iface" to a.name, "mon" to a.name, "state" to "capturing",
                "channels" to freqs.map(Channels::label), "bands" to freqs.mapNotNull(Channels::bandOf).distinct().sorted(),
                "supported_bands" to kind.bands, "chan_rev" to sensor.wifiControl.rev, "regdomain" to sensor.wifiControl.override?.regdomain,
                "hw_bus" to "usb", "hw_usb" to "%04x:%04x".format(device.vendorId, device.productId), "hw_driver" to "sniffowireless-mobile (${kind.iface})",
                "hw_maker" to device.manufacturerName, "hw_product" to device.productName, "chip" to kind.label, "firmware" to radio.firmware.take(64))
            val fpGate = Gate()
            val gates = AttrGates()
            val links = LinkCounter()      // rates, MCS and retries of every client's data frames (the server's link quality)
            val joins = JoinWatcher()      // the steps of every client's connections (the server's troubleshooter and roams)
            var index = 0
            var tuned = freqs.first()
            var lastRate = System.currentTimeMillis()
            var mine = 0
            var lastMine = 0
            val bandFrames = HashMap<String, Int>()
            while (true) {
                currentCoroutineContext().ensureActive()
                val (nowFreqs, nowDwell) = mine()
                if (nowFreqs != freqs) {
                    freqs = nowFreqs
                    index = 0
                    setState(a) { it.copy(channels = freqs) }
                    sensor?.status?.update(statusName, "channels" to freqs.map(Channels::label),
                        "bands" to freqs.mapNotNull(Channels::bandOf).distinct().sorted(), "chan_rev" to sensor.wifiControl.rev,
                        "regdomain" to sensor.wifiControl.override?.regdomain)
                    note("Channels now " + freqs.joinToString(",") { Channels.label(it) })
                } else {
                    sensor?.status?.update(statusName, "chan_rev" to sensor.wifiControl.rev)
                }
                dwell = nowDwell
                val freq = freqs[index % freqs.size]
                if (freq != tuned) {
                    radio.tune(freq)
                    tuned = freq
                }
                setState(a) { it.copy(freq = freq, status = "Listening on %d MHz".format(freq), error = null) }
                sensor?.status?.update(statusName, "channel" to Channels.label(freq), "band" to Channels.bandOf(freq))
                // The channel survey sniffowireless keeps per dwell: frames, bytes, rough airtime and retries.
                var sFrames = 0
                var sBytes = 0L
                var sAirtime = 0.0
                var sRetries = 0
                val t0 = System.currentTimeMillis()
                val end = t0 + dwell
                while (System.currentTimeMillis() < end) {
                    val f = radio.receive(100) ?: continue
                    if (f.fcsError) continue
                    writer.write(f.freqMhz, f.rssi, f.data)
                    sensor?.recorder?.takeIf { it.tapping }?.record("wifi", statusName, Recorder.radiotap(f.freqMhz, f.rssi) + f.data, mapOf("tuned" to freq))
                    collector.add(f.freqMhz, f.rssi, f.data)
                    mine++
                    sFrames++
                    sBytes += f.data.size
                    sAirtime += 20 + f.data.size * 8 / 24.0
                    if (f.data.size > 1 && (f.data[1].toInt() and 0x08) != 0) sRetries++
                    Channels.bandOf(WifiFrames.radioFreq(f.freqMhz, freq))?.let { bandFrames[it] = (bandFrames[it] ?: 0) + 1 }
                    if (sensor != null && sensor.sending) {
                        for (o in WifiFrames.parse(f.data, f.rssi, f.freqMhz, freq, sensor.now(), fpGate, gates)) sensor.sink.put(o)
                        for (o in joins.feed(f.data, f.rssi, sensor.now())) sensor.sink.put(o)
                        links.feed(f.data, f.rate, f.mcs, f.rssi)
                    }
                }
                if (sensor != null && sensor.sending && links.due(sensor.now())) {
                    for (o in links.drain(sensor.now())) sensor.sink.put(o)
                }
                if (sensor != null && sensor.sending) {
                    sensor.sink.put(linkedMapOf("t" to "chan", "ts" to sensor.now(), "mac" to "-", "freq" to freq,
                        "dwell_ms" to (System.currentTimeMillis() - t0).toInt(), "frames" to sFrames, "bytes" to sBytes,
                        "airtime_us" to sAirtime.toLong(), "retries" to sRetries, "noise_sum" to 0, "noise_cnt" to 0))
                }
                if (System.currentTimeMillis() - lastRate >= 5000) {
                    val pps = (mine - lastMine) * 1000.0 / (System.currentTimeMillis() - lastRate)
                    lastRate = System.currentTimeMillis()
                    lastMine = mine
                    sensor?.status?.update(statusName, "packets" to mine, "pps" to Math.round(pps * 10) / 10.0,
                        "band_frames" to HashMap(bandFrames))
                }
                if (index % 20 == 0) note("$mine frames, ${collector.networkCount} networks so far")
                index++
            }
        } finally {
            sensor?.status?.remove(statusName)
            radio?.let { runCatching { it.close() } }
            runCatching { connection.releaseInterface(iface) }
            connection.close()
        }
    }

    /** Turns frames into networks and the devices talking in them. */
    private class Collector {
        @Volatile var total = 0
        private val nets = HashMap<String, HeardNetwork>()
        private val stations = HashMap<String, HeardClient>()
        private val perChannel = HashMap<Int, Int>()
        val networkCount @Synchronized get() = nets.size

        @Synchronized
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

        @Synchronized
        fun networks() = nets.values.sortedByDescending { it.rssi ?: -127 }
        @Synchronized
        fun clients() = stations.values.sortedByDescending { it.frames }.take(200)
        @Synchronized
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

    companion object {
        /** Channel planning outside sensor mode: no server settings. */
        private val LOCAL = WifiControl()
    }
}

/** Mb/s and the server's MCS label ("HE MCS 7 2SS 80") of a frame, from what the chip says about how it was sent. */
internal fun rateOf(phy: RxPhy?): Pair<Double?, String?> {
    if (phy == null) return null to null
    phy.legacyMbps?.let { return it to null }
    val got = when {
        phy.isHt -> PhyRates.ht(phy.mcs, phy.width, phy.gi == 1)
        phy.isVht -> PhyRates.vht(phy.mcs, phy.nss, phy.width, phy.gi == 1)
        phy.isHe -> PhyRates.he(phy.mcs, phy.nss, phy.width, phy.gi)
        else -> null
    }
    return (got?.first) to (got?.second)
}
