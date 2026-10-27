package com.example.banaanscan.data

enum class DeviceType(val label: String) {
    ROUTER("Router / Access point"),
    PHONE("Phone"),
    TABLET("Tablet"),
    COMPUTER("Computer"),
    SERVER("Server / NAS"),
    PRINTER("Printer / Scanner"),
    TV("Smart TV"),
    STREAMER("Media streamer"),
    SPEAKER("Speaker / Audio"),
    HEADPHONES("Headphones / Earbuds"),
    CAMERA("IP camera"),
    SMART_HOME("Smart home / IoT"),
    GAME_CONSOLE("Game console"),
    WEARABLE("Wearable"),
    TRACKER("Tracker / Beacon"),
    INPUT("Keyboard / Mouse / Controller"),
    UNKNOWN("Unknown"),
}

/** Result of the device-type heuristics: best type, 0-100 confidence and the evidence used. */
data class Guess(val type: DeviceType, val confidence: Int, val reasons: List<String>) {
    companion object {
        val UNKNOWN = Guess(DeviceType.UNKNOWN, 0, listOf("No identifying signals yet"))
    }
}

data class WifiNetwork(
    val ssid: String,
    val bssid: String,
    val rssi: Int,
    val frequency: Int,
    val channel: Int,
    val band: String,
    val security: String,
    val standard: String?,
    val channelWidthMhz: Int?,
    val vendor: String?,
    val randomizedBssid: Boolean,
    val isConnected: Boolean,
    val guess: Guess,
    val lastSeen: Long = System.currentTimeMillis(),
)

data class BtDevice(
    val address: String,
    val name: String? = null,
    val rssi: Int? = null,
    val le: Boolean = false,
    val classic: Boolean = false,
    val bonded: Boolean = false,
    val majorClass: Int? = null,
    val deviceClass: Int? = null,
    /** BLE company identifier -> manufacturer payload as hex. */
    val manufacturerData: Map<Int, String> = emptyMap(),
    /** 16-bit UUIDs as 4 hex chars, longer ones as full UUID strings. */
    val serviceUuids: List<String> = emptyList(),
    val txPower: Int? = null,
    val connectable: Boolean? = null,
    val vendor: String? = null,
    val randomAddress: Boolean = false,
    val guess: Guess = Guess.UNKNOWN,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    /** OUI lookups only mean something for public addresses, which LE-only devices rarely use. */
    val vendorReliable: Boolean get() = !randomAddress && (classic || bonded)
}

data class SsdpInfo(
    val server: String? = null,
    val location: String? = null,
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val deviceType: String? = null,
    val searchTargets: Set<String> = emptySet(),
)

data class LanDevice(
    val ip: String,
    val mac: String? = null,
    val macSource: String? = null,
    val vendor: String? = null,
    val note: String? = null,
    val hostname: String? = null,
    val netbiosName: String? = null,
    val netbiosGroup: String? = null,
    val openPorts: Set<Int> = emptySet(),
    val mdnsServices: Set<String> = emptySet(),
    val mdnsNames: Set<String> = emptySet(),
    val mdnsHosts: Set<String> = emptySet(),
    val mdnsTxt: Map<String, String> = emptyMap(),
    val ssdp: SsdpInfo? = null,
    val httpServer: String? = null,
    val httpTitle: String? = null,
    val isGateway: Boolean = false,
    val isSelf: Boolean = false,
    val guess: Guess = Guess.UNKNOWN,
    val lastSeen: Long = System.currentTimeMillis(),
) {
    val displayName: String
        get() = ssdp?.friendlyName
            ?: mdnsNames.firstOrNull()
            ?: netbiosName
            ?: mdnsHosts.firstOrNull()
            ?: hostname
            ?: when {
                isSelf -> "This device"
                isGateway -> "Gateway"
                else -> ip
            }
}
