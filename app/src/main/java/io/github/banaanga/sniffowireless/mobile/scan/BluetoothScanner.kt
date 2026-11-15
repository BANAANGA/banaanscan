package io.github.banaanga.sniffowireless.mobile.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Parcelable
import androidx.core.content.ContextCompat
import io.github.banaanga.sniffowireless.mobile.classify.DeviceClassifier
import io.github.banaanga.sniffowireless.mobile.core.Ble
import io.github.banaanga.sniffowireless.mobile.core.BtEvent
import io.github.banaanga.sniffowireless.mobile.core.BtObserver
import io.github.banaanga.sniffowireless.mobile.sensor.Hub
import io.github.banaanga.sniffowireless.mobile.data.BtDevice
import io.github.banaanga.sniffowireless.mobile.data.OuiDatabase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Runs BLE scanning and classic discovery side by side and merges results per address. Several owners (the Bluetooth
 * tab, sensor mode) can want it running; it stops when the last one lets go. In sensor mode every advert and inquiry
 * answer also becomes a sniffowireless "bt" observation.
 */
@SuppressLint("MissingPermission")
class BluetoothScanner(context: Context, private val hub: Hub? = null) {
    private val app = context.applicationContext
    private val owners = HashSet<String>()
    private val observer = BtObserver()
    private val adapter: BluetoothAdapter? = app.getSystemService(BluetoothManager::class.java)?.adapter
    private val found = ConcurrentHashMap<String, BtDevice>()

    private val _devices = MutableStateFlow<Map<String, BtDevice>>(emptyMap())
    val devices: StateFlow<Map<String, BtDevice>> = _devices.asStateFlow()
    val scanning = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val isAvailable: Boolean get() = adapter != null
    val isEnabled: Boolean get() = adapter?.isEnabled == true

    private val leCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = onLe(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::onLe)
        override fun onScanFailed(errorCode: Int) {
            error.value = "BLE scan failed (code $errorCode)"
        }
    }

    private val classicReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    val dev = intent.parcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
                    val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                    val cls = intent.parcelable<BluetoothClass>(BluetoothDevice.EXTRA_CLASS)
                    onClassic(dev, rssi.takeIf { it != Short.MIN_VALUE.toInt() }, cls)
                }
                // Classic inquiry only lasts ~12 s; keep it going while the user is scanning.
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED ->
                    if (scanning.value) runCatching { adapter?.startDiscovery() }
            }
        }
    }

    @Synchronized
    fun start(owner: String = "ui") {
        val a = adapter ?: run { error.value = "This device has no Bluetooth"; return }
        if (!a.isEnabled) { error.value = "Bluetooth is off"; return }
        owners += owner
        if (scanning.value) return
        error.value = null
        try {
            ContextCompat.registerReceiver(
                app, classicReceiver,
                IntentFilter().apply {
                    addAction(BluetoothDevice.ACTION_FOUND)
                    addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
                },
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            scanning.value = true
            a.bondedDevices?.forEach { onClassic(it, null, it.bluetoothClass, bonded = true) }
            a.startDiscovery()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .apply { if (Build.VERSION.SDK_INT >= 26) setLegacy(false).setPhy(ScanSettings.PHY_LE_ALL_SUPPORTED) }.build()
            // Android stops unfiltered scans while the screen is off; a filter that matches everything keeps it going.
            a.bluetoothLeScanner?.startScan(listOf(ScanFilter.Builder().build()), settings, leCallback)
        } catch (e: SecurityException) {
            error.value = "Bluetooth permission denied"
            stop()
        }
    }

    @Synchronized
    fun stop(owner: String = "ui") {
        owners -= owner
        if (owners.isNotEmpty() || !scanning.value) return
        scanning.value = false
        runCatching { adapter?.bluetoothLeScanner?.stopScan(leCallback) }
        runCatching { adapter?.cancelDiscovery() }
        runCatching { app.unregisterReceiver(classicReceiver) }
    }

    fun clear() {
        found.clear()
        _devices.value = emptyMap()
    }

    private fun onLe(result: ScanResult) {
        hub?.takeIf { it.sending }?.let { sendLe(it, result) }
        val dev = result.device
        val rec = result.scanRecord
        val manufacturer = buildMap {
            rec?.manufacturerSpecificData?.let { sa ->
                for (i in 0 until sa.size()) put(sa.keyAt(i), sa.valueAt(i)?.toHex().orEmpty())
            }
        }
        val uuids = buildList {
            rec?.serviceUuids?.forEach { add(shortUuid(it.uuid)) }
            rec?.serviceData?.keys?.forEach { add(shortUuid(it.uuid)) }
        }
        val cls = runCatching { dev.bluetoothClass }.getOrNull()
        val bonded = runCatching { dev.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        upsert(dev.address) { old ->
            old.copy(
                name = rec?.deviceName ?: safeName(dev) ?: old.name,
                rssi = result.rssi,
                le = true,
                bonded = old.bonded || bonded,
                majorClass = old.majorClass ?: cls?.majorDeviceClass,
                deviceClass = old.deviceClass ?: cls?.deviceClass,
                manufacturerData = old.manufacturerData + manufacturer,
                serviceUuids = (old.serviceUuids + uuids).distinct(),
                txPower = rec?.txPowerLevel?.takeIf { it != Int.MIN_VALUE } ?: old.txPower,
                connectable = result.isConnectable,
            )
        }
    }

    /** The advert as sniffowireless's parser would see it from an HCI report, then its "bt" observation. */
    private fun sendLe(hub: Hub, result: ScanResult) {
        val mac = result.device.address.lowercase()
        val addrType = when {
            Build.VERSION.SDK_INT >= 35 -> if (result.device.addressType == BluetoothDevice.ADDRESS_TYPE_PUBLIC) 0 else 1
            // Older Android doesn't say; an address with a registered maker prefix is most likely public.
            OuiDatabase.isLocallyAdministered(mac) -> 1
            else -> if (OuiDatabase.lookup(mac) != null) 0 else 1
        }
        val legacy = Build.VERSION.SDK_INT < 26 || result.isLegacy
        val phyCode = if (Build.VERSION.SDK_INT >= 26) result.secondaryPhy.takeIf { it != ScanResult.PHY_UNUSED } ?: result.primaryPhy else 1
        val ev = BtEvent("adv", mac, addrType, result.rssi.takeIf { it < 20 && it != 127 },
            connectable = Build.VERSION.SDK_INT < 26 || result.isConnectable, ad = Ble.parseAd(result.scanRecord?.bytes ?: ByteArray(0)),
            ext = !legacy, phy = mapOf(1 to "1M", 2 to "2M", 3 to "Coded")[phyCode])
        observer.observe(ev, hub.now())?.let(hub.sink::put)
        hub.status.update(STATUS, "adverts" to observer.adverts, "packets" to observer.adverts + observer.inquiries)
    }

    private fun sendClassic(hub: Hub, dev: BluetoothDevice, rssi: Int?, cls: BluetoothClass?) {
        // BluetoothClass.hashCode() is the whole class-of-device value (service bits included).
        val ad = safeName(dev)?.let { mapOf<String, Any?>("name" to it) } ?: emptyMap()
        val ev = BtEvent("inq", dev.address.lowercase(), rssi = rssi, cod = cls?.hashCode() ?: 0, ad = ad)
        observer.observe(ev, hub.now())?.let(hub.sink::put)
        hub.status.update(STATUS, "inquiries" to observer.inquiries, "packets" to observer.adverts + observer.inquiries)
    }

    /** Sensor mode: scan until cancelled, reported on the server's Sensors page as the phone's adapter. */
    suspend fun runForSensor() {
        val h = hub ?: return
        h.status.register(STATUS, "type" to "bluetooth", "iface" to "phone", "state" to "scanning", "chip" to "Android (${Build.MANUFACTURER} ${Build.MODEL})".take(40),
            "scan" to "extended", "adverts" to 0, "inquiries" to 0,
            "note" to "The phone's own Bluetooth: Android filters and merges adverts, so a USB adapter hears more")
        try {
            start(SENSOR_OWNER)
            if (!scanning.value) h.status.update(STATUS, "state" to "error", "error" to (error.value ?: "Bluetooth didn't start"))
            kotlinx.coroutines.awaitCancellation()
        } finally {
            stop(SENSOR_OWNER)
            h.status.remove(STATUS)
        }
    }

    private fun onClassic(dev: BluetoothDevice, rssi: Int?, cls: BluetoothClass?, bonded: Boolean = false) {
        if (!bonded) hub?.takeIf { it.sending }?.let { sendClassic(it, dev, rssi, cls) }
        upsert(dev.address) { old ->
            old.copy(
                name = safeName(dev) ?: old.name,
                rssi = rssi ?: old.rssi,
                classic = old.classic || !bonded || dev.type != BluetoothDevice.DEVICE_TYPE_LE,
                le = old.le || dev.type == BluetoothDevice.DEVICE_TYPE_LE || dev.type == BluetoothDevice.DEVICE_TYPE_DUAL,
                bonded = old.bonded || bonded || dev.bondState == BluetoothDevice.BOND_BONDED,
                majorClass = cls?.majorDeviceClass ?: old.majorClass,
                deviceClass = cls?.deviceClass ?: old.deviceClass,
            )
        }
    }

    private fun upsert(address: String, change: (BtDevice) -> BtDevice) {
        found.compute(address) { _, old ->
            val d = change(old ?: BtDevice(address))
            val random = OuiDatabase.isLocallyAdministered(address)
            val withVendor = d.copy(
                vendor = OuiDatabase.lookup(address),
                randomAddress = random,
                lastSeen = System.currentTimeMillis(),
            )
            withVendor.copy(guess = DeviceClassifier.classifyBt(withVendor))
        }
        _devices.value = HashMap(found)
    }

    private fun safeName(dev: BluetoothDevice): String? =
        try { dev.name } catch (e: SecurityException) { null }

    companion object {
        const val STATUS = "bt:phone"
        const val SENSOR_OWNER = "sensor"
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        private fun shortUuid(uuid: UUID): String {
            val s = uuid.toString().lowercase()
            return if (s.startsWith("0000") && s.endsWith(BASE_UUID_SUFFIX)) s.substring(4, 8) else s
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02X".format(it.toInt() and 0xFF) }

        private inline fun <reified T : Parcelable> Intent.parcelable(key: String): T? =
            if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java)
            else @Suppress("DEPRECATION") getParcelableExtra(key)
    }
}
