package com.example.banaanscan.classify

import android.bluetooth.BluetoothClass
import com.example.banaanscan.data.BtDevice
import com.example.banaanscan.data.DeviceType
import com.example.banaanscan.data.DeviceType.CAMERA
import com.example.banaanscan.data.DeviceType.COMPUTER
import com.example.banaanscan.data.DeviceType.GAME_CONSOLE
import com.example.banaanscan.data.DeviceType.HEADPHONES
import com.example.banaanscan.data.DeviceType.INPUT
import com.example.banaanscan.data.DeviceType.PHONE
import com.example.banaanscan.data.DeviceType.PRINTER
import com.example.banaanscan.data.DeviceType.ROUTER
import com.example.banaanscan.data.DeviceType.SERVER
import com.example.banaanscan.data.DeviceType.SMART_HOME
import com.example.banaanscan.data.DeviceType.SPEAKER
import com.example.banaanscan.data.DeviceType.STREAMER
import com.example.banaanscan.data.DeviceType.TABLET
import com.example.banaanscan.data.DeviceType.TRACKER
import com.example.banaanscan.data.DeviceType.TV
import com.example.banaanscan.data.DeviceType.WEARABLE
import com.example.banaanscan.data.Guess
import com.example.banaanscan.data.LanDevice
import java.util.EnumMap
import kotlin.math.roundToInt

/**
 * Heuristic device-type guesser. Every signal (MAC vendor, names, open ports, mDNS
 * services, UPnP descriptions, Bluetooth class/advertisements, ...) adds points to one
 * or more types; the highest score wins and the margin over the runner-up sets confidence.
 */
object DeviceClassifier {

    private class Scorer {
        private val points = EnumMap<DeviceType, Int>(DeviceType::class.java)
        private val notes = mutableListOf<Pair<DeviceType, String>>()

        fun add(type: DeviceType, pts: Int, why: String) {
            points.merge(type, pts) { a, b -> a + b }
            notes += type to "$why (+$pts)"
        }

        fun match(rules: List<Rule>, text: String?, source: String) {
            if (text.isNullOrBlank()) return
            for (rule in rules) {
                val m = rule.regex.find(text) ?: continue
                for ((type, pts) in rule.hits) add(type, pts, "$source matches \"${m.value}\"")
            }
        }

        fun guess(): Guess {
            val ranked = points.entries.sortedByDescending { it.value }
            val top = ranked.firstOrNull() ?: return Guess.UNKNOWN
            val second = ranked.getOrNull(1)?.value ?: 0
            val confidence = (minOf(top.value, 95) * top.value / (top.value + second).toFloat())
                .roundToInt().coerceIn(5, 95)
            val reasons = notes.filter { it.first == top.key }.map { it.second } +
                ranked.drop(1).take(2).map { "Also considered: ${it.key.label} (${it.value} pts)" }
            return Guess(top.key, confidence, reasons)
        }
    }

    private class Rule(pattern: String, val hits: List<Pair<DeviceType, Int>>) {
        val regex = Regex(pattern, RegexOption.IGNORE_CASE)
    }

    private fun rule(pattern: String, vararg hits: Pair<DeviceType, Int>) = Rule(pattern, hits.toList())

    // ---- MAC vendor (IEEE OUI owner) ----------------------------------------------------------

    private val VENDOR_RULES = listOf(
        rule("\\bapple\\b", PHONE to 12, COMPUTER to 8, TABLET to 6),
        rule("samsung", PHONE to 12, TV to 8),
        rule("xiaomi|oneplus|oppo|vivo mobile|realme|motorola|huawei device|honor device|nothing tech|fairphone|hmd global|sony mobile|zte corp", PHONE to 15),
        rule("\\bgoogle\\b", PHONE to 8, STREAMER to 8, SPEAKER to 6),
        rule("intel corp|liteon|azurewave|hon hai|foxconn|dell|lenovo|micro-star|gigabyte|asrock|framework comp|quanta|compal|wistron|pegatron", COMPUTER to 20),
        rule("realtek|qualcomm|mediatek|murata", COMPUTER to 5),
        rule("microsoft", COMPUTER to 15, GAME_CONSOLE to 8),
        rule("hewlett|hp inc", PRINTER to 15, COMPUTER to 12),
        rule("brother|canon|seiko epson|lexmark|xerox|kyocera|ricoh|konica|oki data|zebra tech", PRINTER to 35),
        rule("synology|qnap|western digital|asustor|terramaster|seagate|buffalo", SERVER to 35),
        rule("raspberry pi", SERVER to 15, SMART_HOME to 10),
        rule("tp-?link|netgear|ubiquiti|cisco|linksys|mikrotik|routerboard|\\bavm\\b|juniper|aruba|ruckus|arris|technicolor|sagemcom|zyxel|d-link|draytek|fortinet|eero|plume design|actiontec|askey|sercomm|cambium|cradlepoint|peplink|tenda|mercusys|edimax|asustek", ROUTER to 15),
        rule("espressif|tuya|shelly|allterco|itead|signify|philips lighting|lifx|meross|govee|broadlink|ecobee|nest labs|ring llc|wyze|irobot|yeelink|chamberlain|aqara|lumi united|sonoff|wiz connected|tado|netatmo|eve systems|nanoleaf|belkin", SMART_HOME to 30),
        rule("hikvision|dahua|axis comm|reolink|amcrest|foscam|uniview|hanwha|vivotek|arlo|eufy|anker innov", CAMERA to 35),
        rule("sonos|bose|harman|bang & olufsen|denon|marantz|yamaha|bluesound|devialet|sound united", SPEAKER to 30),
        rule("roku", STREAMER to 35),
        rule("amazon tech", STREAMER to 15, SPEAKER to 15),
        rule("nvidia", STREAMER to 10, COMPUTER to 10),
        rule("lg electronics|vizio|\\btcl\\b|hisense|sony corp|panasonic|tp vision|sharp corp|funai|skyworth|toshiba visual|universal electronics", TV to 22),
        rule("nintendo|sony interactive|valve corp", GAME_CONSOLE to 40),
        rule("garmin|fitbit|polar electro|suunto|huami|zepp|withings|whoop|oura", WEARABLE to 35),
        rule("jabra|gn audio|sennheiser|skullcandy|plantronics|\\bpoly\\b|soundcore|bose|beats", HEADPHONES to 25),
    )

    // ---- hostnames, mDNS instance names, NetBIOS names, UPnP friendly names ------------------

    private val NAME_RULES = listOf(
        rule("iphone", PHONE to 45),
        rule("ipad", TABLET to 45),
        rule("macbook|imac|mac-?mini|mac-?pro|mac-?studio", COMPUTER to 45),
        rule("apple-?tv", STREAMER to 45),
        rule("homepod", SPEAKER to 45),
        rule("android|galaxy|pixel|oneplus|redmi|xiaomi|huawei|oppo|\\bvivo|moto ?[egz]|phone", PHONE to 30),
        rule("\\btab\\b|galaxy-?tab|tablet|kindle|fire-?hd", TABLET to 30),
        rule("desktop-|laptop-|^pc-|-pc\\b|workstation|thinkpad|surface|notebook|zenbook|vivobook|ideapad|latitude|xps", COMPUTER to 35),
        rule("chromecast|google-?tv|nest-?hub", STREAMER to 40),
        rule("roku|fire-?tv|firestick|shield", STREAMER to 40),
        rule("echo|alexa|nest-?(mini|audio)|google-?home", SPEAKER to 35),
        rule("sonos|soundbar|speaker", SPEAKER to 40),
        rule("printer|^hp[0-9a-f]{6}|^brn|^brw|epson|canon|laserjet|officejet|deskjet|envy|pixma|mfc-", PRINTER to 40),
        rule("\\bnas\\b|diskstation|synology|qnap|truenas|unraid|openmediavault", SERVER to 40),
        rule("raspberrypi|ubuntu|debian|fedora|proxmox|homeassistant|hassio|server|pihole|pi-hole|docker", SERVER to 30),
        rule("playstation|\\bps[345]\\b|xbox|nintendo|steamdeck", GAME_CONSOLE to 45),
        rule("esp[-_]|esp32|esp8266|tasmota|shelly|sonoff|tuya|wled|esphome|kasa|hue-?bridge|philips-hue|\\bwiz|tado|thermostat|plug|bulb|nanoleaf|vacuum|roborock|roomba", SMART_HOME to 40),
        rule("\\bcam|ipc|nvr|dvr|doorbell|ring-|reolink|hikvision|dahua|wyze", CAMERA to 35),
        rule("\\btv\\b|bravia|webos|lgwebostv|samsung-?tv|tizen|vizio|\\[tv\\]|androidtv", TV to 40),
        rule("router|gateway|fritz|openwrt|dd-?wrt|mikrotik|unifi|edgerouter|orbi|eero|\\bdeco\\b|access ?point|\\bap\\b|mesh|extender|repeater", ROUTER to 35),
        rule("watch", WEARABLE to 30),
    )

    // ---- mDNS / DNS-SD service types ---------------------------------------------------------

    private val MDNS_RULES: Map<String, List<Pair<DeviceType, Int>>> = mapOf(
        "_googlecast._tcp" to listOf(STREAMER to 40),
        "_airplay._tcp" to listOf(STREAMER to 20, TV to 10),
        "_raop._tcp" to listOf(SPEAKER to 15),
        "_mediaremotetv._tcp" to listOf(STREAMER to 40),
        "_companion-link._tcp" to listOf(PHONE to 15, COMPUTER to 10, TABLET to 10),
        "_apple-mobdev2._tcp" to listOf(PHONE to 30, TABLET to 15),
        "_sleep-proxy._udp" to listOf(STREAMER to 20, SPEAKER to 10),
        "_ipp._tcp" to listOf(PRINTER to 50),
        "_ipps._tcp" to listOf(PRINTER to 50),
        "_printer._tcp" to listOf(PRINTER to 50),
        "_pdl-datastream._tcp" to listOf(PRINTER to 50),
        "_scanner._tcp" to listOf(PRINTER to 40),
        "_uscan._tcp" to listOf(PRINTER to 40),
        "_uscans._tcp" to listOf(PRINTER to 40),
        "_hap._tcp" to listOf(SMART_HOME to 35),
        "_hap._udp" to listOf(SMART_HOME to 35),
        "_homekit._tcp" to listOf(SMART_HOME to 30),
        "_matter._tcp" to listOf(SMART_HOME to 40),
        "_matterc._udp" to listOf(SMART_HOME to 40),
        "_hue._tcp" to listOf(SMART_HOME to 50),
        "_esphomelib._tcp" to listOf(SMART_HOME to 50),
        "_wled._tcp" to listOf(SMART_HOME to 50),
        "_shelly._tcp" to listOf(SMART_HOME to 50),
        "_nanoleafapi._tcp" to listOf(SMART_HOME to 45),
        "_miio._udp" to listOf(SMART_HOME to 40),
        "_meshcop._udp" to listOf(SMART_HOME to 20),
        "_spotify-connect._tcp" to listOf(SPEAKER to 25),
        "_sonos._tcp" to listOf(SPEAKER to 50),
        "_amzn-wplay._tcp" to listOf(STREAMER to 25, SPEAKER to 10),
        "_amzn-alexa._tcp" to listOf(SPEAKER to 30),
        "_androidtvremote2._tcp" to listOf(TV to 45),
        "_androidtvremote._tcp" to listOf(TV to 45),
        "_nvstream._tcp" to listOf(COMPUTER to 30),
        "_rdp._tcp" to listOf(COMPUTER to 30),
        "_rfb._tcp" to listOf(COMPUTER to 20),
        "_dosvc._tcp" to listOf(COMPUTER to 30),
        "_smb._tcp" to listOf(COMPUTER to 15, SERVER to 15),
        "_afpovertcp._tcp" to listOf(SERVER to 20, COMPUTER to 10),
        "_adisk._tcp" to listOf(SERVER to 25),
        "_ssh._tcp" to listOf(SERVER to 15, COMPUTER to 10),
        "_sftp-ssh._tcp" to listOf(SERVER to 15, COMPUTER to 10),
        "_workstation._tcp" to listOf(COMPUTER to 15, SERVER to 15),
        "_home-assistant._tcp" to listOf(SERVER to 45),
        "_plexmediasvr._tcp" to listOf(SERVER to 40),
        "_daap._tcp" to listOf(COMPUTER to 15),
        "_adb-tls-connect._tcp" to listOf(PHONE to 25),
        "_adb._tcp" to listOf(PHONE to 15, STREAMER to 15),
    )

    private val CAST_MODEL_RULES = listOf(
        rule("google home|nest (mini|audio)|home mini|speaker", SPEAKER to 45),
        rule("nest hub|home hub", SMART_HOME to 40),
        rule("chromecast|google tv", STREAMER to 40),
        rule("\\btv\\b|bravia|vizio|tcl|hisense|philips", TV to 40),
    )

    // ---- web page title / Server header --------------------------------------------------------

    private val HTTP_RULES = listOf(
        rule("router|gateway|fritz|openwrt|luci|dd-wrt|mikrotik|unifi|tp-link|netgear|linksys|asuswrt|modem|livebox|speedport|ziggo|connect box", ROUTER to 30),
        rule("printer|laserjet|officejet|epson|brother|canon|web image monitor|embedded web server|cups", PRINTER to 35),
        rule("camera|ipcam|hikvision|dahua|\\bnvr\\b|\\bdvr\\b|reolink|amcrest|netwave", CAMERA to 35),
        rule("synology|qnap|diskstation|truenas|openmediavault|unraid", SERVER to 40),
        rule("home assistant|plex|jellyfin|emby|proxmox|pi-hole|portainer|nextcloud|grafana|octoprint", SERVER to 35),
        rule("shelly|tasmota|esphome|wled|tuya|hue|homey", SMART_HOME to 40),
    )

    // ---- TCP ports -----------------------------------------------------------------------------

    private val PORT_RULES: Map<Int, List<Pair<DeviceType, Int>>> = mapOf(
        9100 to listOf(PRINTER to 40),
        631 to listOf(PRINTER to 30),
        515 to listOf(PRINTER to 30),
        554 to listOf(CAMERA to 35),
        62078 to listOf(PHONE to 45, TABLET to 20),
        8008 to listOf(STREAMER to 25),
        8009 to listOf(STREAMER to 30),
        7000 to listOf(STREAMER to 10, COMPUTER to 8),
        3389 to listOf(COMPUTER to 40),
        5900 to listOf(COMPUTER to 20),
        445 to listOf(COMPUTER to 15, SERVER to 15),
        139 to listOf(COMPUTER to 10),
        548 to listOf(SERVER to 20),
        32400 to listOf(SERVER to 35),
        8123 to listOf(SERVER to 40),
        22 to listOf(SERVER to 10, COMPUTER to 5),
        21 to listOf(SERVER to 10),
        1400 to listOf(SPEAKER to 40),
        1883 to listOf(SMART_HOME to 20, SERVER to 10),
        8883 to listOf(SMART_HOME to 20, SERVER to 10),
        6466 to listOf(TV to 40),
        6467 to listOf(TV to 30),
        5555 to listOf(STREAMER to 15, PHONE to 10),
        53 to listOf(ROUTER to 25),
        23 to listOf(ROUTER to 10, SMART_HOME to 8, CAMERA to 5),
    )

    // ---- LAN devices -----------------------------------------------------------------------------

    fun classify(d: LanDevice): Guess {
        val s = Scorer()
        if (d.isSelf) s.add(PHONE, 90, "This is the device running the scan")
        if (d.isGateway) s.add(ROUTER, 70, "Default gateway of this network")

        s.match(VENDOR_RULES, d.vendor, "MAC vendor")
        d.mac?.let { mac ->
            if (com.example.banaanscan.data.OuiDatabase.isLocallyAdministered(mac)) {
                s.add(PHONE, 15, "Randomized (private) MAC – typical for phones, tablets and laptops")
            }
        }

        val names = buildList {
            add(d.hostname); add(d.netbiosName); addAll(d.mdnsHosts); addAll(d.mdnsNames); add(d.ssdp?.friendlyName)
        }.filterNotNull().joinToString(" | ")
        s.match(NAME_RULES, names, "Name")

        d.mdnsServices.forEach { svc -> MDNS_RULES[svc]?.forEach { (t, p) -> s.add(t, p, "Advertises $svc") } }
        appleModel(d.mdnsTxt["model"] ?: d.mdnsTxt["am"])?.let { (t, model) -> s.add(t, 60, "Apple model identifier $model") }
        s.match(CAST_MODEL_RULES, d.mdnsTxt["md"], "Cast model")
        d.mdnsTxt["ty"]?.let { s.add(PRINTER, 30, "Printer model \"$it\"") }

        d.openPorts.forEach { port -> PORT_RULES[port]?.forEach { (t, p) -> s.add(t, p, "Port $port open") } }
        if (53 in d.openPorts && (80 in d.openPorts || 443 in d.openPorts)) s.add(ROUTER, 10, "DNS + web admin")
        if (5000 in d.openPorts && 5001 in d.openPorts) s.add(SERVER, 30, "Ports 5000/5001 (Synology DSM style)")

        d.ssdp?.let { u ->
            val upnp = listOfNotNull(u.deviceType, u.server, *u.searchTargets.toTypedArray()).joinToString(" ")
            s.match(SSDP_RULES, upnp, "UPnP")
            s.match(VENDOR_RULES, u.manufacturer, "UPnP manufacturer")
            s.match(NAME_RULES, u.modelName, "UPnP model")
        }

        s.match(HTTP_RULES, listOfNotNull(d.httpServer, d.httpTitle).joinToString(" "), "Web page")
        if (d.netbiosName != null) s.add(COMPUTER, 20, "Answers NetBIOS (Windows / Samba)")

        return s.guess()
    }

    private val SSDP_RULES = listOf(
        rule("InternetGatewayDevice|WANDevice|WANConnection|WFADevice|miniupnpd|\\bIGD\\b", ROUTER to 40),
        rule("MediaRenderer", TV to 15, SPEAKER to 15),
        rule("MediaServer", SERVER to 25),
        rule("ZonePlayer|sonos", SPEAKER to 50),
        rule("dial-multiscreen|\\bdial\\b", TV to 20, STREAMER to 20),
        rule("roku", STREAMER to 40),
        rule("webos|\\bLGE\\b", TV to 35),
        rule("samsung|tizen|\\bSHP\\b", TV to 25),
        rule("Printer|Scanner", PRINTER to 40),
        rule("DigitalSecurityCamera|camera", CAMERA to 40),
        rule("Windows", COMPUTER to 20),
        rule("hue|philips-hue", SMART_HOME to 40),
    )

    private fun appleModel(model: String?): Pair<DeviceType, String>? {
        if (model.isNullOrBlank()) return null
        val type = when {
            model.startsWith("iPhone", true) -> PHONE
            model.startsWith("iPad", true) -> TABLET
            model.startsWith("AppleTV", true) -> STREAMER
            model.startsWith("AudioAccessory", true) -> SPEAKER
            model.startsWith("Watch", true) -> WEARABLE
            model.contains("Mac", true) -> COMPUTER
            else -> return null
        }
        return type to model
    }

    // ---- Wi-Fi access points ---------------------------------------------------------------------

    private val AP_RULES = listOf(
        rule("^DIRECT-.*(HP|Brother|EPSON|Canon|Xerox|Samsung M)", PRINTER to 60),
        rule("^DIRECT-.*(TV|BRAVIA|LG|Samsung|Roku)|\\[TV\\]", TV to 55),
        rule("iphone|androidap|galaxy|pixel|redmi|oneplus|huawei|'s phone|’s phone|hotspot", PHONE to 55),
        rule("ipad", TABLET to 55),
        rule("chromecast|fire ?tv|roku", STREAMER to 50),
        rule("cam|ipc|doorbell|ring", CAMERA to 35),
        rule("esp|shelly|tasmota|sonoff|tuya|smartlife|wled|vacuum|roborock", SMART_HOME to 45),
        rule("macbook|desktop-|laptop", COMPUTER to 45),
    )

    fun classifyAp(ssid: String, vendor: String?, randomized: Boolean): Guess {
        val s = Scorer()
        s.add(ROUTER, 30, "Broadcasts a Wi-Fi network")
        s.match(AP_RULES, ssid, "SSID")
        s.match(VENDOR_RULES, vendor, "BSSID vendor")
        if (randomized) s.add(PHONE, 10, "Locally administered BSSID (hotspot or virtual AP)")
        return s.guess()
    }

    // ---- Bluetooth -----------------------------------------------------------------------------

    /** Bluetooth SIG company identifiers seen in BLE manufacturer data. */
    val BLE_COMPANIES: Map<Int, String> = mapOf(
        0x004C to "Apple",
        0x0006 to "Microsoft",
        0x0075 to "Samsung",
        0x00E0 to "Google",
        0x0171 to "Amazon",
        0x0087 to "Garmin",
        0x0157 to "Huami (Amazfit)",
        0x038F to "Xiaomi",
        0x009E to "Bose",
        0x0059 to "Nordic Semiconductor",
        0x02E5 to "Espressif",
        0x0499 to "Ruuvi",
        0x012D to "Sony",
        0x000F to "Broadcom",
    )

    private val COMPANY_RULES: Map<Int, List<Pair<DeviceType, Int>>> = mapOf(
        0x0006 to listOf(COMPUTER to 30),
        0x0075 to listOf(PHONE to 20, HEADPHONES to 8, TV to 8),
        0x00E0 to listOf(PHONE to 15),
        0x0171 to listOf(SPEAKER to 20, STREAMER to 10),
        0x0087 to listOf(WEARABLE to 50),
        0x0157 to listOf(WEARABLE to 50),
        0x038F to listOf(WEARABLE to 15, PHONE to 10, SMART_HOME to 10),
        0x009E to listOf(HEADPHONES to 40, SPEAKER to 20),
        0x0059 to listOf(SMART_HOME to 15, TRACKER to 10),
        0x02E5 to listOf(SMART_HOME to 35),
        0x0499 to listOf(SMART_HOME to 50),
        0x012D to listOf(HEADPHONES to 25, GAME_CONSOLE to 10),
    )

    private val BLE_SERVICE_RULES: Map<String, List<Pair<DeviceType, Int>>> = mapOf(
        "180d" to listOf(WEARABLE to 35),     // Heart rate
        "1812" to listOf(INPUT to 45),        // HID over GATT
        "feaa" to listOf(TRACKER to 40),      // Eddystone beacon
        "fd6f" to listOf(PHONE to 35),        // Exposure notification
        "fe2c" to listOf(HEADPHONES to 25),   // Google Fast Pair
        "feed" to listOf(TRACKER to 50),      // Tile
        "feec" to listOf(TRACKER to 50),      // Tile
        "fd5a" to listOf(TRACKER to 45),      // Samsung SmartTag
        "fe9f" to listOf(PHONE to 10),        // Google
        "181a" to listOf(SMART_HOME to 35),   // Environmental sensing
        "1809" to listOf(SMART_HOME to 20),   // Health thermometer
        "1816" to listOf(WEARABLE to 30),     // Cycling speed/cadence
        "1814" to listOf(WEARABLE to 30),     // Running speed/cadence
        "1810" to listOf(WEARABLE to 25),     // Blood pressure
        "1808" to listOf(WEARABLE to 25),     // Glucose
        "184e" to listOf(HEADPHONES to 30),   // LE Audio stream control
        "fe07" to listOf(SPEAKER to 40),      // Sonos
        "fe95" to listOf(SMART_HOME to 30),   // Xiaomi MiBeacon
    )

    private val BT_NAME_RULES = listOf(
        rule("airpods|buds|earbuds|headphone|headset|\\bwh-|\\bwf-|jabra|\\bqc ?\\d|beats|soundcore|freebuds|\\btws\\b|earphone", HEADPHONES to 45),
        rule("speaker|jbl|soundlink|\\bboom|sonos|echo|home mini|soundbar|megaboom|flip ?\\d|charge ?\\d", SPEAKER to 35),
        rule("watch|\\bband\\b|fitbit|garmin|amazfit|mi band|forerunner|fenix|venu|versa|whoop|oura|polar", WEARABLE to 40),
        rule("keyboard|mouse|\\bmx |trackpad|magic|pen\\b|stylus", INPUT to 45),
        rule("controller|gamepad|dualsense|dualshock|xbox wireless|joy-con|pro controller", INPUT to 40, GAME_CONSOLE to 10),
        rule("\\btv\\b|bravia|\\[tv\\]|roku|fire ?tv|chromecast", TV to 40),
        rule("iphone", PHONE to 50),
        rule("ipad", TABLET to 50),
        rule("macbook|imac|desktop|laptop|-pc\\b|thinkpad|surface", COMPUTER to 40),
        rule("galaxy|pixel|oneplus|redmi|xiaomi|moto|phone|nokia", PHONE to 30),
        rule("tile|airtag|smarttag|chipolo|tracker|beacon|nut\\b", TRACKER to 45),
        rule("printer|deskjet|officejet|laserjet|epson|brother", PRINTER to 40),
        rule("\\bcar\\b|carkit|my car|toyota|\\bford|honda|\\bvw\\b|\\bbmw|audi|mazda|uconnect|mercedes|hyundai|\\bkia\\b|volvo|tesla", SPEAKER to 20),
        rule("bulb|lamp|light|plug|switch|sensor|thermo|lock", SMART_HOME to 30),
    )

    fun classifyBt(d: BtDevice): Guess {
        val s = Scorer()

        val major = d.majorClass
        if (major != null && major != BluetoothClass.Device.Major.UNCATEGORIZED && major != BluetoothClass.Device.Major.MISC) {
            classifyBtClass(s, major, d.deviceClass)
        }

        d.manufacturerData.forEach { (id, hex) ->
            val company = BLE_COMPANIES[id] ?: "company 0x%04X".format(id)
            if (id == 0x004C) appleContinuity(s, hex)
            else COMPANY_RULES[id]?.forEach { (t, p) -> s.add(t, p, "BLE data from $company") }
        }
        d.serviceUuids.forEach { uuid ->
            BLE_SERVICE_RULES[uuid.lowercase()]?.forEach { (t, p) -> s.add(t, p, "BLE service 0x$uuid") }
        }
        s.match(BT_NAME_RULES, d.name, "Name")
        if (d.vendorReliable) s.match(VENDOR_RULES, d.vendor, "MAC vendor")
        return s.guess()
    }

    private fun classifyBtClass(s: Scorer, major: Int, cls: Int?) {
        val why = "Bluetooth device class"
        when (major) {
            BluetoothClass.Device.Major.PHONE -> s.add(PHONE, 50, why)
            BluetoothClass.Device.Major.COMPUTER -> when (cls) {
                BluetoothClass.Device.COMPUTER_HANDHELD_PC_PDA,
                BluetoothClass.Device.COMPUTER_PALM_SIZE_PC_PDA -> s.add(TABLET, 35, why)
                BluetoothClass.Device.COMPUTER_SERVER -> s.add(SERVER, 40, why)
                else -> s.add(COMPUTER, 50, why)
            }
            BluetoothClass.Device.Major.AUDIO_VIDEO -> when (cls) {
                BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
                BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
                BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> s.add(HEADPHONES, 50, why)
                BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
                BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
                BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> s.add(SPEAKER, 50, why)
                BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> s.add(SPEAKER, 35, "$why (car audio)")
                BluetoothClass.Device.AUDIO_VIDEO_VIDEO_DISPLAY_AND_LOUDSPEAKER -> s.add(TV, 50, why)
                BluetoothClass.Device.AUDIO_VIDEO_SET_TOP_BOX -> s.add(STREAMER, 45, why)
                else -> s.add(SPEAKER, 25, why)
            }
            BluetoothClass.Device.Major.PERIPHERAL -> s.add(INPUT, 50, why)
            BluetoothClass.Device.Major.IMAGING -> s.add(PRINTER, 40, why)
            BluetoothClass.Device.Major.WEARABLE -> s.add(WEARABLE, 50, why)
            BluetoothClass.Device.Major.TOY ->
                if (cls == BluetoothClass.Device.TOY_CONTROLLER) s.add(INPUT, 40, why) else s.add(GAME_CONSOLE, 15, why)
            BluetoothClass.Device.Major.HEALTH -> s.add(WEARABLE, 30, why)
            BluetoothClass.Device.Major.NETWORKING -> s.add(ROUTER, 30, why)
        }
    }

    /** Apple "Continuity" advertisements: the first payload byte identifies the message type. */
    private fun appleContinuity(s: Scorer, hex: String) {
        when (hex.take(2).uppercase()) {
            "07" -> s.add(HEADPHONES, 55, "Apple proximity-pairing beacon (AirPods / Beats)")
            "12" -> s.add(TRACKER, 40, "Apple Find My beacon (AirTag or offline Apple device)")
            "02" -> s.add(TRACKER, 40, "iBeacon")
            "09" -> s.add(STREAMER, 25, "Apple AirPlay target")
            "10" -> { s.add(PHONE, 25, "Apple Nearby Info"); s.add(TABLET, 10, "Apple Nearby Info"); s.add(COMPUTER, 10, "Apple Nearby Info") }
            "0C", "0F" -> { s.add(PHONE, 15, "Apple Handoff / Nearby Action"); s.add(COMPUTER, 15, "Apple Handoff / Nearby Action") }
            else -> s.add(PHONE, 10, "Apple BLE advertisement")
        }
    }
}
