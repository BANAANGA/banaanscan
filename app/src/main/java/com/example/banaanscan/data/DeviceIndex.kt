package com.example.banaanscan.data

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.banaanscan.zigbee.SniffedDevice
import com.example.banaanscan.zigbee.ZigbeeNetwork
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Persistent index of every device the app has ever seen (LAN hosts, Bluetooth devices
 * and Wi-Fi access points), stored as JSON in app-private storage.
 */
class DeviceIndex(context: Context) {

    enum class Kind(val label: String) { LAN("Network"), BLUETOOTH("Bluetooth"), WIFI("Wi-Fi AP"), ZIGBEE("Zigbee"), RADIO("802.15.4") }

    data class Entry(
        val key: String,
        val kind: Kind,
        val name: String,
        val address: String?,
        val ip: String?,
        val vendor: String?,
        val type: DeviceType,
        val firstSeen: Long,
        val lastSeen: Long,
        val sightings: Int,
        val network: String?,
        val details: String?,
    )

    private val file = File(context.filesDir, "device_index.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val map = LinkedHashMap<String, Entry>()
    private val _entries = MutableStateFlow<List<Entry>>(emptyList())
    val entries: StateFlow<List<Entry>> = _entries.asStateFlow()
    private var saveJob: Job? = null

    suspend fun load() = withContext(Dispatchers.IO) {
        val loaded = runCatching {
            if (!file.exists()) return@runCatching emptyList()
            val arr = JSONArray(file.readText())
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        }.getOrDefault(emptyList())
        synchronized(map) { loaded.forEach { map[it.key] = it } }
        publish()
    }

    fun recordLan(devices: List<LanDevice>, network: String?) {
        if (devices.isEmpty()) return
        for (d in devices) {
            val key = d.mac?.let { "mac:${OuiDatabase.normalize(it)}" } ?: "ip:${network ?: "?"}:${d.ip}"
            val details = buildList {
                if (d.openPorts.isNotEmpty()) add("ports " + d.openPorts.sorted().joinToString(","))
                if (d.mdnsServices.isNotEmpty()) add(d.mdnsServices.joinToString(","))
                d.ssdp?.modelName?.let { add(it) }
            }.joinToString(" · ").ifBlank { null }
            upsert(key, Kind.LAN, d.displayName, d.mac, d.ip, d.vendor, d.guess.type, network, details)
        }
        commit()
    }

    fun recordBt(devices: Collection<BtDevice>) {
        if (devices.isEmpty()) return
        for (d in devices) {
            val details = buildList {
                if (d.le) add("BLE")
                if (d.classic) add("Classic")
                if (d.bonded) add("Paired")
                if (d.randomAddress) add("random address")
            }.joinToString(" · ")
            upsert("bt:${d.address}", Kind.BLUETOOTH, d.name ?: "", d.address, null,
                d.vendor.takeIf { d.vendorReliable }, d.guess.type, null, details)
        }
        commit()
    }

    fun recordWifi(networks: List<WifiNetwork>) {
        if (networks.isEmpty()) return
        for (n in networks) {
            upsert("ap:${n.bssid}", Kind.WIFI, n.ssid, n.bssid, null, n.vendor, n.guess.type, n.ssid,
                "${n.band} ch ${n.channel} · ${n.security}")
        }
        commit()
    }

    fun recordZigbee(networks: List<ZigbeeNetwork>) {
        if (networks.isEmpty()) return
        for (n in networks) {
            val details = buildList {
                add("ch ${n.channel}")
                if (n.permitJoining) add("open for joining")
                if (n.routers.size > 1) add("${n.routers.size} routers")
            }.joinToString(" · ")
            upsert("zigbee:${n.extendedPanId}", Kind.ZIGBEE, "PAN 0x%04X".format(n.panId), n.extendedPanId, null,
                n.possibleVendor, DeviceType.SMART_HOME, null, details)
        }
        commit()
    }

    fun recordSniffed(devices: List<SniffedDevice>) {
        if (devices.isEmpty()) return
        for (d in devices) {
            val details = listOfNotNull(
                d.pan?.let { "PAN 0x%04X".format(it) },
                "ch " + d.channels.sorted().joinToString(","),
                d.hint.label,
            ).joinToString(" · ")
            upsert("radio:${d.key}", Kind.RADIO, d.address.text, d.address.extended, null,
                d.possibleVendor, DeviceType.UNKNOWN, null, details)
        }
        commit()
    }

    fun clear() {
        synchronized(map) { map.clear() }
        commit()
    }

    fun toCsv(): String {
        val header = "kind,name,type,address,ip,vendor,network,first_seen,last_seen,sightings,details"
        fun esc(s: Any?): String {
            val v = s?.toString() ?: ""
            return if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        }
        val rows = entries.value.map { e ->
            listOf(e.kind.label, e.name, e.type.label, e.address, e.ip, e.vendor, e.network,
                java.time.Instant.ofEpochMilli(e.firstSeen), java.time.Instant.ofEpochMilli(e.lastSeen),
                e.sightings, e.details).joinToString(",") { esc(it) }
        }
        return (listOf(header) + rows).joinToString("\n")
    }

    private fun upsert(
        key: String, kind: Kind, name: String, address: String?, ip: String?, vendor: String?,
        type: DeviceType, network: String?, details: String?,
    ) = synchronized(map) {
        val now = System.currentTimeMillis()
        val old = map[key]
        // A new "sighting" counts only after the device has been absent for a while.
        val sightings = when {
            old == null -> 1
            now - old.lastSeen > SIGHTING_GAP_MS -> old.sightings + 1
            else -> old.sightings
        }
        val isGenericName = name.isBlank() || name == ip
        map[key] = Entry(
            key = key,
            kind = kind,
            name = if (isGenericName) old?.name?.takeIf { it.isNotBlank() } ?: name else name,
            address = address ?: old?.address,
            ip = ip ?: old?.ip,
            vendor = vendor ?: old?.vendor,
            type = if (type == DeviceType.UNKNOWN) old?.type ?: type else type,
            firstSeen = old?.firstSeen ?: now,
            lastSeen = now,
            sightings = sightings,
            network = network ?: old?.network,
            details = details ?: old?.details,
        )
    }

    private fun commit() {
        publish()
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(1500)
            save()
        }
    }

    private fun publish() {
        _entries.value = synchronized(map) { map.values.sortedByDescending { it.lastSeen } }
    }

    private fun save() {
        val snapshot = synchronized(map) { map.values.toList() }
        val arr = JSONArray()
        snapshot.forEach { arr.put(toJson(it)) }
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(file)
    }

    private fun toJson(e: Entry) = JSONObject().apply {
        put("key", e.key); put("kind", e.kind.name); put("name", e.name)
        put("address", e.address); put("ip", e.ip); put("vendor", e.vendor)
        put("type", e.type.name); put("firstSeen", e.firstSeen); put("lastSeen", e.lastSeen)
        put("sightings", e.sightings); put("network", e.network); put("details", e.details)
    }

    private fun fromJson(o: JSONObject): Entry {
        fun str(k: String): String? = if (o.isNull(k)) null else o.optString(k)
        return Entry(
            key = o.getString("key"),
            kind = runCatching { Kind.valueOf(o.getString("kind")) }.getOrDefault(Kind.LAN),
            name = o.optString("name"),
            address = str("address"),
            ip = str("ip"),
            vendor = str("vendor"),
            type = runCatching { DeviceType.valueOf(o.getString("type")) }.getOrDefault(DeviceType.UNKNOWN),
            firstSeen = o.optLong("firstSeen"),
            lastSeen = o.optLong("lastSeen"),
            sightings = o.optInt("sightings", 1),
            network = str("network"),
            details = str("details"),
        )
    }

    private companion object {
        const val SIGHTING_GAP_MS = 10 * 60_000L
    }
}
