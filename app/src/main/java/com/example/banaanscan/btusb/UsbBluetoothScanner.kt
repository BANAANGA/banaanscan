package com.example.banaanscan.btusb

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.example.banaanscan.bthci.Advert
import com.example.banaanscan.bthci.Hci
import com.example.banaanscan.bthci.HciException
import com.example.banaanscan.bthci.HciIo
import com.example.banaanscan.bthci.InquiryComplete
import com.example.banaanscan.bthci.InquiryResponse
import com.example.banaanscan.bthci.Scanner
import com.example.banaanscan.bthci.hex
import com.example.banaanscan.classify.DeviceClassifier
import com.example.banaanscan.data.BtDevice
import com.example.banaanscan.data.OuiDatabase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** HCI over Android's USB host API: commands on the control endpoint, events from the interrupt endpoint. */
private class AndroidHciIo(private val connection: UsbDeviceConnection, private val iface: UsbInterface, private val events: UsbEndpoint) : HciIo {
    override fun sendCommand(packet: ByteArray, timeoutMs: Int) {
        val n = connection.controlTransfer(0x20, 0, 0, iface.id, packet, packet.size, timeoutMs)
        if (n < 0) throw HciException("Sending a command to the adapter failed")
    }

    /** Android reports a timeout and an error the same way (-1), so both count as "nothing yet". */
    override fun readEvents(buffer: ByteArray, timeoutMs: Int): Int =
        connection.bulkTransfer(events, buffer, buffer.size, timeoutMs).coerceAtLeast(0)
}

/** A USB Bluetooth adapter: any device with the Bluetooth controller interface (class E0, subclass 01, protocol 01). */
class BtAdapter(val device: UsbDevice, val iface: UsbInterface, val label: String)

/**
 * Bluetooth scanning through a USB adapter, with BanaanScan speaking HCI to it directly (no root, and the phone's own
 * Bluetooth isn't involved). Realtek RTL8761BU adapters (TP-Link UB500) get their firmware from assets/rtl_bt, under
 * Realtek's licence (LICENCE.rtlwifi_firmware.txt there).
 */
class UsbBluetoothScanner(context: Context) {
    private val app = context.applicationContext
    private val usb: UsbManager? = app.getSystemService(UsbManager::class.java)
    val logDir = File(app.filesDir, "bthci")
    /** Readable after a session with `adb shell run-as io.github.banaanga.banaanscan.debug cat files/bthci/log.txt`. */
    val logFile get() = File(logDir, "log.txt")

    val adapter = MutableStateFlow<BtAdapter?>(null)
    val running = MutableStateFlow(false)
    val status = MutableStateFlow<String?>(null)
    val error = MutableStateFlow<String?>(null)
    val packets = MutableStateFlow(0)
    private val _devices = MutableStateFlow<Map<String, BtDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BtDevice>> = _devices.asStateFlow()

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
        adapter.value = usb?.deviceList?.values?.firstNotNullOfOrNull { d ->
            (0 until d.interfaceCount).map(d::getInterface).firstOrNull {
                it.interfaceClass == UsbConstants.USB_CLASS_WIRELESS_CONTROLLER && it.interfaceSubclass == 1 && it.interfaceProtocol == 1
            }?.let { BtAdapter(d, it, KNOWN[d.vendorId to d.productId] ?: (d.productName ?: "USB Bluetooth adapter")) }
        }
    }

    fun close() {
        runCatching { app.unregisterReceiver(hotplug) }
    }

    fun clear() {
        _devices.value = emptyMap()
        packets.value = 0
    }

    /** [passive] only listens; otherwise BLE scan requests and classic inquiry are sent, which finds more names. */
    suspend fun run(passive: Boolean) {
        val a = adapter.value ?: return
        running.value = true
        error.value = null
        logDir.mkdirs()
        val log = PrintWriter(logFile.outputStream().bufferedWriter(), true)
        fun note(msg: String) {
            log.println(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + msg)
            status.value = msg
        }
        try {
            note("Waiting for USB permission…")
            if (!ensurePermission(a.device)) throw HciException("USB permission was denied.")
            withContext(Dispatchers.IO) { scan(a, passive, ::note) { log.println(SimpleDateFormat("HH:mm:ss.SSS", Locale.ROOT).format(Date()) + " " + it) } }
        } catch (e: CancellationException) {
            note("Stopped")
            throw e
        } catch (e: Exception) {
            note("Failed: ${e.message}")
            error.value = e.message ?: e.toString()
        } finally {
            running.value = false
            log.close()
        }
    }

    /** [note] also shows the message as the status line; [log] only writes it to the log. */
    private suspend fun scan(a: BtAdapter, passive: Boolean, note: (String) -> Unit, log: (String) -> Unit) {
        val manager = usb ?: throw HciException("This phone has no USB host support.")
        val connection = manager.openDevice(a.device) ?: throw HciException("Couldn't open the adapter.")
        try {
            if (!connection.claimInterface(a.iface, true)) throw HciException("Couldn't claim the adapter's USB interface.")
            val events = (0 until a.iface.endpointCount).map(a.iface::getEndpoint).firstOrNull {
                it.type == UsbConstants.USB_ENDPOINT_XFER_INT && it.direction == UsbConstants.USB_DIR_IN
            } ?: throw HciException("The adapter has no event endpoint.")
            note("Starting the adapter…")
            val scanner = Scanner(Hci(AndroidHciIo(connection, a.iface, events)), note)
            val info = scanner.open(::firmware)
            note("Ready: ${info.firmware ?: "firmware already running"}, address ${info.address}" +
                if (info.codedPhy) ", long range supported" else "")
            scanner.startLe(active = !passive, coded = info.codedPhy)
            val inquiry = !passive && info.classic
            if (inquiry) scanner.startInquiry()
            status.value = if (passive) "Listening (nothing is transmitted)" else "Scanning" + if (inquiry) " (BLE + classic)" else ""
            val found = HashMap(_devices.value)
            var count = packets.value
            var lastPublish = 0L
            var lastLog = System.currentTimeMillis()
            try {
                while (true) {
                    currentCoroutineContext().ensureActive()
                    for (h in scanner.poll(200)) {
                        when (h) {
                            is Advert -> { count++; found.merge(h) }
                            is InquiryResponse -> { count++; found.merge(h) }
                            InquiryComplete -> if (inquiry) scanner.startInquiry()
                        }
                    }
                    if (System.currentTimeMillis() - lastPublish > 500) {
                        lastPublish = System.currentTimeMillis()
                        _devices.value = HashMap(found)
                        packets.value = count
                    }
                    if (System.currentTimeMillis() - lastLog > 10_000) {
                        lastLog = System.currentTimeMillis()
                        log("$count packets from ${found.size} devices so far")
                    }
                }
            } finally {
                _devices.value = HashMap(found)
                packets.value = count
                log("$count packets from ${found.size} devices (${found.values.count { it.name != null }} named, " +
                    "${found.values.count { it.classic }} classic, ${found.values.count { "Coded" in it.phys }} long range)")
                runCatching { scanner.stop() }
            }
        } finally {
            runCatching { connection.releaseInterface(a.iface) }
            connection.close()
        }
    }

    private fun firmware(name: String): ByteArray? =
        runCatching { app.assets.open("rtl_bt/$name").use { it.readBytes() } }.getOrNull()

    private fun HashMap<String, BtDevice>.merge(a: Advert) {
        val ad = a.ad
        update(a.address, a.random) { old ->
            old.copy(
                name = ad.name?.takeIf { ad.completeName || old.name == null } ?: old.name,
                rssi = a.rssi ?: old.rssi,
                le = true,
                manufacturerData = old.manufacturerData + ad.manufacturer.mapValues { it.value.hex().uppercase() },
                serviceUuids = (old.serviceUuids + ad.uuids + ad.serviceData.keys).distinct(),
                txPower = ad.txPower ?: a.txPower ?: old.txPower,
                connectable = if (a.scanResponse) old.connectable else a.connectable,
                phys = old.phys + PHYS.getOrElse(a.primaryPhy) { "?" } + listOfNotNull(PHYS.getOrNull(a.secondaryPhy)?.takeIf { a.secondaryPhy > 0 }),
            )
        }
    }

    private fun HashMap<String, BtDevice>.merge(r: InquiryResponse) {
        update(r.address, random = false) { old ->
            old.copy(
                name = r.eir?.name ?: old.name,
                rssi = r.rssi ?: old.rssi,
                classic = true,
                // Same values as Android's BluetoothClass.getMajorDeviceClass() and getDeviceClass().
                majorClass = r.classOfDevice and 0x1F00,
                deviceClass = r.classOfDevice and 0x1FFC,
                serviceUuids = (old.serviceUuids + r.eir?.uuids.orEmpty()).distinct(),
                manufacturerData = old.manufacturerData + r.eir?.manufacturer.orEmpty().mapValues { it.value.hex().uppercase() },
                txPower = r.eir?.txPower ?: old.txPower,
            )
        }
    }

    private inline fun HashMap<String, BtDevice>.update(address: String, random: Boolean, change: (BtDevice) -> BtDevice) {
        val old = this[address] ?: BtDevice(address)
        val d = change(old).copy(
            // The adapter reports the address type, which is better than guessing from the address bits.
            randomAddress = random,
            vendor = if (random) null else OuiDatabase.lookup(address),
            packets = old.packets + 1,
            lastSeen = System.currentTimeMillis(),
        )
        this[address] = d.copy(guess = DeviceClassifier.classifyBt(d))
    }

    private suspend fun ensurePermission(device: UsbDevice): Boolean {
        val manager = usb ?: return false
        if (manager.hasPermission(device)) return true
        val answered = CompletableDeferred<Boolean>()
        val action = "${app.packageName}.BT_ADAPTER_PERMISSION"
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                answered.complete(manager.hasPermission(device))
            }
        }
        ContextCompat.registerReceiver(app, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val flags = if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0
            manager.requestPermission(device, PendingIntent.getBroadcast(app, 2, Intent(action).setPackage(app.packageName), flags))
            return answered.await()
        } finally {
            runCatching { app.unregisterReceiver(receiver) }
        }
    }

    private companion object {
        val PHYS = listOf("", "1M", "2M", "Coded")
        val KNOWN = mapOf((0x2357 to 0x0604) to "TP-Link UB500")
    }
}
