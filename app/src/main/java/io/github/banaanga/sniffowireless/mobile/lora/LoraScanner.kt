package io.github.banaanga.sniffowireless.mobile.lora

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import io.github.banaanga.sniffowireless.mobile.core.Recorder
import io.github.banaanga.sniffowireless.mobile.core.LineLink
import io.github.banaanga.sniffowireless.mobile.core.LoraClassifier
import io.github.banaanga.sniffowireless.mobile.core.LoraException
import io.github.banaanga.sniffowireless.mobile.core.LoraProfile
import io.github.banaanga.sniffowireless.mobile.core.LoraProfiles
import io.github.banaanga.sniffowireless.mobile.core.LoraRx
import io.github.banaanga.sniffowireless.mobile.core.LoraSniffer
import io.github.banaanga.sniffowireless.mobile.core.Meshtastic
import io.github.banaanga.sniffowireless.mobile.sensor.Hub
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.io.IOException

/** A USB serial device that may be a Heltec WiFi LoRa 32 V3 running sniffowireless's lora-sniffer firmware. */
data class LoraBoard(val device: UsbDevice, val id: String, val name: String)

/** A LoRa node heard: by protocol and the address it sends from. */
data class LoraNode(
    val key: String, val protocol: String, val label: String, val address: String, val name: String?, val kind: String?,
    val packets: Int, val rssi: Double?, val snr: Double?, val lastSeen: Long, val profile: String?,
    val hardware: String? = null, val role: String? = null, val lat: Double? = null, val lon: Double? = null,
    val lastMessage: String? = null, val telemetry: String? = null,
)

/** One frame heard, with what the classifier and the public-channel decoder made of it. */
data class LoraFrame(
    val ts: Long, val protocol: String, val label: String, val kind: String?, val src: String?, val dst: String?, val hops: Int?,
    val confidence: Int, val rssi: Double, val snr: Double, val profile: String, val bytes: Int, val decoded: String?,
)

data class ProfileCount(val profile: String, val frames: Int)

/** The serial port as lines (the firmware speaks 115200 baud ASCII, one line each way). */
private class SerialLines(private val port: UsbSerialPort) : LineLink, Closeable {
    private val buf = StringBuilder()
    private val chunk = ByteArray(1024)

    override fun writeLine(text: String) = port.write((text + "\n").toByteArray(Charsets.US_ASCII), 1000)

    override fun readLine(timeoutMs: Long): String? {
        val end = System.currentTimeMillis() + timeoutMs
        while (true) {
            val i = buf.indexOf("\n")
            if (i >= 0) {
                val line = buf.substring(0, i).trim()
                buf.delete(0, i + 1)
                return line
            }
            val left = end - System.currentTimeMillis()
            if (left <= 0) return null
            val n = port.read(chunk, left.coerceIn(1, 200).toInt())
            if (n > 0) buf.append(String(chunk, 0, n, Charsets.ISO_8859_1))
            if (buf.length > 4096 && buf.indexOf("\n") < 0) buf.setLength(0)  // another firmware's binary output
        }
    }

    override fun close() = port.close()
}

/**
 * LoRa through a Heltec WiFi LoRa 32 V3 with sniffowireless's lora-sniffer firmware: hops over LoRa profiles (Meshtastic,
 * MeshCore, LoRaWAN, LoRa APRS…), recognises each frame's protocol as sniffowireless does, and in sensor mode sends the
 * raw frames to the server, which works them out again on its side. The firmware never transmits.
 */
class LoraScanner(context: Context, private val hub: Hub) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber(
        UsbSerialProber.getDefaultProbeTable().also { table: ProbeTable ->
            table.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
            table.addProduct(0x1A86, 0x55D4, CdcAcmSerialDriver::class.java)
            table.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
            table.addProduct(0x303A, 0x1001, CdcAcmSerialDriver::class.java)
        },
    )

    private val _boards = MutableStateFlow<List<LoraBoard>>(emptyList())
    val boards: StateFlow<List<LoraBoard>> = _boards.asStateFlow()
    val running = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val current = MutableStateFlow<LoraProfile?>(null)
    val firmware = MutableStateFlow<String?>(null)
    private val _nodes = MutableStateFlow<List<LoraNode>>(emptyList())
    val nodes: StateFlow<List<LoraNode>> = _nodes.asStateFlow()
    private val _frames = MutableStateFlow<List<LoraFrame>>(emptyList())
    val frames: StateFlow<List<LoraFrame>> = _frames.asStateFlow()
    private val _perProfile = MutableStateFlow<List<ProfileCount>>(emptyList())
    val perProfile: StateFlow<List<ProfileCount>> = _perProfile.asStateFlow()
    val heard = MutableStateFlow(0)
    val crcErrors = MutableStateFlow(0L)

    /** USB device names that answered as a lora-sniffer: the Zigbee scanner leaves them alone. */
    private val sniffers: MutableSet<String> get() = hub.loraSniffers
    @Volatile private var active: LoraSniffer? = null

    private val hotplug = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) = refresh()
    }

    init {
        ContextCompat.registerReceiver(app, hotplug, IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
        refresh()
    }

    fun refresh() {
        _boards.value = usb?.deviceList?.values.orEmpty().filter { d ->
            val product = (d.productName ?: "").lowercase()
            CANDIDATES.any { it.first == d.vendorId && it.second == d.productId } &&
                ZIGBEE_NAMES.none { it in product } && prober.probeDevice(d) != null
        }.map { d ->
            val id = "%04X:%04X".format(d.vendorId, d.productId)
            LoraBoard(d, id, if (d.deviceName in sniffers) "Heltec V3 with lora-sniffer" else (d.productName?.takeIf { it.isNotBlank() } ?: "USB serial board") + " ($id)")
        }
        sniffers.retainAll(usb?.deviceList?.values.orEmpty().map { it.deviceName }.toSet())
    }

    /** Whether [board] runs the lora-sniffer firmware (it answers PING); remembered, so the Zigbee scanner skips it. */
    suspend fun probe(board: LoraBoard): Boolean {
        if (board.device.deviceName in sniffers) return true
        if (running.value || !ensurePermission(board.device)) return false
        val driver = prober.probeDevice(board.device) ?: return false
        val ok = withContext(Dispatchers.IO) {
            runCatching {
                val port = driver.ports.first()
                port.open(usb!!.openDevice(board.device) ?: return@runCatching false)
                SerialLines(port).use { link ->
                    port.setParameters(LoraSniffer.BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                    runCatching { port.dtr = false; port.rts = false }
                    LoraSniffer(link, ::plan, onFrame = { _, _, _ -> }).handshake()
                    true
                }
            }.getOrDefault(false)
        }
        if (ok) {
            sniffers += board.device.deviceName
            refresh()
            hub.zigbee.refresh()
        }
        return ok
    }

    fun clear() {
        _nodes.value = emptyList()
        _frames.value = emptyList()
        _perProfile.value = emptyList()
        heard.value = 0
    }

    fun stop() {
        active?.stopped = true
    }

    /** This app's own profile list (the LoRa page and sensor mode): the server's when it set one for this sensor. */
    fun plan(): Pair<List<LoraProfile>, Double> {
        val s = hub.settings
        val own = runCatching { LoraProfiles.parse(s.loraProfiles, s.loraRegion) }.getOrElse { LoraProfiles.parse("", "EU868") }
        return hub.loraControl.effective(own, s.loraHop)
    }

    /** Listens with [board] until stopped; in sensor mode also reports it to the server as interface "lora:…". */
    suspend fun run(board: LoraBoard, forSensor: Boolean = false) {
        if (running.value) return
        val name = "lora:" + board.device.deviceName.substringAfterLast('/').let { "usb$it" }
        running.value = true
        error.value = null
        if (forSensor) {
            val (profiles, _) = plan()
            hub.status.register(name, "type" to "lora", "iface" to "usb", "profiles" to profiles.map { it.name }, "lora_rev" to hub.loraControl.rev,
                "hw_bus" to "usb", "hw_usb" to board.id.lowercase(), "hw_maker" to board.device.manufacturerName, "hw_product" to board.device.productName)
        }
        try {
            if (!ensurePermission(board.device)) throw LoraException("USB permission was denied.")
            val driver = prober.probeDevice(board.device) ?: throw LoraException("Android has no serial driver for this USB device.")
            while (currentCoroutineContext().isActive) {
                try {
                    withContext(Dispatchers.IO) {
                        // listen() blocks in serial reads: stopping (cancellation) sets the sniffer's stop flag instead.
                        val watcher = launch { try { awaitCancellation() } finally { active?.stopped = true } }
                        try {
                            listen(board, driver.ports.first(), name, forSensor)
                        } finally {
                            watcher.cancel()
                        }
                    }
                    break  // stopped
                } catch (e: IOException) {
                    if (!forSensor) throw LoraException("Lost contact with the board: ${e.message}")
                    hub.status.update(name, "state" to "error", "error" to "Lost contact: ${e.message}".take(200))
                } catch (e: LoraException) {
                    if (!forSensor) throw e
                    hub.status.update(name, "state" to "error", "error" to e.message?.take(200))
                    error.value = e.message
                }
                delay(RETRY_MS)  // as sniffowireless's sensors do: try again, the board may have been replugged
            }
        } catch (e: LoraException) {
            error.value = e.message
            if (forSensor) hub.status.update(name, "state" to "error", "error" to e.message?.take(200))
        } finally {
            running.value = false
            current.value = null
            active = null
            status.value = null
            if (forSensor) hub.status.remove(name)
        }
    }

    private fun listen(board: LoraBoard, port: UsbSerialPort, name: String, forSensor: Boolean) {
        val manager = usb ?: throw LoraException("This device has no USB host support.")
        val connection = manager.openDevice(board.device) ?: throw LoraException("Couldn't open the USB device. Does the phone support USB-OTG?")
        port.open(connection)
        SerialLines(port).use { link ->
            port.setParameters(LoraSniffer.BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
            // DTR and RTS equal: the ESP32's auto-reset circuit leaves the board running (different levels reset it).
            runCatching { port.dtr = false; port.rts = false }
            sniff(link, board.device.deviceName, name, forSensor)
        }
    }

    /** Debug builds: a simulated board (emulators have no USB host); see the debug source set. */
    var simulator: (() -> LineLink)? = null

    /** Listens with the simulated board until stopped. */
    suspend fun runSimulated() {
        val make = simulator ?: return
        if (running.value) return
        running.value = true
        error.value = null
        try {
            withContext(Dispatchers.IO) {
                val watcher = launch { try { awaitCancellation() } finally { active?.stopped = true } }
                try {
                    sniff(make(), null, "lora:simulated", false)
                } finally {
                    watcher.cancel()
                }
            }
        } catch (e: LoraException) {
            error.value = e.message
        } finally {
            running.value = false
            current.value = null
            active = null
            status.value = null
        }
    }

    private fun sniff(link: LineLink, deviceName: String?, name: String, forSensor: Boolean) {
        run {
            status.value = "Connecting to the board…"
            val sniffer = LoraSniffer(
                link, ::plan, rev = { hub.loraControl.rev },
                onStatus = { st ->
                    if (forSensor) hub.status.update(name, *st.map { it.key to it.value }.toTypedArray())
                    (st["state"] as? String)?.let { if (it == "listening" && deviceName != null && sniffers.add(deviceName)) { refresh(); hub.zigbee.refresh() } }
                    (st["profile"] as? String)?.let { pn -> current.value = plan().first.firstOrNull { it.name == pn } }
                    (st["fw_version"] as? Int)?.let { firmware.value = "lora-sniffer v$it" }
                    (st["crc_errors"] as? Long)?.let { crcErrors.value = it }
                    (st["warning"] as? String)?.let { error.value = it }
                    (st["channel"] as? String)?.let { status.value = "Listening on $it (${st["profile"]})" }
                },
                onFrame = { obs, rx, profile ->
                    if (forSensor) hub.sink.put(obs)
                    if (hub.recorder.active) hub.recorder.record("lora", name, Recorder.loraLine(rx).toByteArray(Charsets.US_ASCII))
                    record(rx, profile)
                },
            )
            active = sniffer
            try {
                sniffer.run()
            } finally {
                sniffer.stopped = true
            }
        }
    }

    /** Adds a frame to the page's lists: its protocol, sender, and for the public Meshtastic channel what it says. */
    private fun record(rx: LoraRx, profile: String) {
        val payload = rx.bytes
        val p = LoraProfiles.PROFILES[profile] ?: plan().first.firstOrNull { it.name == profile }
        val c = LoraClassifier.classify(payload, rx.sync, p?.hint)
        val protocol = c["protocol"] as String
        val decoded = if (protocol == "meshtastic" && hub.settings.meshtasticPublic) Meshtastic.decode(payload) else null
        val now = System.currentTimeMillis()
        heard.value++
        _perProfile.value = (_perProfile.value.associate { it.profile to it.frames } + (profile to ((_perProfile.value.firstOrNull { it.profile == profile }?.frames ?: 0) + 1)))
            .map { ProfileCount(it.key, it.value) }.sortedByDescending { it.frames }
        _frames.value = (listOf(LoraFrame(now, protocol, c["label"] as String, c["kind"] as String?, c["src"] as String?, c["dst"] as String?,
            c["hops"] as Int?, c["confidence"] as Int, rx.rssi, rx.snr, profile, payload.size, summary(decoded))) + _frames.value).take(MAX_FRAMES)
        @Suppress("UNCHECKED_CAST")
        val node = c["node"] as List<String?>? ?: return
        val addr = node[0] ?: return
        if (node[2] == "hash") return  // a one-byte hash is shared by many nodes
        val key = "$protocol/$addr"
        val old = _nodes.value.firstOrNull { it.key == key }
        @Suppress("UNCHECKED_CAST") val user = decoded?.get("user") as Map<String, Any?>?
        @Suppress("UNCHECKED_CAST") val pos = decoded?.get("position") as Map<String, Any?>?
        @Suppress("UNCHECKED_CAST") val tel = decoded?.get("telemetry") as Map<String, Any?>?
        val n = LoraNode(
            key, protocol, c["label"] as String, addr,
            name = (user?.get("long_name") as String?) ?: node[1] ?: old?.name,
            kind = node[2] ?: old?.kind,
            packets = (old?.packets ?: 0) + 1, rssi = rx.rssi, snr = rx.snr, lastSeen = now, profile = profile,
            hardware = user?.get("hardware") as String? ?: old?.hardware,
            role = user?.get("role") as String? ?: old?.role,
            lat = (pos?.get("lat") as Double?) ?: old?.lat, lon = (pos?.get("lon") as Double?) ?: old?.lon,
            lastMessage = (decoded?.get("text") as String?) ?: old?.lastMessage,
            telemetry = tel?.entries?.joinToString(" · ") { "${it.key.replace('_', ' ')} ${it.value}" } ?: old?.telemetry,
        )
        _nodes.value = (_nodes.value.filter { it.key != key } + n).sortedByDescending { it.lastSeen }.take(MAX_NODES)
    }

    private fun summary(d: Map<String, Any?>?): String? {
        d ?: return null
        @Suppress("UNCHECKED_CAST")
        return when {
            d["text"] != null -> "“${d["text"]}”"
            d["user"] != null -> (d["user"] as Map<String, Any?>).values.joinToString(" · ")
            d["position"] != null -> (d["position"] as Map<String, Any?>).let { "position ${it["lat"] ?: "?"}, ${it["lon"] ?: "?"}" }
            d["telemetry"] != null -> (d["telemetry"] as Map<String, Any?>).entries.joinToString(" · ") { "${it.key.replace('_', ' ')} ${it.value}" }
            d["waypoint"] != null -> "waypoint " + (d["waypoint"] as Map<String, Any?>)["name"]
            else -> d["port_name"] as String?
        }
    }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val answered = CompletableDeferred<Boolean>()
        val action = "${app.packageName}.USB_PERMISSION_LORA"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                answered.complete(manager.hasPermission(device))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(app, 3, Intent(action).setPackage(app.packageName), flags))
            return answered.await()
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    companion object {
        /** USB serial chips on LoRa boards: the Heltec V3's CP2102, CH9102/CH340 boards, and the ESP32-S3's own USB. */
        val CANDIDATES = listOf(0x10C4 to 0xEA60, 0x1A86 to 0x55D4, 0x1A86 to 0x7523, 0x303A to 0x1001)
        private val ZIGBEE_NAMES = listOf("zigbee", "sonoff", "skyconnect", "zbt-", "conbee", "thread")
        private const val RETRY_MS = 30_000L
        private const val MAX_FRAMES = 200
        private const val MAX_NODES = 500
    }
}
