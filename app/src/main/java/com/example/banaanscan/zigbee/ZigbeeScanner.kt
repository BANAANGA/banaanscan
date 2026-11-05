package com.example.banaanscan.zigbee

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.banaanscan.data.OuiDatabase
import com.hoho.android.usbserial.driver.CdcAcmSerialDriver
import com.hoho.android.usbserial.driver.Ch34xSerialDriver
import com.hoho.android.usbserial.driver.Cp21xxSerialDriver
import com.hoho.android.usbserial.driver.ProbeTable
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException

/** A USB serial device that might be a Zigbee coordinator. */
data class UsbDongle(
    val device: UsbDevice?,
    val id: String,
    val name: String,
    val blocker: String? = null,
    val ezspFirst: Boolean = false,
)

private class KnownDongle(
    val vendor: Int,
    val product: Int,
    val name: String,
    val blocker: String? = null,
    val ezspFirst: Boolean = false,
)

/** Keep in sync with res/xml/zigbee_dongles.xml. */
private val KNOWN = listOf(
    KnownDongle(0x10C4, 0xEA60, "CP210x USB stick (ZBDongle-P, SkyConnect, ZBT-1…)"),
    KnownDongle(0x1A86, 0x55D4, "CH9102 USB stick (ZBDongle-E…)", ezspFirst = true),
    KnownDongle(0x1A86, 0x7523, "CH340 USB stick"),
    KnownDongle(0x0451, 0x16A8, "TI CC2531 stick"),
    KnownDongle(0x0451, 0xBEF3, "TI LaunchPad"),
    KnownDongle(0x1CF1, 0x0030, "ConBee II", "ConBee sticks use the deCONZ protocol, which BanaanScan doesn't support."),
)

private class UsbSerialLink(private val port: UsbSerialPort) : SerialLink {
    override fun write(data: ByteArray) = port.write(data, WRITE_TIMEOUT_MS)

    override fun read(buffer: ByteArray, timeoutMs: Int): Int = port.read(buffer, timeoutMs)

    override fun setPins(dtr: Boolean, rts: Boolean) {
        // Sticks without these lines ignore them, and some drivers refuse the call.
        try {
            port.dtr = dtr
            port.rts = rts
        } catch (e: UnsupportedOperationException) {
            return
        } catch (e: IOException) {
            return
        }
    }

    override fun close() = port.close()

    private companion object {
        const val WRITE_TIMEOUT_MS = 1_000
    }
}

class ZigbeeScanner(context: Context) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    private val prober = UsbSerialProber(
        UsbSerialProber.getDefaultProbeTable().also { table: ProbeTable ->
            // These aren't all in the library's table, and CDC devices must be named explicitly.
            table.addProduct(0x10C4, 0xEA60, Cp21xxSerialDriver::class.java)
            table.addProduct(0x1A86, 0x55D4, CdcAcmSerialDriver::class.java)
            table.addProduct(0x1A86, 0x7523, Ch34xSerialDriver::class.java)
            table.addProduct(0x0451, 0x16A8, CdcAcmSerialDriver::class.java)
            table.addProduct(0x0451, 0xBEF3, CdcAcmSerialDriver::class.java)
        },
    )

    private val _dongles = MutableStateFlow<List<UsbDongle>>(emptyList())
    val dongles: StateFlow<List<UsbDongle>> = _dongles.asStateFlow()
    private val _networks = MutableStateFlow<List<ZigbeeNetwork>>(emptyList())
    val networks: StateFlow<List<ZigbeeNetwork>> = _networks.asStateFlow()
    private val _energy = MutableStateFlow<List<ChannelEnergy>>(emptyList())
    val energy: StateFlow<List<ChannelEnergy>> = _energy.asStateFlow()
    val dongleInfo = MutableStateFlow<DongleInfo?>(null)
    val running = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)

    private val _sniffedDevices = MutableStateFlow<List<SniffedDevice>>(emptyList())
    val sniffedDevices: StateFlow<List<SniffedDevice>> = _sniffedDevices.asStateFlow()
    private val _channelActivity = MutableStateFlow<List<ChannelActivity>>(emptyList())
    val channelActivity: StateFlow<List<ChannelActivity>> = _channelActivity.asStateFlow()
    val currentChannel = MutableStateFlow<Int?>(null)
    val framesHeard = MutableStateFlow(0)
    val framesUndecoded = MutableStateFlow(0)

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
        // A simulated dongle has no USB device behind it, so plug events must not remove it.
        val simulated = _dongles.value.filter { it.device == null }
        _dongles.value = simulated + usb?.deviceList?.values.orEmpty().mapNotNull { device ->
            val known = KNOWN.firstOrNull { it.vendor == device.vendorId && it.product == device.productId }
            if (known == null && prober.probeDevice(device) == null) return@mapNotNull null
            UsbDongle(
                device = device,
                id = "%04X:%04X".format(device.vendorId, device.productId),
                name = known?.name ?: device.productName ?: "USB serial device",
                blocker = known?.blocker,
                ezspFirst = known?.ezspFirst == true,
            )
        }
    }

    suspend fun scan(dongle: UsbDongle) {
        val device = dongle.device ?: return
        if (dongle.blocker != null) {
            error.value = dongle.blocker
            return
        }
        withLifecycle {
            status.value = "Waiting for USB permission…"
            if (!ensurePermission(device)) throw ZigbeeException("USB permission was denied.")
            val driver = prober.probeDevice(device)
                ?: throw ZigbeeException("Android has no serial driver for this USB device.")
            withContext(Dispatchers.IO) { open(driver).use { runScan(it, dongle.ezspFirst) } }
        }
    }

    /** Listens until cancelled: on one channel, or hopping when [channel] is null. */
    suspend fun sniff(dongle: UsbDongle, channel: Int?) {
        val device = dongle.device ?: return
        if (dongle.blocker != null) {
            error.value = dongle.blocker
            return
        }
        withSniffLifecycle {
            status.value = "Waiting for USB permission…"
            if (!ensurePermission(device)) throw ZigbeeException("USB permission was denied.")
            val driver = prober.probeDevice(device)
                ?: throw ZigbeeException("Android has no serial driver for this USB device.")
            withContext(Dispatchers.IO) { open(driver).use { runSniff(it, channel, dongle.ezspFirst) } }
        }
    }

    suspend fun sniffSimulated(link: SerialLink, channel: Int?) = withSniffLifecycle {
        withContext(Dispatchers.IO) { link.use { runSniff(it, channel, false) } }
    }

    private suspend fun withSniffLifecycle(body: suspend () -> Unit) {
        running.value = true
        error.value = null
        dongleInfo.value = null
        _sniffedDevices.value = emptyList()
        _channelActivity.value = emptyList()
        framesHeard.value = 0
        framesUndecoded.value = 0
        try {
            body()
        } catch (e: CancellationException) {
            status.value = "Sniffer stopped"
            throw e
        } catch (e: ZigbeeException) {
            status.value = null
            error.value = e.message
        } catch (e: IOException) {
            status.value = null
            error.value = "Lost contact with the dongle: ${e.message}"
        } finally {
            running.value = false
            currentChannel.value = null
        }
    }

    private suspend fun runSniff(link: SerialLink, channel: Int?, ezspFirst: Boolean) {
        val driver = DongleDetector.connect(link, ezspFirst) { status.value = it }
            ?: throw ZigbeeException(UNRECOGNISED_DONGLE)
        dongleInfo.value = driver.info
        val capture = driver as? FrameCapture ?: throw ZigbeeException(
            "${driver.info.family.label} firmware has no capture mode. Sniffing needs a Silicon Labs stick such as the ZBDongle-E.",
        )
        val log = SnifferLog(vendorOf = { address ->
            address.takeUnless(OuiDatabase::isLocallyAdministered)?.let(OuiDatabase::lookup)
        })
        fun publish() = synchronized(log) {
            _sniffedDevices.value = log.devices()
            _channelActivity.value = log.channels()
            framesHeard.value = log.totalFrames
            framesUndecoded.value = log.undecodedFrames
        }
        val channels = channel?.let { listOf(it) } ?: (11..26).toList()
        coroutineScope {
            // On a timer, not per frame: a lone frame from a sleepy device must still show up.
            val publisher = launch {
                while (true) {
                    delay(PUBLISH_INTERVAL_MS)
                    publish()
                }
            }
            try {
                capture.capture(
                    channels,
                    HOP_DWELL_MS,
                    onChannel = {
                        currentChannel.value = it
                        status.value = if (channel == null) "Listening, now on channel $it (hopping)…" else "Listening on channel $it…"
                    },
                    onFrame = { frame -> synchronized(log) { log.add(frame) } },
                )
            } finally {
                publisher.cancel()
                publish()
            }
        }
    }

    /** Runs the whole flow against a simulated dongle in debug builds, for testing without hardware. */
    suspend fun scanSimulated(link: SerialLink) = withLifecycle {
        withContext(Dispatchers.IO) { link.use { runScan(it) } }
    }

    /** For debug builds: shows a simulated dongle in the list so the empty state can be replaced. */
    fun addSimulatedDongle(name: String) {
        _dongles.value = _dongles.value + UsbDongle(null, "simulated", name)
    }

    fun close() {
        runCatching { app.unregisterReceiver(hotplug) }
    }

    private suspend fun withLifecycle(body: suspend () -> Unit) {
        running.value = true
        error.value = null
        _networks.value = emptyList()
        _energy.value = emptyList()
        dongleInfo.value = null
        try {
            body()
            val count = _networks.value.size
            status.value = "Found $count Zigbee network" + if (count == 1) "" else "s"
        } catch (e: CancellationException) {
            status.value = "Scan stopped"
            throw e
        } catch (e: ZigbeeException) {
            status.value = null
            error.value = e.message
        } catch (e: IOException) {
            status.value = null
            error.value = "Lost contact with the dongle: ${e.message}"
        } finally {
            running.value = false
        }
    }

    private fun open(driver: UsbSerialDriver): SerialLink {
        val manager = usb ?: throw ZigbeeException("This device has no USB host support.")
        val connection = manager.openDevice(driver.device)
            ?: throw ZigbeeException("Couldn't open the USB device. Does the phone support USB-OTG?")
        val port = driver.ports.firstOrNull() ?: throw ZigbeeException("This USB device has no serial port.")
        port.open(connection)
        try {
            port.setParameters(BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
        } catch (e: Exception) {
            runCatching { port.close() }
            throw e
        }
        return UsbSerialLink(port)
    }

    private suspend fun runScan(link: SerialLink, ezspFirst: Boolean = false) {
        val driver = DongleDetector.connect(link, ezspFirst) { status.value = it }
            ?: throw ZigbeeException(UNRECOGNISED_DONGLE)
        dongleInfo.value = driver.info
        driver.scan(object : ScanListener {
            override fun onNetwork(network: ZigbeeNetwork) = merge(network)

            override fun onEnergy(energy: ChannelEnergy) {
                _energy.value = (_energy.value.filter { it.channel != energy.channel } + energy).sortedBy { it.channel }
            }

            override fun onProgress(message: String) {
                status.value = message
            }
        })
    }

    private fun merge(heard: ZigbeeNetwork) {
        val merged = heard.mergedWith(_networks.value.firstOrNull { it.key == heard.key }).copy(
            possibleVendor = heard.extendedPanId
                .takeUnless(OuiDatabase::isLocallyAdministered)
                ?.let(OuiDatabase::lookup),
        )
        _networks.value = (_networks.value.filter { it.key != heard.key } + merged)
            .sortedByDescending { it.lqi ?: 0 }
    }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val answered = CompletableDeferred<Boolean>()
        val action = "${app.packageName}.USB_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                answered.complete(manager.hasPermission(device))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            val intent = Intent(action).setPackage(app.packageName)
            manager.requestPermission(device, PendingIntent.getBroadcast(app, 0, intent, flags))
            return answered.await()
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        const val BAUD_RATE = 115_200
        const val HOP_DWELL_MS = 2_000L
        const val PUBLISH_INTERVAL_MS = 500L
        const val UNRECOGNISED_DONGLE = "This dongle didn't answer as a Z-Stack or EmberZNet coordinator. It may have " +
            "router, Thread or other firmware, or another app may be using it."
    }
}
