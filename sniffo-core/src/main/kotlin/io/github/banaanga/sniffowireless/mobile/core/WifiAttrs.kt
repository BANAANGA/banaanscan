package io.github.banaanga.sniffowireless.mobile.core

import java.security.MessageDigest

/** One information element: its id and body. */
class Elt(val id: Int, val info: ByteArray)

/**
 * What Wi-Fi information elements say: ported from sniffowireless's identify.py (wifi_fingerprint, parse_wps, parse_p2p)
 * and wifiattrs.py (ap_attributes, client_caps, parse_rsn, parse_rnr). The results are the same maps sniffowireless puts in
 * an observation's "fp" and "extra", so its server reads them as from one of its own sensors.
 */
object WifiAttrs {
    private val RSN_AKM = mapOf(1 to "802.1X", 2 to "PSK", 3 to "FT-802.1X", 4 to "FT-PSK", 5 to "802.1X-SHA256", 6 to "PSK-SHA256",
        8 to "SAE", 9 to "FT-SAE", 11 to "Suite B", 12 to "Suite B 192", 14 to "FILS-SHA256", 15 to "FILS-SHA384",
        16 to "FT-FILS-SHA256", 17 to "FT-FILS-SHA384", 18 to "OWE", 19 to "FT-PSK-SHA384", 20 to "PSK-SHA384",
        22 to "FT-802.1X-SHA384", 23 to "802.1X-SHA384", 24 to "SAE-EXT", 25 to "FT-SAE-EXT")
    private val RSN_CIPHER = mapOf(0 to "none", 1 to "WEP-40", 2 to "TKIP", 4 to "CCMP", 5 to "WEP-104", 6 to "BIP-CMAC", 8 to "GCMP",
        9 to "GCMP-256", 10 to "CCMP-256", 11 to "BIP-GMAC", 12 to "BIP-GMAC-256", 13 to "BIP-CMAC-256")
    private val PSK_LIKE = setOf("PSK", "FT-PSK", "PSK-SHA256", "PSK-SHA384", "FT-PSK-SHA384")
    private val SAE_LIKE = setOf("SAE", "FT-SAE", "SAE-EXT", "FT-SAE-EXT")
    private val EXTCAP_BITS = sortedMapOf(12 to "proxy ARP", 19 to "802.11v BSS transition", 22 to "multiple BSSID",
        23 to "timing measurement", 31 to "interworking", 37 to "TDLS", 46 to "WNM notification", 62 to "operating mode notification",
        70 to "FTM responder", 71 to "FTM initiator", 72 to "FILS", 84 to "beacon protection")
    private val LEGACY_RATES = setOf(1.0, 2.0, 5.5, 11.0)
    private val WFA_TYPES = mapOf(0x09 to "Wi-Fi Direct (P2P)", 0x16 to "MBO/OCE (agile multiband)", 0x10 to "Hotspot 2.0 / Passpoint",
        0x1C to "OWE transition", 0x12 to "OSEN")
    private val ARUBA_SUBTYPES = mapOf(1 to "CAC", 2 to "Mesh", 3 to "AP name", 4 to "ARM (adaptive radio management)", 5 to "SLB",
        6 to "SJ loop protect", 7 to "Auto mesh", 8 to "LCI", 9 to "GPS ellipse", 10 to "AP health")
    private const val AP_NAME_MAX = 48

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int = 0) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun le32(b: ByteArray, i: Int) = le16(b, i).toLong() or (le16(b, i + 2).toLong() shl 16)
    private fun ouiOf(b: ByteArray) = (0 until 3).joinToString(":") { "%02x".format(u8(b, it)) }
    private fun mac(b: ByteArray, at: Int) = (0 until 6).joinToString(":") { "%02x".format(u8(b, at + it)) }
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun ByteArray.slice(from: Int, to: Int = size) = copyOfRange(from.coerceAtMost(size), to.coerceIn(from.coerceAtMost(size), size))

    // ------------------------------------------------------------------------------------------------- RSN and friends
    private fun cipherName(sel: ByteArray): String {
        val t = u8(sel, 3)
        return if (ouiOf(sel) == "00:0f:ac") RSN_CIPHER[t] ?: "cipher $t" else "vendor ${ouiOf(sel)}/$t"
    }

    private fun akmName(sel: ByteArray): String {
        val t = u8(sel, 3)
        return when (ouiOf(sel)) {
            "00:0f:ac" -> RSN_AKM[t] ?: "akm $t"
            "50:6f:9a" -> if (t == 2) "DPP" else "vendor 50:6f:9a/$t"
            "00:40:96" -> if (t == 0) "CCKM" else "vendor 00:40:96/$t"
            else -> "vendor ${ouiOf(sel)}/$t"
        }
    }

    fun parseRsn(info: ByteArray): Map<String, Any>? {
        if (info.size < 8) return null
        val group = cipherName(info.slice(2, 6))
        var i = 6
        var n = le16(info, i)
        i += 2
        val pairwise = (0 until minOf(n, 8)).filter { i + 4 * it + 4 <= info.size }.map { cipherName(info.slice(i + 4 * it, i + 4 * it + 4)) }
        i += 4 * n
        if (i + 2 > info.size) return null
        n = le16(info, i)
        i += 2
        val akm = (0 until minOf(n, 12)).filter { i + 4 * it + 4 <= info.size }.map { akmName(info.slice(i + 4 * it, i + 4 * it + 4)) }
        i += 4 * n
        val caps = if (i + 2 <= info.size) le16(info, i) else 0
        val pmf = if (caps and 0x40 != 0) "required" else if (caps and 0x80 != 0) "optional" else "off"
        val out = linkedMapOf<String, Any>("akm" to akm, "pairwise" to pairwise, "group" to group, "pmf" to pmf)
        if (akm.isNotEmpty() && akm.any { it in SAE_LIKE } && akm.any { it in PSK_LIKE }) out["transition"] = true
        return out
    }

    fun parseRnr(info: ByteArray, limit: Int = 12): List<Map<String, Any>> {
        val out = ArrayList<Map<String, Any>>()
        var i = 0
        while (i + 4 <= info.size && out.size < limit) {
            val hdr = le16(info, i)
            val count = ((hdr shr 4) and 0xF) + 1
            val tlen = (hdr shr 8) and 0xFF
            val opclass = u8(info, i + 2)
            val chan = u8(info, i + 3)
            i += 4
            if (tlen == 0) break
            repeat(count) {
                val ent = info.slice(i, i + tlen)
                i += tlen
                if (ent.size < tlen) return out
                if (tlen in setOf(7, 8, 11, 12, 13, 16) && ent.size >= 7 && out.size < limit) {
                    out += linkedMapOf("bssid" to mac(ent, 1), "ch" to chan, "oc" to opclass)
                }
            }
        }
        return out
    }

    /** (configured?, setup locked?) from a WPS element body after its 00:50:f2:04 prefix. */
    private fun wpsState(info: ByteArray): Pair<Boolean?, Boolean?> {
        var state: Boolean? = null
        var locked: Boolean? = null
        var i = 0
        while (i + 4 <= info.size) {
            val t = be16(info, i)
            val ln = be16(info, i + 2)
            val v = info.slice(i + 4, i + 4 + ln)
            i += 4 + ln
            if (t == 0x1044 && v.isNotEmpty()) state = u8(v, 0) == 2
            else if (t == 0x1057 && v.isNotEmpty()) locked = u8(v, 0) != 0
        }
        return state to locked
    }

    fun beaconSignature(elts: List<Elt>, capability: Int?, beaconTu: Int?): String {
        val parts = elts.map { if (it.id == 221) "221-" + hex(it.info.slice(0, 4)) else it.id.toString() } +
            "cap${capability ?: ""}" + "tu${beaconTu ?: ""}"
        val d = MessageDigest.getInstance("SHA-1").digest(parts.joinToString("|").toByteArray())
        return hex(d).substring(0, 12)
    }

    private fun bits(info: ByteArray): Set<Int> {
        val s = HashSet<Int>()
        info.forEachIndexed { o, v -> for (b in 0 until 8) if ((v.toInt() shr b) and 1 == 1) s += o * 8 + b }
        return s
    }

    /** Text from bytes as Python's decode("utf-8", "replace") does. */
    private fun utf8(b: ByteArray) = String(b, Charsets.UTF_8)

    /** Python's str.isprintable() for one code point: no controls, separators other than the plain space, or unassigned. */
    fun isPrintable(cp: Int): Boolean {
        if (cp == ' '.code) return true
        return when (Character.getType(cp)) {
            Character.CONTROL.toInt(), Character.FORMAT.toInt(), Character.SURROGATE.toInt(), Character.PRIVATE_USE.toInt(),
            Character.UNASSIGNED.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt(),
            Character.SPACE_SEPARATOR.toInt() -> false
            else -> true
        }
    }

    fun printable(s: String): String = buildString { s.codePoints().forEach { if (isPrintable(it)) appendCodePoint(it) } }

    /** Python's str.strip(): Unicode whitespace at both ends. */
    fun pyStrip(s: String): String = s.trim { Character.isWhitespace(it) || Character.isSpaceChar(it) || it == '\u001c' || it == '\u001d' || it == '\u001e' || it == '\u001f' || it == '\u0085' }

    private fun cleanName(raw: ByteArray): String? {
        val s = printable(pyStrip(utf8(raw).replace("\u0000", "")))
        return s.take(AP_NAME_MAX).ifEmpty { null }
    }

    /** (vendor, name, Aruba subtype) from a Ubiquiti or Aruba vendor element, or null. */
    private fun vendorApName(info: ByteArray): Triple<String, String?, Int>? {
        val oui = ouiOf(info)
        if (oui == "00:15:6d" && info.size >= 5 && u8(info, 3) == 1) return Triple("Ubiquiti", cleanName(info.slice(4)), 1)
        if (oui == "00:0b:86" && info.size >= 5) {
            val sub = u8(info, 4)
            return Triple("Aruba", if (sub == 3 && info.size >= 7) cleanName(info.slice(6)) else null, sub)
        }
        return null
    }

    /** What a beacon / probe response says about the access point: sniffowireless's extra["ap"]. */
    fun apAttributes(elts: List<Elt>, beaconTu: Int?, capability: Int?): MutableMap<String, Any> {
        val out = linkedMapOf<String, Any>("bsig" to beaconSignature(elts, capability, beaconTu))
        val roaming = ArrayList<String>()
        val widths = ArrayList<Int>()
        for (e in elts) {
            val info = e.info
            when {
                e.id == 7 && info.size >= 3 -> {
                    val a = u8(info, 0)
                    val b = u8(info, 1)
                    if (a in 0x41..0x5A && b in 0x41..0x5A) {
                        out["country"] = "${a.toChar()}${b.toChar()}"
                        val env = u8(info, 2)
                        if (env == 0x4F || env == 0x49) out["country_env"] = if (env == 0x49) "indoor" else "outdoor"
                    }
                }
                e.id == 5 && info.size >= 2 && u8(info, 1) != 0 -> out["dtim"] = u8(info, 1)   // TIM: DTIM period (server 34.2)
                e.id == 11 && info.size >= 5 -> out["load"] = linkedMapOf("sta" to le16(info, 0), "util" to Math.round(u8(info, 2) * 100.0 / 255).toInt())
                e.id == 48 -> parseRsn(info)?.let { out["rsn"] = it }
                e.id == 54 && info.size >= 3 -> roaming += "11r"
                e.id == 70 -> roaming += "11k"
                e.id == 127 -> {
                    val bits = bits(info)
                    if (19 in bits) roaming += "11v"
                    if (22 in bits) out["mbssid"] = true
                    if (70 in bits) out["ftm"] = true
                    if (84 in bits) out["beacon_protection"] = true
                }
                e.id == 71 -> out["mbssid"] = true
                e.id == 107 -> out["interworking"] = true
                e.id == 114 && info.isNotEmpty() -> out["mesh_id"] = utf8(info.slice(0, 32))
                e.id == 201 -> {
                    val n = parseRnr(info)
                    if (n.isNotEmpty()) {
                        @Suppress("UNCHECKED_CAST")
                        val all = ((out["rnr"] as? List<Map<String, Any>>).orEmpty() + n).take(12)
                        out["rnr"] = all
                    }
                }
                e.id == 221 && info.size >= 4 && ouiOf(info) in setOf("00:15:6d", "00:0b:86") -> {
                    val (vendor, name, sub) = vendorApName(info) ?: Triple(null, null, null)
                    if (vendor == "Aruba" && sub != null && sub in ARUBA_SUBTYPES) {
                        @Suppress("UNCHECKED_CAST")
                        val list = (out.getOrPut("aruba") { ArrayList<String>() } as MutableList<String>)
                        val label = ARUBA_SUBTYPES.getValue(sub!!)
                        if (label !in list) list += label
                    }
                    if (name != null) { out["ap_name"] = name; out["ap_name_src"] = vendor!! }
                }
                e.id == 221 && info.size >= 4 -> {
                    val oui = ouiOf(info)
                    val vtype = u8(info, 3)
                    if (oui == "00:50:f2" && vtype == 1) out["wpa1"] = true
                    else if (oui == "00:50:f2" && vtype == 4) {
                        val (state, locked) = wpsState(info.slice(4))
                        if (state != null) out["wps_configured"] = state
                        if (locked == true) out["wps_locked"] = true
                    }
                }
                // The channel width it announces (sniffowireless 34.1): HT (40), VHT (80 / 160), HE 6 GHz and EHT (up to 320)
                e.id == 61 && info.size >= 2 -> if (u8(info, 1) and 0x03 in setOf(1, 3) && u8(info, 1) and 0x04 != 0) widths += 40
                e.id == 192 && info.size >= 3 -> {
                    val cw = u8(info, 0)
                    val c0 = u8(info, 1)
                    val c1 = u8(info, 2)
                    if (cw == 1) widths += if (c1 != 0 && (Math.abs(c1 - c0) == 8 || Math.abs(c1 - c0) > 16)) 160 else 80
                    else if (cw == 2 || cw == 3) widths += 160
                }
                e.id == 255 && info.isNotEmpty() -> {
                    val ext = u8(info, 0)
                    if (ext == 36 && info.size >= 5) {
                        val params = le32(info, 1)
                        if (params and 0x80000000L == 0L) out["bss_color"] = ((params shr 24) and 0x3F).toInt()
                        val at = 7 + (if (params and (1L shl 14) != 0L) 3 else 0) + (if (params and (1L shl 15) != 0L) 1 else 0)
                        if (params and (1L shl 17) != 0L && info.size >= at + 2) widths += listOf(20, 40, 80, 160)[u8(info, at + 1) and 0x03]
                    } else if (ext == 106 && info.size >= 7 && u8(info, 1) and 0x01 != 0) {
                        widths += listOf(20, 40, 80, 160, 320, 20, 20, 20)[u8(info, 6) and 0x07]
                    } else if (ext == 107) out["mlo"] = true
                }
            }
        }
        if (roaming.isNotEmpty()) out["roaming"] = roaming.toSortedSet().toList()
        if (beaconTu != null && beaconTu != 0) out["beacon_tu"] = beaconTu
        // The lowest basic rate (the rate beacons go at; above 54 Mb/s a byte is a BSS membership selector) and the beacon's
        // size: MAC header, timestamp + interval + capabilities, the elements and the FCS. The server works out beacon airtime.
        val basic = elts.filter { it.id == 1 || it.id == 50 }.flatMap { e -> e.info.map { it.toInt() and 0xFF } }
            .filter { it and 0x80 != 0 && (it and 0x7F) in 1..108 }.map { (it and 0x7F) / 2.0 }
        basic.minOrNull()?.let { out["basic_rate"] = it }
        widths.maxOrNull()?.let { out["width"] = it }
        out["beacon_len"] = 40 + elts.sumOf { 2 + it.info.size }
        return out
    }

    /** What a client's capability elements say it can do: sniffowireless's fp["wifi"]["capinfo"]. */
    fun clientCaps(elts: List<Elt>): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        val caps = ArrayList<String>()
        val rates = HashSet<Double>()
        for (e in elts) {
            val info = e.info
            when {
                e.id == 1 || e.id == 50 -> info.forEach { rates += (it.toInt() and 0x7F) * 0.5 }
                e.id == 127 -> {
                    val bits = bits(info)
                    caps += EXTCAP_BITS.filter { (bit, _) -> bit in bits && bit !in setOf(12, 22, 70, 84) }.values
                    out["extcap"] = hex(info.slice(0, 16))
                }
                e.id == 70 -> caps += "802.11k radio measurement"
                e.id == 54 -> caps += "802.11r fast roaming"
                e.id == 45 && info.size >= 2 -> {
                    val v = le16(info)
                    caps += listOf(0 to "LDPC", 7 to "TX STBC", 5 to "short GI 20 MHz").filter { (bit, _) -> (v shr bit) and 1 == 1 }.map { it.second }
                    if ((v shr 8) and 3 != 0) caps += "RX STBC"
                }
                e.id == 191 && info.size >= 4 -> {
                    val v = le32(info, 0)
                    if ((v shr 12) and 1 == 1L) caps += "SU beamformee"
                    if ((v shr 20) and 1 == 1L) caps += "MU-MIMO beamformee"
                    if ((v shr 11) and 1 == 1L || (v shr 19) and 1 == 1L) caps += "beamformer"
                }
                e.id == 36 && info.size >= 2 -> out["chan_count"] = (0 until info.size - 1 step 2).sumOf { u8(info, it + 1) }
                e.id == 33 && info.size >= 2 -> out["txp"] = listOf(info[0].toInt(), info[1].toInt())
                e.id == 59 && info.isNotEmpty() -> out["opcls"] = info.slice(0, 16).map { it.toInt() and 0xFF }
                e.id == 48 -> parseRsn(info)?.let { r ->   // in an association request: the key management chosen, and PMF
                    @Suppress("UNCHECKED_CAST")
                    out["akm"] = (r["akm"] as List<Any>).take(4)
                    out["pmf"] = r["pmf"]!!
                }
            }
        }
        if (rates.isNotEmpty()) out["dsss"] = rates.any { it in LEGACY_RATES }
        if (rates.isNotEmpty() && rates.all { it in LEGACY_RATES }) out["b_only"] = true
        val seen = caps.distinct()
        if (seen.isNotEmpty()) out["caps"] = seen
        return out
    }

    // ---------------------------------------------------------------------------------------------- WPS, P2P, fingerprint
    private val BAD_WPS = setOf("", "0", "n/a", "none", "123456", " ")

    fun parseWps(data: ByteArray): Map<String, Any> {
        val out = linkedMapOf<String, Any>()
        val names = mapOf(0x1021 to "manufacturer", 0x1023 to "model", 0x1024 to "model_number", 0x1011 to "device_name")
        var i = 0
        while (i + 4 <= data.size) {
            val t = be16(data, i)
            val ln = be16(data, i + 2)
            val v = data.slice(i + 4, i + 4 + ln)
            i += 4 + ln
            if (t in names) {
                val s = pyStrip(utf8(v).trim { it == '\u0000' || it == ' ' })
                if (s.isNotEmpty() && s.lowercase() !in BAD_WPS) out[names.getValue(t)] = s.take(64)
            } else if (t == 0x1054 && ln >= 8 && v.size >= 8) {
                out["category"] = be16(v, 0)
                out["subcategory"] = be16(v, 6)
            }
        }
        return out
    }

    fun parseP2p(data: ByteArray): Map<String, Any>? {
        var i = 0
        while (i + 3 <= data.size) {
            val aid = u8(data, i)
            val ln = u8(data, i + 1) or (u8(data, i + 2) shl 8)
            val body = data.slice(i + 3, i + 3 + ln)
            i += 3 + ln
            if (aid != 13 || body.size < 17) continue
            val out = linkedMapOf<String, Any>("category" to be16(body, 8), "subcategory" to be16(body, 14))
            val at = 17 + 8 * u8(body, 16)
            if (at + 4 <= body.size && be16(body, at) == 0x1011) {
                val n = be16(body, at + 2)
                val name = pyStrip(utf8(body.slice(at + 4, at + 4 + n)).trim { it == '\u0000' || it == ' ' })
                if (name.isNotEmpty()) out["name"] = name.take(32)
            }
            return out
        }
        return null
    }

    private fun ssFromMap(m: Int) = (0 until 8).count { (m shr (2 * it)) and 0x3 != 0x3 }

    /** The capability fingerprint of a device: sniffowireless's fp["wifi"]. */
    fun fingerprint(elts: List<Elt>): Map<String, Any> {
        val fp = linkedMapOf<String, Any>()
        val vendors = ArrayList<String>()
        val wfa = ArrayList<String>()
        val sig = ArrayList<String>()
        var width: Int? = null
        var ss: Int? = null
        fun widen(w: Int) { width = maxOf(width ?: 20, w) }
        fun streams(n: Int) { ss = maxOf(ss ?: 0, n) }
        for (e in elts) {
            val info = e.info
            if (e.id == 221 && info.size >= 3) {
                val oui = ouiOf(info)
                val vtype = if (info.size > 3) u8(info, 3) else null
                sig += "221:$oui:${vtype ?: "None"}"
                if (oui !in vendors) vendors += oui
                if (oui == "00:50:f2" && vtype == 4) {
                    val wps = parseWps(info.slice(4))
                    if (wps.isNotEmpty()) fp["wps"] = wps
                } else if (oui == "50:6f:9a" && vtype in WFA_TYPES) {
                    val label = WFA_TYPES.getValue(vtype!!)
                    if (label !in wfa) wfa += label
                    if (vtype == 0x09) parseP2p(info.slice(4))?.let { fp["p2p"] = it }
                }
                continue
            }
            if (e.id == 255 && info.isNotEmpty()) {
                val ext = u8(info, 0)
                sig += "255:$ext"
                if (ext == 35 && info.size >= 8) {
                    fp["he"] = true
                    val phy0 = u8(info, 7)
                    if (phy0 and 0x18 != 0) widen(160)
                    else if (phy0 and 0x06 != 0) widen(if (phy0 and 0x04 != 0) 80 else 40)
                    if (info.size >= 20) streams(ssFromMap(le16(info, 18)))
                } else if (ext == 108 && info.size >= 4) {
                    fp["eht"] = true
                    if (u8(info, 3) and 0x02 != 0) width = 320
                }
                continue
            }
            if (e.id !in setOf(0, 3, 5)) sig += e.id.toString()
            when {
                e.id == 45 && info.size >= 7 -> {
                    fp["ht"] = true
                    if (le16(info, 0) and 0x0002 != 0) widen(40)
                    streams((3 until 7).count { info[it].toInt() != 0 })
                }
                e.id == 191 && info.size >= 6 -> {
                    fp["vht"] = true
                    val cw = ((le32(info, 0) shr 2) and 0x3).toInt()
                    widen(if (cw != 0) 160 else 80)
                    streams(ssFromMap(le16(info, 4)))
                }
                e.id == 48 -> fp["rsn"] = true
                e.id == 127 -> fp["extcap_len"] = info.size
            }
        }
        width?.let { fp["width"] = it }
        ss?.let { fp["ss"] = it }
        if (vendors.isNotEmpty()) fp["vendors"] = vendors
        if (wfa.isNotEmpty()) fp["wfa"] = wfa
        val gen = when { fp["eht"] == true -> 7; fp["he"] == true -> 6; fp["vht"] == true -> 5; fp["ht"] == true -> 4; else -> null }
        gen?.let { fp["gen"] = it }
        val caps = clientCaps(elts)
        if (caps.isNotEmpty()) fp["capinfo"] = caps
        fp["sig"] = sig.joinToString(",")
        return fp
    }
}
