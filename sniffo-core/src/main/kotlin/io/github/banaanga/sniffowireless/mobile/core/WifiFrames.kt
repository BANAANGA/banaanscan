package io.github.banaanga.sniffowireless.mobile.core

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction

/** Only lets a (device, source) through every [interval] seconds: sniffowireless's FingerprintGate. */
class Gate(private val interval: Double = 600.0) {
    private val seen = HashMap<Pair<String, String>, Double>()

    operator fun invoke(mac: String, src: String, ts: Double): Boolean {
        val k = mac to src
        if (ts - (seen[k] ?: 0.0) < interval) return false
        if (seen.size > 100_000) seen.clear()
        seen[k] = ts
        return true
    }
}

/** Rate limits for the extra attributes (sniffowireless's AttrGates): AP details, link quality, beacon timer readings. */
class AttrGates(apInterval: Double = 60.0, linkInterval: Double = 30.0) {
    val ap = Gate(apInterval)
    val link = Gate(linkInterval)
    val skew = Gate(10.0)
}

/**
 * Turns one captured 802.11 frame into sniffowireless observations, as its wifi.parse_frame does with scapy. The frame
 * starts at the 802.11 header (no radiotap, no FCS). [rssi] and [rtFreq] are what the radio reported; [tuned] is the
 * frequency the adapter is tuned to, which wins when the radio reports the right channel in the wrong band.
 *
 * Information elements are read by their length fields. That is what scapy does for well-formed elements; for malformed
 * ones its per-element parsers differ (see tools/sniffowireless/make_fixtures.py), which this doesn't copy.
 */
object WifiFrames {
    private const val BROADCAST = "ff:ff:ff:ff:ff:ff"

    fun isUnicast(mac: String?): Boolean {
        if (mac == null || mac == BROADCAST || mac == "00:00:00:00:00:00") return false
        return (mac.substring(0, 2).toIntOrNull(16) ?: return false) and 0x01 == 0
    }

    fun isLocal(mac: String?): Boolean = ((mac?.take(2)?.toIntOrNull(16) ?: 0) and 0x02) != 0

    /** SSID bytes to text: empty or all zeros is "" (hidden), UTF-8 if valid, else Latin-1; printable characters only. */
    fun decodeSsid(raw: ByteArray?): String? {
        if (raw == null) return null
        if (raw.isEmpty() || raw.all { it.toInt() == 0 }) return ""
        val s = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: CharacterCodingException) {
            String(raw, Charsets.ISO_8859_1)
        }
        return WifiAttrs.printable(s)
    }

    /** The frequency a frame was received on: the radio's, unless it names the tuned channel in another band. */
    fun radioFreq(rtFreq: Int?, tuned: Int?): Int? {
        if (tuned == null || tuned == 0) return rtFreq
        if (rtFreq == null || rtFreq == 0) return tuned
        if (Channels.bandOf(rtFreq) != Channels.bandOf(tuned) && Channels.toChannel(rtFreq) == Channels.toChannel(tuned)) return tuned
        return rtFreq
    }

    fun elements(b: ByteArray, from: Int): List<Elt> {
        val out = ArrayList<Elt>()
        var i = from
        while (i + 2 <= b.size) {
            val id = b[i].toInt() and 0xFF
            val len = b[i + 1].toInt() and 0xFF
            val end = minOf(b.size, i + 2 + len)
            out += Elt(id, b.copyOfRange(i + 2, end))
            i += 2 + len
        }
        return out
    }

    private val AKM_SCAPY = mapOf(0 to "Reserved", 1 to "802.1X", 2 to "PSK", 3 to "FT-802.1X", 4 to "FT-PSK", 5 to "WPA-SHA256",
        6 to "PSK-SHA256", 7 to "TDLS", 8 to "SAE", 9 to "FT-SAE", 10 to "AP-PEER-KEY", 11 to "WPA-SHA256-SUITE-B",
        12 to "WPA-SHA384-SUITE-B", 13 to "FT-802.1X-SHA384", 14 to "FILS-SHA256", 15 to "FILS-SHA384", 16 to "FT-FILS-SHA256",
        17 to "FT-FILS-SHA384", 18 to "OWE")

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    /** Suite numbers of an RSN-style body from [at]: (pairwise ciphers, AKMs, capabilities), or null when cut short. */
    private fun suites(info: ByteArray, at: Int): Triple<List<Int>, List<Int>, Int>? {
        var i = at
        if (i + 2 > info.size) return null
        val np = le16(info, i); i += 2
        if (i + 4 * np > info.size) return null
        val pairwise = (0 until np).map { u8(info, i + 4 * it + 3) }; i += 4 * np
        if (i + 2 > info.size) return null
        val na = le16(info, i); i += 2
        if (i + 4 * na > info.size) return null
        val akm = (0 until na).map { u8(info, i + 4 * it + 3) }; i += 4 * na
        val caps = if (i + 2 <= info.size) le16(info, i) else 0
        return Triple(pairwise, akm, caps)
    }

    /**
     * The encryption label of scapy's Dot11Beacon.network_stats() ("WPA2/PSK", "WPA3/SAE", "WPA3-transition/PSK", "WPA/PSK",
     * "WEP", "OPN", several joined by commas), and the DS Parameter Set channel. A DS element that isn't one byte made
     * scapy raise, which loses both: then (null, null).
     */
    internal fun networkStats(elts: List<Elt>, privacy: Boolean): Pair<String?, Int?> {
        val crypto = sortedSetOf<String>()
        var channel: Int? = null
        for (e in elts) {
            when {
                e.id == 3 -> if (e.info.size == 1) channel = u8(e.info, 0) else return null to null
                e.id == 48 -> {
                    val (pairwise, akm, caps) = suites(e.info, 6) ?: Triple(emptyList(), emptyList(), 0)
                    val mfpc = caps and 0x80 != 0
                    val mfpr = caps and 0x40 != 0
                    var version = "WPA2"
                    if (8 in akm && akm.none { it == 2 || it == 6 } && mfpc && mfpr && pairwise.none { it in setOf(1, 2, 5) }) version = "WPA3"
                    else if (8 in akm && 2 in akm && mfpc && !mfpr) version = "WPA3-transition"
                    crypto += if (akm.isNotEmpty()) "$version/${AKM_SCAPY[akm[0]] ?: akm[0].toString()}" else version
                }
                e.id == 221 && e.info.size >= 4 && u8(e.info, 0) == 0x00 && u8(e.info, 1) == 0x50 && u8(e.info, 2) == 0xf2 && u8(e.info, 3) == 1 -> {
                    val akm = suites(e.info, 4 + 2 + 4)?.second.orEmpty()
                    crypto += if (akm.isNotEmpty()) "WPA/${AKM_SCAPY[akm[0]] ?: akm[0].toString()}" else "WPA"
                }
            }
        }
        if (crypto.isEmpty()) crypto += if (privacy) "WEP" else "OPN"
        return crypto.joinToString(",") to channel
    }

    /** (csa, HT operation primary channel) from a beacon's elements. */
    internal fun chanElts(elts: List<Elt>): Pair<Map<String, Int>?, Int?> {
        var csa: Map<String, Int>? = null
        var primary: Int? = null
        for (e in elts) {
            val i = e.info
            if (csa == null && e.id == 37 && i.size >= 3) csa = linkedMapOf("mode" to u8(i, 0), "new_channel" to u8(i, 1), "count" to u8(i, 2))
            else if (csa == null && e.id == 60 && i.size >= 4) csa = linkedMapOf("mode" to u8(i, 0), "op_class" to u8(i, 1), "new_channel" to u8(i, 2), "count" to u8(i, 3))
            else if (e.id == 61 && i.isNotEmpty()) primary = u8(i, 0)
        }
        return csa to primary
    }

    private fun mac(b: ByteArray, at: Int): String? =
        if (at + 6 <= b.size) (0 until 6).joinToString(":") { "%02x".format(u8(b, at + it)) } else null

    /**
     * Observations for one frame. [phyRate] (Mb/s) and [noise] (dBm) go into the link-quality extras when the radio
     * reports them; [seqOn] adds the sequence number of randomised transmitters, as sniffowireless's sensors do.
     */
    fun parse(
        frame: ByteArray, rssiIn: Int?, rtFreq: Int?, tuned: Int?, ts: Double,
        fpGate: Gate? = null, gates: AttrGates? = null, seqOn: Boolean = true, phyRate: Double? = null, noise: Int? = null,
    ): List<MutableMap<String, Any?>> {
        if (frame.size < 10) return emptyList()
        val fc0 = u8(frame, 0)
        val flags = u8(frame, 1)
        val type = (fc0 shr 2) and 3
        val st = (fc0 shr 4) and 0xF
        val rssi = rssiIn?.takeIf { it in -119..9 }
        val freq = radioFreq(rtFreq, tuned)
        val hasA2 = type != 1 || st in setOf(8, 9, 10, 11, 14, 15)
        val a1 = mac(frame, 4)
        val a2 = if (hasA2) mac(frame, 10) else null
        val a3 = if (type == 0 || type == 2) mac(frame, 16) else null
        val seq = if (type != 1 && frame.size >= 24) (le16(frame, 22) shr 4) and 0xFFF else null
        val ch = Channels.toChannel(freq)
        val band = Channels.bandOf(freq)
        val out = ArrayList<MutableMap<String, Any?>>()
        val protected = flags and 0x40 != 0
        val body = 24
        // scapy decodes the fixed fields and elements of a management frame only when it isn't protected.
        fun eltsFrom(fixed: Int): List<Elt> = if (!protected && type == 0 && frame.size >= body + fixed) elements(frame, body + fixed) else emptyList()

        fun fingerprint(m: String?, src: String, elts: List<Elt>): Map<String, Any>? {
            if (!isUnicast(m) || (fpGate != null && !fpGate(m!!, src, ts))) return null
            val fp = WifiAttrs.fingerprint(elts)
            return if (fp.isNotEmpty()) linkedMapOf("wifi" to fp, "src" to src) else null
        }

        fun client(m: String?, bssid: String?, kind: String?, r: Int? = null, tx: Boolean = false, link: String? = null, kw: Map<String, Any?> = emptyMap()) {
            if (!isUnicast(m) || m == bssid) return
            val o = linkedMapOf<String, Any?>("t" to "wifi_client", "ts" to ts, "mac" to m, "bssid" to bssid, "frame" to kind,
                "rssi" to r, "freq" to freq, "band" to band)
            o.putAll(kw)
            if (tx && seqOn && seq != null && isLocal(m)) o["seq"] = seq
            if (link != null && gates != null && gates.link(m!!, link, ts)) {
                val ex = linkedMapOf<String, Any>()
                phyRate?.let { ex["rate_$link"] = Math.round(it * 10) / 10.0 }
                noise?.takeIf { it in -119..-21 }?.let { ex["noise"] = it }
                if (ex.isNotEmpty()) o["extra"] = ex
            }
            out += o
        }

        fun ap(m: String?, r: Int? = null, beaconCh: Int? = null, kw: Map<String, Any?> = emptyMap()) {
            if (!isUnicast(m)) return
            val o = linkedMapOf<String, Any?>("t" to "wifi_ap", "ts" to ts, "mac" to m, "rssi" to r, "freq" to freq, "band" to band,
                "channel" to if (band == "6") ch else (beaconCh?.takeIf { it != 0 } ?: ch))
            o.putAll(kw)
            out += o
        }

        if (type == 0) {
            when (st) {
                8, 5 -> {
                    if (protected || frame.size < body + 12) return out
                    val tsf = frame.copyOfRange(body, body + 8).foldIndexed(0L) { i, acc, b -> acc or ((b.toLong() and 0xFF) shl (8 * i)) }
                    val interval = le16(frame, body + 8)
                    val cap = (u8(frame, body + 10) shl 8) or u8(frame, body + 11) // scapy reads it big-endian
                    val elts = eltsFrom(12)
                    val (enc, dsChannel) = networkStats(elts, privacy = u8(frame, body + 10) and 0x10 != 0)
                    val ssid = elts.firstOrNull { it.id == 0 }?.let { decodeSsid(it.info) }
                    val (csa, primary) = chanElts(elts)
                    val extra = linkedMapOf<String, Any>()
                    if (st == 8 && ssid == "") extra["hidden"] = true
                    val bssid = a2 ?: a3
                    if (gates != null && gates.ap(bssid ?: "", "ap", ts)) {
                        val info = WifiAttrs.apAttributes(elts, interval, cap)
                        if (tsf > 0 && tsf < 5L * 365 * 86400 * 1_000_000) info["boot"] = (ts - tsf / 1e6).toLong()
                        if (info.isNotEmpty()) extra["ap"] = info
                    }
                    val tsfOut = if (st == 8 && tsf > 0 && gates != null && gates.skew(bssid ?: "", "skew", ts)) tsf else null
                    ap(bssid, rssi, dsChannel?.takeIf { it != 0 } ?: primary, linkedMapOf("ssid" to ssid?.ifEmpty { null }, "enc" to enc,
                        "extra" to extra.ifEmpty { null }, "tsf" to tsfOut, "fp" to fingerprint(bssid, "beacon", elts), "csa" to csa))
                    if (st == 5) client(a1, null, "probe_resp")
                }
                4 -> {
                    val elts = eltsFrom(0)
                    val ssid = elts.firstOrNull { it.id == 0 }?.let { decodeSsid(it.info) }
                    client(a2, null, "probe", rssi, tx = true, kw = linkedMapOf("probe" to ssid?.ifEmpty { null }, "fp" to fingerprint(a2, "probe", elts)))
                }
                0, 2 -> {
                    val elts = eltsFrom(if (st == 0) 4 else 10)
                    client(a2, a1, "assoc", rssi, tx = true, kw = linkedMapOf("fp" to fingerprint(a2, "assoc", elts)))
                    ap(a1)
                }
                1, 3 -> {
                    val ok = protected || frame.size < body + 4 || le16(frame, body + 2) == 0
                    ap(a2, rssi)
                    client(a1, if (ok) a2 else null, if (ok) "assoc" else "assoc_fail")
                }
                11 -> {
                    if (a2 == a3) {
                        ap(a2, rssi)
                        client(a1, null, "auth")
                    } else {
                        client(a2, null, "auth", rssi, tx = true)
                    }
                }
                10, 12 -> {
                    val reason = if (!protected && frame.size >= body + 2) le16(frame, body) else null
                    val kind = if (st == 12) "deauth" else "disassoc"
                    val bssid = a3
                    if (a2 == bssid) {
                        ap(a2, rssi)
                        if (!isUnicast(a1)) {
                            out += linkedMapOf("t" to "event", "ts" to ts, "mac" to bssid, "type" to "deauth_broadcast",
                                "detail" to "$kind to ${a1 ?: "None"} reason ${reason ?: "None"}")
                        } else {
                            client(a1, bssid, kind, kw = linkedMapOf("reason" to reason, "from_ap" to true))
                        }
                    } else {
                        client(a2, bssid, kind, rssi, kw = linkedMapOf("reason" to reason))
                    }
                }
            }
        } else if (type == 2) {
            val toDs = flags and 0x01 != 0
            val fromDs = flags and 0x02 != 0
            if (toDs && !fromDs) {
                client(a2, a1, "data", rssi, tx = true, link = "up")
                ap(a1)
            } else if (fromDs && !toDs) {
                ap(a2, rssi)
                client(a1, a2, "data", link = "down")
            }
        }
        return out
    }

    /**
     * A "wifi_ap" observation from the phone's own Wi-Fi scan (no monitor mode). With the access point's information
     * elements (Android 11+) it carries the same encryption label, channel, CSA, AP details and fingerprint a beacon
     * gives; without them the label comes from Android's capability string. The beacon signature is left out: it needs
     * the capability field and beacon interval, which Android doesn't pass on, and a different one would confuse the
     * server's matching.
     */
    fun phoneAp(
        bssid: String, ssid: String?, rssi: Int?, freq: Int, elts: List<Elt>?, capabilities: String, ts: Double,
        fpGate: Gate? = null, gates: AttrGates? = null,
    ): Map<String, Any?>? {
        val mac = bssid.lowercase()
        if (!isUnicast(mac)) return null
        val band = Channels.bandOf(freq)
        val ch = Channels.toChannel(freq)
        val extra = linkedMapOf<String, Any>()
        val o = linkedMapOf<String, Any?>("t" to "wifi_ap", "ts" to ts, "mac" to mac, "rssi" to rssi?.takeIf { it in -119..9 }, "freq" to freq, "band" to band)
        if (elts != null) {
            val (enc, ds) = networkStats(elts, privacy = "WEP" in capabilities)
            val (csa, primary) = chanElts(elts)
            val s = elts.firstOrNull { it.id == 0 }?.let { decodeSsid(it.info) } ?: ssid
            if (s == "") extra["hidden"] = true
            if (gates != null && gates.ap(mac, "ap", ts)) {
                val info = WifiAttrs.apAttributes(elts, null, null)
                info.remove("bsig")
                if (info.isNotEmpty()) extra["ap"] = info
            }
            o["channel"] = if (band == "6") ch else (ds?.takeIf { it != 0 } ?: primary?.takeIf { it != 0 } ?: ch)
            o["ssid"] = s?.ifEmpty { null }
            o["enc"] = enc
            o["csa"] = csa
            if (fpGate == null || fpGate(mac, "beacon", ts)) {
                val fp = WifiAttrs.fingerprint(elts)
                if (fp.isNotEmpty()) o["fp"] = linkedMapOf("wifi" to fp, "src" to "beacon")
            }
        } else {
            if (ssid.isNullOrEmpty()) extra["hidden"] = true
            o["channel"] = ch
            o["ssid"] = ssid?.ifEmpty { null }
            o["enc"] = encFromCapabilities(capabilities)
        }
        o["extra"] = extra.ifEmpty { null }
        return o
    }

    /** Android's capability string ("[RSN-PSK+SAE-CCMP][ESS]") as scapy's labels, for phones without the raw elements. */
    fun encFromCapabilities(caps: String): String {
        val out = sortedSetOf<String>()
        Regex("""\[(RSN|WPA2|WPA)-([^\]-]+)""").findAll(caps).forEach { m ->
            val akms = m.groupValues[2].split("+")
            val first = when (akms.first()) { "EAP", "EAP_SUITE_B_192" -> "802.1X"; "PSK-SHA256" -> "PSK-SHA256"; else -> akms.first() }
            if (m.groupValues[1] == "WPA") out += "WPA/$first"
            else {
                val version = if ("SAE" in akms && "PSK" in akms) "WPA3-transition" else if (akms.all { it == "SAE" }) "WPA3" else "WPA2"
                out += "$version/$first"
            }
        }
        if (out.isEmpty()) out += if ("WEP" in caps) "WEP" else "OPN"
        return out.joinToString(",")
    }
}
