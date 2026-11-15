package io.github.banaanga.sniffowireless.mobile.core

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.CodingErrorAction
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max

/*
 * LoRa, ported from sniffowireless's lora_protocols.py (profiles and the frame classifier), meshtastic.py (reading the public
 * default channel) and lora.py (the lora-sniffer firmware's serial lines and the profile hopping). Receive-only: the
 * firmware has no transmit code, and nothing here asks it to send.
 */

internal fun pyRound(x: Double, digits: Int): Double {
    if (!x.isFinite()) return x
    val r = BigDecimal(x).setScale(digits, RoundingMode.HALF_EVEN).toDouble()
    return if (r == 0.0 && (x < 0 || 1 / x < 0)) -0.0 else r  // Python keeps the sign of zero
}

/** Python's `format(x, "g")` for the numbers used here (frequencies, bandwidths): 6 significant digits, no trailing zeros. */
internal fun pyG(x: Double): String {
    if (x == 0.0) return "0"
    val s = BigDecimal(x).round(MathContext(6, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()
    return s
}

internal fun ByteArray.hex() = joinToString("") { "%02x".format(it.toInt() and 0xFF) }

internal fun hexBytes(s: String): ByteArray? {
    if (s.length % 2 != 0) return null
    val out = ByteArray(s.length / 2)
    for (i in out.indices) {
        val hi = Character.digit(s[2 * i], 16)
        val lo = Character.digit(s[2 * i + 1], 16)
        if (hi < 0 || lo < 0) return null
        out[i] = ((hi shl 4) or lo).toByte()
    }
    return out
}

/** Python's `int(s, 0)`: decimal, or 0x / 0o / 0b prefixed. */
internal fun pyInt0(raw: String): Int {
    val s = raw.trim().replace("_", "")
    val neg = s.startsWith("-")
    val t = s.removePrefix("-").removePrefix("+")
    val v = when {
        t.startsWith("0x", true) -> t.substring(2).toInt(16)
        t.startsWith("0o", true) -> t.substring(2).toInt(8)
        t.startsWith("0b", true) -> t.substring(2).toInt(2)
        t.length > 1 && t.startsWith("0") && t.any { it != '0' } -> throw NumberFormatException(raw)
        else -> t.toInt()
    }
    return if (neg) -v else v
}

data class LoraProfile(
    val name: String,
    val label: String,
    /** MHz */
    val freq: Double,
    /** kHz */
    val bw: Double,
    val sf: Int,
    /** 5..8: coding rate 4/5..4/8 */
    val cr: Int,
    val sync: Int,
    val preamble: Int = 8,
    /** The protocol expected on these settings. */
    val hint: String? = null,
) {
    fun describe() = "${pyG(freq)} MHz · SF$sf · ${pyG(bw)} kHz"
}

object LoraProfiles {
    const val SYNC_PRIVATE = 0x12
    const val SYNC_LORAWAN = 0x34
    const val SYNC_MESHTASTIC = 0x2B
    val BANDWIDTHS = listOf(7.8, 10.4, 15.6, 20.8, 31.25, 41.7, 62.5, 125.0, 250.0, 500.0)
    val PROTOCOLS = linkedMapOf("meshtastic" to "Meshtastic", "meshcore" to "MeshCore", "lorawan" to "LoRaWAN", "lora-aprs" to "LoRa APRS",
        "unknown" to "Unidentified LoRa")

    private data class Region(val start: Double, val end: Double, val spacing: Double)

    private val MESHTASTIC_REGIONS = linkedMapOf("US" to Region(902.0, 928.0, 0.0), "EU868" to Region(869.4, 869.65, 0.0),
        "EU433" to Region(433.0, 434.0, 0.0), "ANZ" to Region(915.0, 928.0, 0.0), "ANZ433" to Region(433.05, 434.79, 0.0))

    /** (bandwidth kHz, spreading factor, coding rate) */
    private val MESHTASTIC_PRESETS = linkedMapOf(
        "ShortTurbo" to Triple(500.0, 7, 5), "ShortFast" to Triple(250.0, 7, 5), "ShortSlow" to Triple(250.0, 8, 5),
        "MediumTurbo" to Triple(500.0, 9, 5), "MediumFast" to Triple(250.0, 9, 5), "MediumSlow" to Triple(250.0, 10, 5),
        "LongTurbo" to Triple(500.0, 11, 8), "LongFast" to Triple(250.0, 11, 5), "LongModerate" to Triple(125.0, 11, 8),
        "LiteFast" to Triple(125.0, 9, 5), "LiteSlow" to Triple(125.0, 10, 5), "NarrowFast" to Triple(62.5, 7, 6),
        "NarrowSlow" to Triple(62.5, 8, 6), "TinyFast" to Triple(15.6, 7, 5), "TinySlow" to Triple(15.6, 8, 6),
    )
    val meshtasticRegions: Set<String> get() = MESHTASTIC_REGIONS.keys
    val meshtasticPresets: Set<String> get() = MESHTASTIC_PRESETS.keys

    /** Default uplink channels (MHz) and the spreading factors devices use, per LoRaWAN region. */
    private val LORAWAN_PLANS: Map<String, Pair<List<Double>, IntRange>> = linkedMapOf(
        "EU868" to (listOf(868.1, 868.3, 868.5) to 7..12), "EU433" to (listOf(433.175, 433.375, 433.575) to 7..12),
        "US915" to ((0 until 8).map { pyRound(903.9 + 0.2 * it, 1) } to 7..10),
        "AU915" to ((0 until 8).map { pyRound(916.8 + 0.2 * it, 1) } to 7..10),
        "AS923" to (listOf(923.2, 923.4) to 7..12), "IN865" to (listOf(865.0625, 865.4025, 865.985) to 7..12),
        "KR920" to (listOf(922.1, 922.3, 922.5) to 7..12),
    )

    /** Semtech's time-on-air formula; [cr] is the coding rate denominator 5..8. */
    fun airtimeMs(length: Int, sf: Int, bwKhz: Double, cr: Int = 5, preamble: Int = 8, crc: Boolean = true, explicitHeader: Boolean = true): Double {
        val tsym = Math.pow(2.0, sf.toDouble()) / (bwKhz * 1000.0)
        val de = if (tsym > 0.016) 1 else 0
        val ih = if (explicitHeader) 0 else 1
        val bits = 8 * length - 4 * sf + 28 + (if (crc) 16 else 0) - 20 * ih
        val symbols = 8 + max(ceil(bits.toDouble() / (4 * (sf - 2 * de))).toInt() * cr, 0)
        return ((preamble + 4.25) * tsym + symbols * tsym) * 1000.0
    }

    /** djb2, as the Meshtastic firmware hashes a channel name to pick its frequency slot. */
    fun meshtasticHash(name: String): Long {
        var h = 5381L
        for (c in name.toByteArray(Charsets.UTF_8)) h = ((h shl 5) + h + (c.toInt() and 0xFF)) and 0xFFFFFFFFL
        return h
    }

    fun meshtasticFrequency(region: String, preset: String = "LongFast"): Double {
        val r = MESHTASTIC_REGIONS.getValue(region)
        val bw = MESHTASTIC_PRESETS.getValue(preset).first
        val slots = max(1, floor(pyRound((r.end - r.start) / (r.spacing + bw / 1000), 6)).toInt())
        return pyRound(r.start + bw / 2000 + (meshtasticHash(preset) % slots) * (bw / 1000), 4)
    }

    fun meshtasticProfile(region: String, preset: String = "LongFast"): LoraProfile {
        val (bw, sf, cr) = MESHTASTIC_PRESETS.getValue(preset)
        val name = if (preset == "LongFast") "meshtastic-${region.lowercase()}" else "meshtastic-${region.lowercase()}-${preset.lowercase()}"
        return LoraProfile(name, "Meshtastic $preset ($region)", meshtasticFrequency(region, preset), bw, sf, cr, SYNC_MESHTASTIC, 8, "meshtastic")
    }

    fun lorawanProfiles(region: String, sfs: List<Int>): List<LoraProfile> {
        val (freqs, range) = LORAWAN_PLANS.getValue(region)
        return sfs.filter { it in range }.flatMap { sf ->
            freqs.map { f ->
                LoraProfile("lorawan-${region.lowercase()}-${pyG(f)}-sf$sf", "LoRaWAN $region ${pyG(f)} MHz SF$sf", f, 125.0, sf, 5, SYNC_LORAWAN, 8, "lorawan")
            }
        }
    }

    val PROFILES: Map<String, LoraProfile> = linkedMapOf<String, LoraProfile>().apply {
        (listOf("EU868", "US", "ANZ", "EU433").map { meshtasticProfile(it) } + listOf(
            LoraProfile("meshcore-eu868", "MeshCore (EU/UK 869.618, the firmware default)", 869.618, 62.5, 8, 5, SYNC_PRIVATE, 8, "meshcore"),
            LoraProfile("meshcore-au-narrow", "MeshCore (Australia narrow)", 916.575, 62.5, 7, 7, SYNC_PRIVATE, 8, "meshcore"),
            LoraProfile("meshcore-nz-narrow", "MeshCore (New Zealand narrow)", 917.375, 62.5, 7, 5, SYNC_PRIVATE, 8, "meshcore"),
            LoraProfile("lorawan-eu868", "LoRaWAN EU868 (868.1 MHz, SF7)", 868.1, 125.0, 7, 5, SYNC_LORAWAN, 8, "lorawan"),
            LoraProfile("lorawan-us915-sb2", "LoRaWAN US915 sub-band 2 (903.9 MHz, SF7)", 903.9, 125.0, 7, 5, SYNC_LORAWAN, 8, "lorawan"),
            LoraProfile("lora-aprs-433", "LoRa APRS (433.775 MHz, SF12)", 433.775, 125.0, 12, 5, SYNC_PRIVATE, 8, "lora-aprs"),
        )).forEach { put(it.name, it) }
    }

    val REGION_DEFAULTS = linkedMapOf("EU868" to listOf("meshtastic-eu868", "meshcore-eu868", "lorawan:eu868"),
        "US915" to listOf("meshtastic-us", "lorawan:us915"), "ANZ" to listOf("meshtastic-anz", "meshcore-au-narrow", "lorawan:au915"))

    private val NAME = Regex("[A-Za-z0-9._-]{1,32}")
    private val SF_RANGE = Regex("(\\d{1,2})(?:-(\\d{1,2}))?")

    /**
     * A LORA_PROFILES list as sniffowireless reads it: names from [PROFILES], `meshtastic:<region>[:<preset>]`,
     * `lorawan:<region>[:<sf or range>]`, or `name=freq/bw/sf/cr/sync[/preamble]`. Empty means the region's defaults.
     * Throws IllegalArgumentException saying what is wrong.
     */
    fun parse(spec: String?, region: String? = "EU868"): List<LoraProfile> {
        var items = (spec ?: "").split(",").map { it.trim() }.filter { it.isNotEmpty() }
        if (items.isEmpty()) {
            val key = (region?.takeIf { it.isNotEmpty() } ?: "EU868").uppercase()
            items = REGION_DEFAULTS[key] ?: throw IllegalArgumentException("unknown LoRa region '$region'; use one of ${REGION_DEFAULTS.keys.joinToString()}")
        }
        val out = ArrayList<LoraProfile>()
        for (item in items) {
            val low = item.lowercase()
            when {
                low in PROFILES -> out += PROFILES.getValue(low)
                low.startsWith("meshtastic:") -> {
                    val parts = item.split(":")
                    val reg = parts.getOrNull(1)?.uppercase() ?: ""
                    val want = (parts.getOrNull(2) ?: "LongFast").lowercase()
                    val preset = MESHTASTIC_PRESETS.keys.firstOrNull { it.lowercase() == want }
                    require(reg in MESHTASTIC_REGIONS && preset != null && parts.size <= 3) {
                        "'$item': use meshtastic:<${MESHTASTIC_REGIONS.keys.joinToString("|")}>[:<preset>]"
                    }
                    out += meshtasticProfile(reg, preset!!)
                }
                low.startsWith("lorawan:") -> {
                    val parts = item.split(":")
                    val reg = parts.getOrNull(1)?.uppercase() ?: ""
                    val m = if (parts.size == 3) SF_RANGE.matchEntire(parts[2]) else null
                    require(reg in LORAWAN_PLANS && parts.size <= 3 && !(parts.size == 3 && m == null)) {
                        "'$item': use lorawan:<${LORAWAN_PLANS.keys.joinToString("|")}>[:<sf>] with an SF like 7 or a range like 7-12"
                    }
                    val lo = m?.groupValues?.get(1)?.toInt() ?: 7
                    val hi = m?.groupValues?.get(2)?.takeIf { it.isNotEmpty() }?.toInt() ?: lo
                    val plan = LORAWAN_PLANS.getValue(reg).second
                    val sfs = (lo..hi).filter { it in plan }
                    require(sfs.isNotEmpty()) { "'$item': $reg devices use SF${plan.first}-${plan.last}" }
                    out += lorawanProfiles(reg, sfs)
                }
                "=" in item -> {
                    val name = item.substringBefore("=")
                    val f = item.substringAfter("=").split("/")
                    require(NAME.matches(name) && f.size in 5..6) { "'$item': use name=freq/bw/sf/cr/sync[/preamble], for example mesh=868.3/125/7/5/0x34" }
                    val p = try {
                        LoraProfile(name.lowercase(), name, f[0].trim().toDouble(), f[1].trim().toDouble(), f[2].trim().toInt(), f[3].trim().toInt(),
                            pyInt0(f[4]), if (f.size == 6) f[5].trim().toInt() else 8)
                    } catch (e: NumberFormatException) {
                        throw IllegalArgumentException("'$item': the settings must be numbers")
                    }
                    require(p.freq in 150.0..960.0 && p.bw in BANDWIDTHS && p.sf in 5..12 && p.cr in 5..8 && p.sync in 0..255 && p.preamble in 6..65535) {
                        "'$item': frequency 150-960 MHz, bandwidth one of ${BANDWIDTHS.joinToString { pyG(it) }} kHz, SF 5-12, coding rate 5-8, sync 0-255"
                    }
                    out += p.copy(hint = mapOf(SYNC_MESHTASTIC to "meshtastic", SYNC_LORAWAN to "lorawan")[p.sync])
                }
                else -> throw IllegalArgumentException("unknown LoRa profile '$item'; known: ${PROFILES.keys.joinToString()}, or meshtastic:<region>[:<preset>], " +
                    "lorawan:<region>[:<sf>], name=freq/bw/sf/cr/sync")
            }
        }
        val unique = LinkedHashMap<String, LoraProfile>()
        out.forEach { unique[it.name] = it }
        return unique.values.toList()
    }
}

/** One `RX` line of the lora-sniffer firmware: the frame and the settings it was heard on. */
data class LoraRx(val freq: Double, val bw: Double, val sf: Int, val sync: Int, val rssi: Double, val snr: Double, val ferr: Double, val hex: String) {
    val bytes: ByteArray get() = hexBytes(hex)!!

    companion object {
        /** `RX,<ms>,<freq MHz>,<bw kHz>,<sf>,<sync>,<rssi>,<snr>,<freq error Hz>,<length>,<hex>`, or null if it isn't one. */
        fun parse(line: String): LoraRx? {
            val f = line.split(",")
            if (f.size != 11 || f[0] != "RX") return null
            return try {
                val hex = f[10].lowercase()
                val n = f[9].trim().toInt()
                val raw = hexBytes(hex.trim()) ?: return null
                val rx = LoraRx(f[2].trim().toDouble(), f[3].trim().toDouble(), f[4].trim().toInt(), pyInt0(f[5]), f[6].trim().toDouble(),
                    f[7].trim().toDouble(), f[8].trim().toDouble(), hex)
                if (n == raw.size && n in 1..255) rx else null
            } catch (e: NumberFormatException) {
                null
            }
        }
    }
}

/** sniffowireless's classifier: which protocol a frame is, from its clear-text structure and the settings it was heard on. */
object LoraClassifier {
    private val MC_ROUTES = listOf("transport flood", "flood", "direct", "transport direct")
    private val MC_TYPES = mapOf(0 to "request", 1 to "response", 2 to "text message", 3 to "ack", 4 to "advert", 5 to "group text",
        6 to "group data", 7 to "anonymous request", 8 to "path", 9 to "trace", 10 to "multipart", 11 to "control", 15 to "custom")
    private val MC_NODE_TYPES = mapOf(1 to "chat node", 2 to "repeater", 3 to "room server", 4 to "sensor")
    private val LW_MTYPES = listOf("join request", "join accept", "unconfirmed uplink", "unconfirmed downlink", "confirmed uplink",
        "confirmed downlink", "rejoin request", "proprietary")
    private val LEVELS = listOf(80 to "high", 65 to "medium", 50 to "low")
    private val APRS = Regex("^([A-Za-z0-9-]{1,9})>([A-Za-z0-9-]{1,9})((?:,[A-Za-z0-9*-]{1,9})*):(.*)$", RegexOption.DOT_MATCHES_ALL)
    private val APRS_KINDS = mapOf('!' to "position", '=' to "position", '/' to "position", '@' to "position", ':' to "message",
        '>' to "status", '_' to "weather", ';' to "object")

    private fun u(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u32(b: ByteArray, i: Int) = ByteBuffer.wrap(b, i, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL
    private fun u16(b: ByteArray, i: Int) = (u(b, i) or (u(b, i + 1) shl 8))

    internal fun printable(b: ByteArray): Double =
        if (b.isEmpty()) 0.0 else b.count { val x = it.toInt() and 0xFF; x in 32..126 || x == 9 || x == 10 || x == 13 }.toDouble() / b.size

    internal fun entropy(b: ByteArray): Double {
        if (b.isEmpty()) return 0.0
        val n = b.size.toDouble()
        val counts = LinkedHashMap<Byte, Int>()
        b.forEach { counts[it] = (counts[it] ?: 0) + 1 }
        return -counts.values.sumOf { c -> c / n * (ln(c / n) / ln(2.0)) }
    }

    private class R(
        val protocol: String, var score: Int, val kind: String, val src: String?, val dst: String?, val hops: Int?,
        val node: List<String?>?, val info: Map<String, Any?>, val name: String? = null,
    )

    private fun meshtastic(p: ByteArray, sync: Int?, hint: String?): R? {
        if (p.size < 17) return null
        val to = u32(p, 0)
        val frm = u32(p, 4)
        val pid = u32(p, 8)
        val flags = u(p, 12)
        val chan = u(p, 13)
        val nextHop = u(p, 14)
        val relay = u(p, 15)
        val hopLimit = flags and 0x07
        val hopStart = flags shr 5
        var score = 20 + (if (frm != 0L && frm != 0xFFFFFFFFL) 15 else 0) + (if (pid != 0L) 10 else 0) + (if (to != 0L) 5 else 0) +
            (if (hopStart == 0 || hopStart >= hopLimit) 10 else 0)
        val prior = if (sync == LoraProfiles.SYNC_MESHTASTIC) 40 else if (hint == "meshtastic") 15 else 0
        score = if (prior != 0) minOf(100, score + prior) else minOf(score, 40)
        val info = linkedMapOf<String, Any?>("id" to "%08x".format(pid), "hop_limit" to hopLimit, "channel" to "%02x".format(chan),
            "payload_bytes" to p.size - 16)
        if (hopStart != 0) {
            info["hop_start"] = hopStart
            info["hops_taken"] = max(0, hopStart - hopLimit)
            info["relay"] = "%02x".format(relay)
            info["next_hop"] = if (nextHop != 0) "%02x".format(nextHop) else null
        }
        if (flags and 0x08 != 0) info["want_ack"] = true
        if (flags and 0x10 != 0) info["via_mqtt"] = true
        val bc = to == 0xFFFFFFFFL
        return R("meshtastic", score, if (bc) "broadcast" else "direct", "!%08x".format(frm), if (bc) "broadcast" else "!%08x".format(to),
            info["hops_taken"] as Int?, if (frm != 0L && frm != 0xFFFFFFFFL) listOf("!%08x".format(frm), null, "node") else null,
            info.filterValues { it != null })
    }

    private fun meshcore(p: ByteArray, sync: Int?, hint: String?): R? {
        if (p.size < 2) return null
        val h = u(p, 0)
        val route = h and 3
        val ptype = (h shr 2) and 15
        val ver = h shr 6
        if (ptype in 12..14) return null
        var o = 1
        if (route == 0 || route == 3) o += 4
        if (o >= p.size) return null
        val hops = u(p, o) and 0x3F
        val hsize = (u(p, o) shr 6) + 1
        o += 1
        if (hsize == 4 || hops * hsize > 64 || o + hops * hsize > p.size) return null
        val path = p.copyOfRange(o, o + hops * hsize)
        val body = p.copyOfRange(o + hops * hsize, p.size)
        if (body.size > 184) return null
        var score = 20 + if (ver == 0) 10 else 0
        val info = linkedMapOf<String, Any?>("route" to MC_ROUTES[route], "hops" to hops, "hash_bytes" to hsize, "payload_bytes" to body.size)
        if (hops != 0) info["path"] = path.hex()
        val kind = MC_TYPES.getValue(ptype)
        var src: String? = null
        var dst: String? = null
        var name: String? = null
        var node: List<String?>? = null
        if (ptype == 4 && body.size >= 101) {
            val flags = u(body, 100)
            var off = 101
            val ntype = flags and 0x0F
            off += if (flags and 0x10 != 0) 8 else 0
            off += if (flags and 0x20 != 0) 2 else 0
            off += if (flags and 0x40 != 0) 2 else 0
            if (ntype in MC_NODE_TYPES && off <= body.size) {
                val text = if (flags and 0x80 != 0) String(body, off, body.size - off, Charsets.UTF_8) else null
                score += 45 + if (text != null && printable(text.toByteArray(Charsets.UTF_8)) > 0.9) 10 else 0
                name = text
                node = listOf("pk:" + body.copyOfRange(0, 4).hex(), text, MC_NODE_TYPES[ntype])
                info["node_type"] = MC_NODE_TYPES[ntype]
                info["location_shared"] = flags and 0x10 != 0
                info["public_key"] = body.copyOfRange(0, 8).hex()
                src = node[0]
            }
        } else if (ptype == 3 && body.size == 4) {
            score += 25
        } else if (ptype in listOf(0, 1, 2, 8) && body.size >= 5) {
            score += 10
            dst = "h:%02x".format(u(body, 0))
            src = "h:%02x".format(u(body, 1))
            node = listOf(src, null, "hash")
        } else if (ptype == 7 && body.size >= 36) {
            score += 10
            dst = "h:%02x".format(u(body, 0))
            node = listOf("pk:" + body.copyOfRange(1, 5).hex(), null, "node")
            src = node[0]
        } else if ((ptype == 5 || ptype == 6) && body.size >= 4) {
            score += 10
            info["channel"] = "%02x".format(u(body, 0))
        } else if (ptype == 11 && body.isNotEmpty()) {
            score += 5
            info["control"] = u(body, 0) shr 4
        }
        var prior = if (hint == "meshcore") 20 else 0
        prior += if (sync == LoraProfiles.SYNC_PRIVATE) 5 else 0
        score = if (prior != 0) minOf(100, score + prior) else minOf(score, 35)
        return R("meshcore", score, kind, src, dst ?: if (route == 0 || route == 1) "flood" else null, hops, node, info, name)
    }

    private fun lorawan(p: ByteArray, sync: Int?, hint: String?): R? {
        if (p.size < 12) return null
        val mtype = u(p, 0) shr 5
        val rfu = (u(p, 0) shr 2) and 7
        val major = u(p, 0) and 3
        if (major != 0) return null
        var score = 15 + if (rfu == 0) 10 else 0
        val info = linkedMapOf<String, Any?>("payload_bytes" to p.size)
        var src: String? = null
        var dst: String? = null
        var node: List<String?>? = null
        if (mtype == 0 && p.size == 23) {
            score += 30
            val joinEui = p.copyOfRange(1, 9).reversedArray().hex()
            val devEui = p.copyOfRange(9, 17).reversedArray().hex()
            info["join_eui"] = joinEui
            info["dev_eui"] = devEui
            src = "eui:$devEui"
            node = listOf("eui:$devEui", null, "device")
        } else if (mtype == 0 || mtype == 1) {
            score += if (mtype == 1 && (p.size == 17 || p.size == 33)) 30 else -25
        } else if (mtype in 2..5) {
            val fopts = u(p, 5) and 0x0F
            if (p.size >= 12 + fopts) {
                score += 25
                val devAddr = p.copyOfRange(1, 5).reversedArray().hex()
                val fctrl = u(p, 5)
                info["dev_addr"] = devAddr
                info["fcnt"] = u16(p, 6)
                if (p.size > 12 + fopts) info["fport"] = u(p, 8 + fopts)
                if (fctrl and 0x80 != 0) info["adr"] = true
                if (fctrl and 0x20 != 0) info["ack"] = true
                if (mtype == 2 || mtype == 4) {
                    src = devAddr
                    node = listOf(devAddr, null, "device")
                } else {
                    dst = devAddr
                }
            } else {
                score -= 25
            }
        } else if (mtype == 6 || mtype == 7) {
            score += 5
        }
        val prior = if (sync == LoraProfiles.SYNC_LORAWAN) 40 else if (hint == "lorawan") 10 else 0
        score = if (prior != 0) minOf(100, score + prior) else minOf(score, 40)
        return R("lorawan", score, LW_MTYPES[mtype], src, dst, null, node, info)
    }

    private fun aprs(p: ByteArray, sync: Int?, hint: String?): R? {
        if (p.size < 8 || u(p, 0) != '<'.code || u(p, 1) != 0xFF || u(p, 2) != 0x01) return null
        val text = String(p, 3, p.size - 3, Charsets.ISO_8859_1)
        val m = APRS.matchEntire(text) ?: return R("lora-aprs", 60, "frame", null, null, null, null, emptyMap())
        val (src, dst, path, data) = m.destructured
        return R("lora-aprs", 95, data.firstOrNull()?.let { APRS_KINDS[it] } ?: "frame", src, dst, path.count { it == ',' }.takeIf { it != 0 },
            listOf(src, null, "station"), mapOf("path" to path.trimStart(',').ifEmpty { null }))
    }

    /**
     * {protocol, label, confidence, level, kind, src, dst, hops, name, node: [address, name, kind], info}, as sniffowireless's
     * lora_protocols.classify returns it. A frame no protocol fits comes back as "unknown".
     */
    fun classify(payload: ByteArray, sync: Int? = null, hint: String? = null): Map<String, Any?> {
        var best: R? = null
        for (fn in listOf(::aprs, ::meshtastic, ::lorawan, ::meshcore)) {
            val r = try {
                fn(payload, sync, hint)
            } catch (e: IndexOutOfBoundsException) {
                null
            }
            if (r != null && (best == null || r.score > best.score)) best = r
        }
        if (best == null || best.score < 50) {
            val pr = printable(payload)
            return linkedMapOf("protocol" to "unknown", "label" to LoraProfiles.PROTOCOLS["unknown"], "confidence" to 0, "level" to "none",
                "kind" to if (pr >= 0.9 && payload.size >= 4) "plain text" else "unidentified", "src" to null, "dst" to null, "hops" to null,
                "name" to null, "node" to null, "info" to linkedMapOf("payload_bytes" to payload.size, "entropy" to pyRound(entropy(payload), 1)))
        }
        val level = LEVELS.firstOrNull { best.score >= it.first }?.second ?: "low"
        return linkedMapOf("protocol" to best.protocol, "label" to LoraProfiles.PROTOCOLS[best.protocol], "confidence" to best.score, "level" to level,
            "kind" to best.kind, "src" to best.src, "dst" to best.dst, "hops" to best.hops, "name" to best.name, "node" to best.node, "info" to best.info)
    }
}

/**
 * Meshtastic packets meant to be read by anyone (sniffowireless's meshtastic.py): those on a channel with the public default
 * key every device ships with, and those sent without encryption. Channels with their own key and direct messages stay
 * unreadable.
 */
object Meshtastic {
    private val DEFAULT_KEY = hexBytes("d4f1bb3a20290759f0bcffabcf4e6901")!!
    val PORTS = mapOf(1 to "text message", 3 to "position", 4 to "node info", 5 to "routing", 6 to "admin", 8 to "waypoint",
        10 to "detection sensor", 32 to "reply", 34 to "paxcounter", 65 to "store and forward", 66 to "range test", 67 to "telemetry",
        70 to "traceroute", 71 to "neighbor info", 73 to "map report")
    private val HW_MODELS = mapOf(4 to "T-Beam", 5 to "Heltec V2.0", 7 to "T-Echo", 9 to "RAK4631", 10 to "Heltec V2.1", 12 to "LilyGO T-LoRa V2.1",
        13 to "Heltec V1", 39 to "T-Deck", 43 to "Heltec V3", 44 to "Heltec WSL V3", 48 to "Heltec Wireless Tracker", 50 to "Seeed Xiao ESP32S3",
        51 to "Seeed Wio Tracker L1", 47 to "RP2040 LoRa", 255 to "Private hardware")
    private val ROLES = mapOf(0 to "client", 1 to "client mute", 2 to "router", 3 to "router client", 4 to "repeater", 5 to "tracker",
        6 to "sensor", 7 to "TAK", 8 to "client hidden", 9 to "lost and found", 10 to "TAK tracker", 11 to "router late", 12 to "client base")

    private class BadProto : Exception()

    fun aesCtr(key: ByteArray, nonce: ByteArray, data: ByteArray): ByteArray =
        Cipher.getInstance("AES/CTR/NoPadding").run {
            init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(nonce))
            doFinal(data)
        }

    private fun varint(b: ByteArray, start: Int): Pair<Long, Int> {
        var v = 0L
        var shift = 0
        var i = start
        while (true) {
            if (i >= b.size || shift > 63) throw BadProto()
            val x = b[i].toInt() and 0xFF
            i++
            v = v or ((x and 0x7F).toLong() shl shift)
            if (x and 0x80 == 0) return v to i
            shift += 7
        }
    }

    /** {field: Long (varint) or ByteArray (fixed and length-delimited)}; the last occurrence wins. */
    internal fun fields(b: ByteArray): Map<Int, Any> {
        val out = HashMap<Int, Any>()
        var i = 0
        while (i < b.size) {
            val (tag, j) = varint(b, i)
            i = j
            // Field numbers past Int's range (garbage) are kept under -1, which nothing reads, as Python's dict would.
            val num = (tag ushr 3).let { if (it in 1..Int.MAX_VALUE.toLong()) it.toInt() else -1 }
            val wire = (tag and 7).toInt()
            if (tag ushr 3 == 0L) throw BadProto()
            when (wire) {
                0 -> varint(b, i).let { (v, k) -> out[num] = v; i = k }
                1 -> { out[num] = b.copyOfRange(i, minOf(b.size, i + 8)); i += 8 }
                2 -> {
                    val (n, k) = varint(b, i)
                    i = k
                    if (n < 0 || i + n > b.size) throw BadProto()
                    out[num] = b.copyOfRange(i, i + n.toInt())
                    i += n.toInt()
                }
                5 -> { out[num] = b.copyOfRange(i, minOf(b.size, i + 4)); i += 4 }
                else -> throw BadProto()
            }
            if (i > b.size) throw BadProto()
        }
        return out
    }

    private fun s32(v: Long): Long = (v and 0xFFFFFFFFL).let { if (it and 0x80000000L != 0L) it - (1L shl 32) else it }
    private fun f32(raw: Any?): Double? =
        if (raw is ByteArray && raw.size == 4) ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).float.toDouble() else null
    private fun i32(raw: Any?): Long? =
        if (raw is ByteArray && raw.size == 4) ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).int.toLong() else null

    /** Python's str.isprintable for one code point. */
    private fun printable(cp: Int): Boolean = when (Character.getType(cp).toByte()) {
        Character.CONTROL, Character.FORMAT, Character.SURROGATE, Character.PRIVATE_USE, Character.UNASSIGNED,
        Character.LINE_SEPARATOR, Character.PARAGRAPH_SEPARATOR -> false
        Character.SPACE_SEPARATOR -> cp == ' '.code
        else -> true
    }

    private fun text(raw: Any?, limit: Int = 240): String? {
        if (raw !is ByteArray) return null
        val t = try {
            Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(raw)).toString()
        } catch (e: java.nio.charset.CharacterCodingException) {
            throw BadProto()
        }
        val cps = t.codePoints().toArray()
        if (cps.isEmpty() || cps.count { printable(it) || it == '\n'.code || it == '\t'.code } < cps.size * 0.9) throw BadProto()
        val kept = cps.filter { printable(it) || it == '\n'.code || it == '\t'.code }.take(limit)
        return String(kept.toIntArray(), 0, kept.size)
    }

    private fun position(b: ByteArray): Map<String, Any?> {
        val f = fields(b)
        val lat = i32(f[1])
        val lon = i32(f[2])
        val out = linkedMapOf<String, Any?>()
        if (lat != null && lon != null && (lat != 0L || lon != 0L) && kotlin.math.abs(lat) <= 900000000 && kotlin.math.abs(lon) <= 1800000000) {
            out["lat"] = pyRound(lat / 1e7, 6)
            out["lon"] = pyRound(lon / 1e7, 6)
        }
        (f[3] as? Long)?.let { out["alt"] = s32(it) }
        (f[19] as? Long)?.let { out["sats"] = it }
        (f[23] as? Long)?.let { out["precision_bits"] = it }
        (f[15] as? Long)?.let { out["speed"] = it }
        if (out.isEmpty()) throw BadProto()
        return out
    }

    private fun user(b: ByteArray): Map<String, Any?> {
        val f = fields(b)
        val out = linkedMapOf<String, Any?>()
        for ((key, num) in listOf("long_name" to 2, "short_name" to 3, "id" to 1)) {
            val t = if (num in f) text(f[num], 40) else null
            if (!t.isNullOrEmpty()) out[key] = t
        }
        (f[5] as? Long)?.let { out["hardware"] = HW_MODELS[it.toInt()] ?: "model $it" }
        (f[7] as? Long)?.let { out["role"] = ROLES[it.toInt()] ?: "role $it" }
        if (out.isEmpty()) throw BadProto()
        return out
    }

    private fun telemetry(b: ByteArray): Map<String, Any?> {
        val f = fields(b)
        val out = linkedMapOf<String, Any?>()
        val dev = f[2]
        val env = f[3]
        if (dev is ByteArray) {
            val m = fields(dev)
            for ((key, num, isInt) in listOf(Triple("battery", 1, true), Triple("voltage", 2, false), Triple("channel_util", 3, false),
                Triple("air_util_tx", 4, false), Triple("uptime", 5, true))) {
                if (isInt) (m[num] as? Long)?.let { out[key] = it }
                else f32(m[num])?.takeIf { it.isFinite() }?.let { out[key] = pyRound(it, 2) }
            }
        } else if (env is ByteArray) {
            val m = fields(env)
            for ((key, num) in listOf("temperature" to 1, "humidity" to 2, "pressure" to 3)) {
                f32(m[num])?.takeIf { it.isFinite() }?.let { out[key] = pyRound(it, 1) }
            }
        }
        if (out.isEmpty()) throw BadProto()
        return out
    }

    private fun waypoint(b: ByteArray): Map<String, Any?> {
        val f = fields(b)
        val out = linkedMapOf<String, Any?>()
        val lat = i32(f[2])
        val lon = i32(f[3])
        if (lat != null && lon != null) {
            out["lat"] = pyRound(lat / 1e7, 6)
            out["lon"] = pyRound(lon / 1e7, 6)
        }
        for ((key, num) in listOf("name" to 6, "description" to 7)) {
            if (num in f) text(f[num], 100)?.takeIf { it.isNotEmpty() }?.let { out[key] = it }
        }
        if (out.isEmpty()) throw BadProto()
        return out
    }

    /** The Data message as {port, port_name, ...contents}, or null when [plain] isn't one (wrong key, or none). */
    fun decodeData(plain: ByteArray): Map<String, Any?>? = try {
        val f = fields(plain)
        val port = (f[1] as? Long)?.takeIf { it in 0..65535 }?.toInt()
        val body = f[2]
        if (port == null || port !in PORTS || body !is ByteArray) {
            null
        } else {
            val out = linkedMapOf<String, Any?>("port" to port, "port_name" to PORTS[port])
            when (port) {
                1 -> out["text"] = text(body)?.takeIf { it.isNotEmpty() } ?: throw BadProto()
                3 -> out["position"] = position(body)
                4 -> out["user"] = user(body)
                67 -> out["telemetry"] = telemetry(body)
                8 -> out["waypoint"] = waypoint(body)
            }
            out
        }
    } catch (e: BadProto) {
        null
    }

    /** A packet (16-byte header + Data) that the public default key, or no key at all, opens; null when it is private. */
    fun decode(packet: ByteArray): Map<String, Any?>? {
        if (packet.size < 18) return null
        val bb = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        val pid = bb.getInt(8).toLong() and 0xFFFFFFFFL
        val frm = bb.getInt(4)
        val body = packet.copyOfRange(16, packet.size)
        decodeData(body)?.let { return it + ("encrypted" to false) }
        val nonce = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN).putLong(pid).putInt(frm).putInt(0).array()
        return decodeData(aesCtr(DEFAULT_KEY, nonce, body))?.let { it + ("encrypted" to true) }
    }
}

/**
 * Which LoRa profiles to hop over and for how long on each: this sensor's own list, or what the server set for it
 * (sniffowireless's lora.LoraControl, Settings → LoRa there), applied once per new revision.
 */
class LoraControl(private val log: (String, String) -> Unit = { _, _ -> }) {
    data class Plan(val profiles: String?, val hopInterval: Double?)

    @Volatile var rev = 0
        private set
    @Volatile var override: Plan? = null
        private set
    private var rejected: Int? = null
    var onChange: (() -> Unit)? = null

    fun effective(own: List<LoraProfile>, ownHop: Double): Pair<List<LoraProfile>, Double> {
        val o = override
        val profiles = o?.profiles?.let { runCatching { LoraProfiles.parse(it) }.getOrNull() } ?: own
        return profiles to (o?.hopInterval ?: ownHop)
    }

    fun applyRemote(conf: Any?) {
        if (conf !is Map<*, *>) return
        val r = (conf["rev"] as? Number)?.takeIf { it is Int || it is Long }?.toInt() ?: return
        if (r == rev || r == rejected) return
        try {
            override = clean(conf)
            rev = r
            log("INFO", "LoRa settings from the server applied (revision $r)")
            onChange?.invoke()
        } catch (e: IllegalArgumentException) {
            rejected = r
            log("WARNING", "Ignoring LoRa settings from the server (revision $r): ${e.message}")
        }
    }

    companion object {
        const val MAX_PROFILES = 64

        fun clean(o: Map<*, *>): Plan? {
            val spec = o["profiles"]
            var profiles: String? = null
            if (spec != null && spec != "") {
                require(spec is String && spec.length <= 1000) { "The profile list is too long" }
                val found = LoraProfiles.parse(spec.trim())
                require(found.isNotEmpty()) { "The profile list is empty" }
                require(found.size <= MAX_PROFILES) { "That is ${found.size} profiles; the most is $MAX_PROFILES" }
                profiles = spec.trim()
            }
            val hop = when (val h = o["hop_interval"]) {
                null, "" -> null
                is Number -> h.toDouble()
                is String -> h.trim().toDoubleOrNull() ?: throw IllegalArgumentException("The hop time is a number of seconds")
                else -> throw IllegalArgumentException("The hop time is a number of seconds")
            }?.also { require(it in 0.5..600.0) { "The time on each profile must be between 0.5 and 600 seconds" } }
            if (profiles == null && hop == null) return null
            return Plan(profiles, hop)
        }
    }
}

/** Lines to and from the sniffer: the serial port in the app, a fake in the tests. */
interface LineLink {
    fun writeLine(text: String)

    /** One line without its line end, or null when none arrived within [timeoutMs]. */
    fun readLine(timeoutMs: Long): String?
}

class LoraException(message: String) : Exception(message)

/**
 * Drives a board running the lora-sniffer firmware (sniffowireless's lora.LoraSensor.run): checks that it is one, then hops
 * over the profiles, telling the board each one's settings, and hands every frame to [onFrame] as the observation
 * sniffowireless's remote sensors send (`lora_frame`). The board never transmits.
 */
class LoraSniffer(
    private val link: LineLink,
    private val profiles: () -> Pair<List<LoraProfile>, Double>,
    private val rev: () -> Int = { 0 },
    private val onStatus: (Map<String, Any?>) -> Unit = {},
    private val onFrame: (obs: Map<String, Any?>, rx: LoraRx, profile: String) -> Unit,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    @Volatile var stopped = false
    @Volatile var current: LoraProfile? = null
        private set
    var packets = 0L
        private set
    var crcErrors = 0L
        private set

    fun handshake(): String {
        onStatus(mapOf("state" to "connecting", "error" to null))
        repeat(HANDSHAKE_TRIES) {
            link.writeLine("PING")
            val end = clock() + 2000
            while (clock() < end && !stopped) {
                val line = link.readLine(500)
                if (line != null && (line == "PONG" || line.startsWith("READY,lora-sniffer"))) return line
            }
        }
        throw LoraException("This device did not answer as the lora-sniffer firmware. It may run other firmware (flash it from " +
            "sniffowireless: Settings → LoRa flasher), or another app may be using it")
    }

    /** The firmware version from INFO (1 for boards flashed before that field existed), or null. */
    fun readVersion(): Int? {
        link.writeLine("INFO")
        val end = clock() + 1500
        while (clock() < end && !stopped) {
            val line = link.readLine(300) ?: continue
            if (line.startsWith("INFO,lora-sniffer")) {
                val f = line.split(",")
                return f.getOrNull(12)?.takeIf { s -> s.isNotEmpty() && s.all(Char::isDigit) }?.toInt() ?: 1
            }
            handle(line)
        }
        return null
    }

    fun configure(p: LoraProfile): Boolean {
        link.writeLine("CFG,${pyG(p.freq)},${pyG(p.bw)},${p.sf},${p.cr},0x%02X,${p.preamble}".format(p.sync))
        val end = clock() + CONFIG_TIMEOUT_MS
        while (clock() < end && !stopped) {
            val line = link.readLine(500) ?: continue
            if (line.startsWith("OK,CFG")) return true
            if (line.startsWith("ERR,CFG")) return false
            handle(line)
        }
        if (stopped) return false
        throw LoraException("The sniffer did not answer a settings change")
    }

    /** The profile a frame was heard on, from the settings the sniffer reports with it. */
    fun match(rx: LoraRx): String {
        for (p in profiles().first + LoraProfiles.PROFILES.values) {
            if (kotlin.math.abs(p.freq - rx.freq) < 0.002 && p.sf == rx.sf && kotlin.math.abs(p.bw - rx.bw) < 0.5 && p.sync == rx.sync) return p.name
        }
        return current?.name ?: "custom"
    }

    fun handle(line: String) {
        val rx = LoraRx.parse(line)
        if (rx != null) {
            packets++
            val profile = match(rx)
            onFrame(linkedMapOf("t" to "lora_frame", "ts" to clock() / 1000.0, "mac" to "-", "profile" to profile, "freq" to rx.freq, "bw" to rx.bw,
                "sf" to rx.sf, "sync" to rx.sync, "rssi" to rx.rssi, "snr" to rx.snr, "ferr" to rx.ferr, "hex" to rx.hex), rx, profile)
            return
        }
        if (line.startsWith("STAT,")) {
            val f = line.split(",")
            if (f.size == 4 && f[3].isNotEmpty() && f[3].all(Char::isDigit)) {
                crcErrors = f[3].toLong()
                onStatus(mapOf("packets" to packets, "crc_errors" to crcErrors))
            }
        }
    }

    /** Runs until [stopped] or the board stops answering (LoraException / the link's own errors). */
    fun run() {
        val first = handshake()
        onStatus(mapOf("state" to "listening", "error" to null, "firmware" to "lora-sniffer", "warning" to null, "fw_version" to readVersion(),
            "note" to first.takeIf { it.startsWith("READY") }))
        val refused = ArrayList<String>()
        var i = 0
        var seenRev = rev()
        var lastHeard = clock()
        while (!stopped) {
            val (list, hop) = profiles()
            if (rev() != seenRev) {
                refused.clear()
                i = 0
                seenRev = rev()
            }
            val p = list[i % list.size]
            current = p
            i++
            if (!configure(p)) {
                if (stopped) return
                if (p.name !in refused) refused += p.name
                onStatus(mapOf("warning" to "The sniffer refused ${refused.joinToString()}: the frequency may be outside its band"))
                if (refused.size >= list.size) throw LoraException("The sniffer refused every profile; check the LoRa profiles against the board's frequency band")
                continue
            }
            onStatus(mapOf("warning" to if (refused.isEmpty()) null else "The sniffer refused ${refused.joinToString()}: the frequency may be outside its band",
                "channel" to "${pyG(p.freq)} MHz SF${p.sf}", "profile" to p.name, "profiles" to list.map { it.name }, "lora_rev" to rev()))
            val dwellEnd = clock() + if (list.size > 1) (hop * 1000).toLong() else Long.MAX_VALUE / 4
            while (!stopped && clock() < dwellEnd && rev() == seenRev && profiles().first.map { it.name } == list.map { it.name }) {
                val line = link.readLine(1000)
                val now = clock()
                if (line != null) {
                    lastHeard = now
                    handle(line)
                } else if (now - lastHeard > SILENCE_LIMIT_MS) {
                    throw LoraException("The sniffer stopped sending (unplugged, or reset?)")
                }
            }
        }
    }

    companion object {
        const val BAUD_RATE = 115200
        const val HANDSHAKE_TRIES = 4
        const val CONFIG_TIMEOUT_MS = 3000L
        /** The firmware prints a STAT line every 10 s: this long without any line means the board is gone. */
        const val SILENCE_LIMIT_MS = 45_000L
    }
}
