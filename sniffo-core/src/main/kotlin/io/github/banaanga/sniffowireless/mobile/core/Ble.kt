package io.github.banaanga.sniffowireless.mobile.core

/** Bluetooth SIG company identifiers, from the list sniffowireless ships (bt_companies.json, made by make_fixtures.py). */
object Companies {
    private val names: Map<Int, String>
    private val unregisteredFrom: Int

    init {
        val text = Companies::class.java.getResourceAsStream("/bt_companies.json")?.use { it.readBytes().toString(Charsets.UTF_8) }
        @Suppress("UNCHECKED_CAST")
        val root = text?.let { Json.parse(it) as? Map<String, Any?> }
        @Suppress("UNCHECKED_CAST")
        names = (root?.get("companies") as? Map<String, Any?>).orEmpty().mapNotNull { (k, v) -> k.toIntOrNull(16)?.let { it to v.toString() } }.toMap()
        unregisteredFrom = (root?.get("unregistered_from") as? Number)?.toInt() ?: 0x10000
    }

    /** The registered name, or null: sniffowireless's bt_company_known. */
    fun known(cid: Int?): String? = cid?.let { names[it] }

    /** A name for any ID: sniffowireless's bt_company. */
    fun name(cid: Int?): String? {
        if (cid == null) return null
        names[cid]?.let { return it }
        return if (cid >= unregisteredFrom) "Unregistered 0x%04X".format(cid) else "Company 0x%04X".format(cid)
    }
}

/** One advertising report or inquiry answer, as sniffowireless's bluetooth.parse_event returns it. */
class BtEvent(
    val kind: String,                 // "adv", "inq", "inq_done", "name", ...
    val mac: String = "",
    val addrType: Int = 0,
    val rssi: Int? = null,
    val connectable: Boolean = false,
    val ad: Map<String, Any?> = emptyMap(),
    val ext: Boolean = false,
    val phy: String? = null,
    val srsp: Boolean = false,
    val cod: Int = 0,
)

/**
 * Bluetooth advertising and inquiry data as sniffowireless's bluetooth.py and identify.py read it: parse_ad, uuid_label,
 * ibeacon, raw_mfg, classify_le_addr, ble_fingerprint, parse_event.
 */
object Ble {
    private val APPEARANCE = mapOf(1 to "phone", 2 to "computer", 3 to "watch", 4 to "clock", 5 to "display", 6 to "remote",
        7 to "eyeglasses", 8 to "tag", 9 to "keyring", 10 to "media player", 11 to "barcode scanner", 12 to "thermometer",
        13 to "heart rate", 14 to "blood pressure", 15 to "HID", 16 to "glucose", 17 to "running sensor", 18 to "cycling sensor",
        21 to "light", 41 to "audio sink", 42 to "audio source", 49 to "sensor")
    val COD_MAJOR = mapOf(1 to "computer", 2 to "phone", 3 to "network", 4 to "audio/video", 5 to "peripheral", 6 to "imaging",
        7 to "wearable", 8 to "toy", 9 to "health")
    private val PHY_NAMES = mapOf(1 to "1M", 2 to "2M", 3 to "Coded")
    private val APPLE_MODELS = setOf(0x2002, 0x200F, 0x2013, 0x200E, 0x2014, 0x2024, 0x200A, 0x201F, 0x2019, 0x201B, 0x2005, 0x2006,
        0x2003, 0x200B, 0x200C, 0x2009, 0x2010, 0x2011, 0x2012, 0x2016, 0x2017)
    private const val UUID_PBA = 0x1856
    private const val UUID_BAA = 0x1852
    private const val UUID_MATTER = 0xFFF6
    private val BASE_UUID_TAIL = byteArrayOf(0x00, 0x00, 0x10, 0x00, 0x80.toByte(), 0x00, 0x00, 0x80.toByte(), 0x5f, 0x9b.toByte(), 0x34, 0xfb.toByte())

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    private fun signed(v: Int) = if (v > 127) v - 256 else v
    private fun text(v: ByteArray) = WifiAttrs.pyStrip(String(v, Charsets.UTF_8).trim('\u0000'))
    private fun round2(x: Double) = Math.rint(x * 100) / 100

    fun bdaddr(b: ByteArray, at: Int) = (5 downTo 0).joinToString(":") { "%02x".format(u8(b, at + it)) }

    /** A UUID given most significant byte first: "180f", 8 hex digits, or the dashed form; base UUIDs shortened. */
    fun uuidLabel(b: ByteArray): String {
        if (b.size == 4) return if (b[0].toInt() == 0 && b[1].toInt() == 0) hex(b).substring(4) else hex(b)
        if (b.size == 16 && b.copyOfRange(4, 16).contentEquals(BASE_UUID_TAIL)) return uuidLabel(b.copyOfRange(0, 4))
        val h = hex(b)
        return "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
    }

    fun ibeacon(mfg: ByteArray?, cid: Int?): Map<String, Any>? {
        if (cid != 0x004C || mfg == null || mfg.size < 23 || u8(mfg, 0) != 0x02 || u8(mfg, 1) != 0x15) return null
        return linkedMapOf("uuid" to uuidLabel(mfg.copyOfRange(2, 18)), "major" to ((u8(mfg, 18) shl 8) or u8(mfg, 19)),
            "minor" to ((u8(mfg, 20) shl 8) or u8(mfg, 21)), "power" to signed(u8(mfg, 22)))
    }

    /** Advertising / EIR data into the fields sniffowireless keeps (its parse_ad). "mfg" stays bytes. */
    fun parseAd(data: ByteArray): MutableMap<String, Any?> {
        val out = linkedMapOf<String, Any?>()
        val uuids = ArrayList<String>()
        var i = 0
        while (i < data.size) {
            val ln = u8(data, i)
            if (ln == 0 || i + 1 + ln > data.size) break
            val t = u8(data, i + 1)
            val v = data.copyOfRange(i + 2, i + 1 + ln)
            when {
                (t == 0x08 || t == 0x09) && v.isNotEmpty() -> {
                    val name = text(v)
                    if (name.isNotEmpty() && (t == 0x09 || "name" !in out)) out["name"] = name
                }
                t == 0x0A && v.isNotEmpty() -> out["tx_power"] = signed(u8(v, 0))
                t == 0x19 && v.size >= 2 -> {
                    val a = le16(v, 0)
                    out["appearance"] = APPEARANCE[a shr 6] ?: "0x%04x".format(a)
                }
                t == 0xFF && v.size >= 2 -> {
                    out["company_id"] = le16(v, 0)
                    out["mfg"] = v.copyOfRange(2, minOf(v.size, 34))
                }
                t == 0x01 && v.isNotEmpty() -> out["flags"] = u8(v, 0)
                t == 0x02 || t == 0x03 -> for (j in 0 until v.size - 1 step 2) uuids += "%04x".format(le16(v, j))
                t == 0x04 || t == 0x05 -> for (j in 0 until v.size - 3 step 4) uuids += uuidLabel(v.copyOfRange(j, j + 4).reversedArray())
                t == 0x06 || t == 0x07 -> for (j in 0 until v.size - 15 step 16) uuids += uuidLabel(v.copyOfRange(j, j + 16).reversedArray())
                t == 0x12 && v.size >= 4 -> {
                    val lo = le16(v, 0)
                    val hi = le16(v, 2)
                    if (lo in 6..hi && hi <= 3200) out["conn_interval"] = listOf(round2(lo * 1.25), round2(hi * 1.25))
                }
                t == 0x16 && v.size >= 2 -> {
                    val u = le16(v, 0)
                    uuids += "%04x".format(u)
                    when {
                        u == UUID_PBA && v.size >= 3 -> out["auracast"] = linkedMapOf("encrypted" to (u8(v, 2) and 1 != 0),
                            "quality" to if (u8(v, 2) and 4 != 0) "high" else if (u8(v, 2) and 2 != 0) "standard" else null)
                        u == UUID_MATTER && v.size >= 9 && u8(v, 2) == 0 -> out["matter"] = linkedMapOf(
                            "discriminator" to (le16(v, 3) and 0x0FFF), "vendor" to le16(v, 5), "product" to le16(v, 7))
                        u == 0xFE95 && v.size >= 6 -> out["mi_product"] = le16(v, 4)
                        u == 0xFE2C && v.size == 5 -> out["fast_pair"] = hex(v.copyOfRange(2, 5))
                        u == UUID_BAA && v.size >= 5 -> out["broadcast_id"] = hex(v.copyOfRange(2, 5).reversedArray())
                    }
                }
                t == 0x30 && v.isNotEmpty() -> text(v).takeIf { it.isNotEmpty() }?.let { out["broadcast_name"] = it.take(64) }
                t == 0x31 -> out["encrypted_ad"] = true
                t == 0x34 -> out["esl"] = true
            }
            i += 1 + ln
        }
        if (uuids.isNotEmpty()) out["uuids"] = uuids.toSet().sortedWith(compareBy({ it.length }, { it })).take(16)
        ibeacon(out["mfg"] as ByteArray?, out["company_id"] as Int?)?.let { out["ibeacon"] = it }
        return out
    }

    fun rawMfg(cid: Int?, mfg: ByteArray?): String? {
        if (cid == null || cid == 0x004C) return null
        return hex(byteArrayOf(cid.toByte(), (cid shr 8).toByte()) + (mfg ?: ByteArray(0)))
    }

    /** (label, randomized) for an LE address of [addrType] (0 public, 1 random, 2/3 resolved identities). */
    fun classifyLeAddr(addrType: Int, mac: String): Pair<String, Boolean> {
        if (addrType == 0 || addrType == 2) return "public" to false
        return when (mac.substring(0, 2).toInt(16) shr 6) {
            0b11 -> "random-static" to false
            0b01 -> "random-resolvable" to true
            else -> "random-nonresolvable" to true
        }
    }

    /** Identification fields from parsed advertising data (identify.ble_fingerprint). */
    fun fingerprint(ad: Map<String, Any?>, ext: Boolean): Map<String, Any> {
        val fp = linkedMapOf<String, Any>()
        val mfg = ad["mfg"] as ByteArray?
        val cid = ad["company_id"] as Int?
        if (cid != null) fp["company"] = cid
        if (mfg != null && mfg.isNotEmpty() && cid == 0x004C) {
            val types = ArrayList<Int>()
            var i = 0
            while (i + 2 <= mfg.size) {
                val t = u8(mfg, i)
                val ln = u8(mfg, i + 1)
                val payload = mfg.copyOfRange(minOf(i + 2, mfg.size), minOf(i + 2 + ln, mfg.size))
                types += t
                if (t == 0x12 && payload.isNotEmpty()) fp["findmy"] = linkedMapOf("kind" to ((u8(payload, 0) shr 4) and 3), "battery" to (u8(payload, 0) shr 6))
                if (t == 0x07 && payload.size >= 3) {
                    val be = (u8(payload, 1) shl 8) or u8(payload, 2)
                    val le = le16(payload, 1)
                    val model = if (be in APPLE_MODELS) be else if (le in APPLE_MODELS) le else null
                    if (model != null) fp["apple_model"] = model
                }
                i += 2 + ln
            }
            fp["apple"] = types.toSortedSet().toList()
        } else if (mfg != null && mfg.isNotEmpty() && cid == 0x0006 && mfg.size >= 2 && u8(mfg, 0) == 0x01) {
            fp["ms_type"] = u8(mfg, 1) and 0x1F
        } else if (mfg != null && mfg.isNotEmpty() && cid == 0x0006 && mfg.size >= 3 && u8(mfg, 0) == 0x03 && u8(mfg, 1) <= 0x02) {
            fp["swift_pair"] = true
        }
        for (k in listOf("mi_product", "fast_pair")) ad[k]?.let { fp[k] = it }
        val flags = ad["flags"] as Int?
        if (flags != null && flags and 0x04 == 0 && flags and 0x18 != 0) fp["dual"] = true
        @Suppress("UNCHECKED_CAST")
        (ad["uuids"] as List<String>?)?.takeIf { it.isNotEmpty() }?.let { fp["uuids"] = it.toList() }
        if (ext) fp["ext_adv"] = true
        return fp
    }

    private fun rssi(b: Int): Int? {
        val v = signed(b)
        return if (v == 127 || v >= 20) null else v
    }

    /** One HCI event (code and parameters) into events (bluetooth.parse_event, the parts a scanner uses). */
    fun parseEvent(code: Int, p: ByteArray): List<BtEvent> {
        val out = ArrayList<BtEvent>()
        if (code == 0x3E && p.isNotEmpty()) {
            val sub = u8(p, 0)
            if ((sub == 0x02 || sub == 0x0D) && p.size < 2) return out
            if (sub == 0x02) {
                var i = 2
                repeat(u8(p, 1)) {
                    if (i + 9 > p.size) return out
                    val evt = u8(p, i)
                    val ln = u8(p, i + 8)
                    val data = p.copyOfRange(i + 9, minOf(p.size, i + 9 + ln))
                    val r = if (i + 9 + ln < p.size) rssi(u8(p, i + 9 + ln)) else null
                    out += BtEvent("adv", bdaddr(p, i + 2), u8(p, i + 1), r, evt == 0 || evt == 1, parseAd(data), srsp = evt == 4)
                    i += 10 + ln
                }
            } else if (sub == 0x0D) {
                var i = 2
                repeat(u8(p, 1)) {
                    if (i + 24 > p.size) return out
                    val evt = le16(p, i)
                    val ln = u8(p, i + 23)
                    val data = p.copyOfRange(i + 24, minOf(p.size, i + 24 + ln))
                    val phyCode = u8(p, i + 10).takeIf { it != 0 } ?: u8(p, i + 9)
                    out += BtEvent("adv", bdaddr(p, i + 3), u8(p, i + 2), rssi(u8(p, i + 13)), evt and 0x01 != 0, parseAd(data),
                        ext = evt and 0x10 == 0, phy = PHY_NAMES[phyCode], srsp = evt and 0x08 != 0)
                    i += 24 + ln
                }
            }
        } else if ((code == 0x02 || code == 0x22) && p.isNotEmpty()) {
            for (k in 0 until u8(p, 0)) {
                val o = 1 + k * 14
                if (o + 14 > p.size) break
                val codAt = if (code == 0x02) o + 9 else o + 8
                val cod = u8(p, codAt) or (u8(p, codAt + 1) shl 8) or (u8(p, codAt + 2) shl 16)
                out += BtEvent("inq", bdaddr(p, o), rssi = if (code == 0x22) rssi(u8(p, o + 13)) else null, cod = cod)
            }
        } else if (code == 0x2F && p.size >= 15) {
            val cod = u8(p, 9) or (u8(p, 10) shl 8) or (u8(p, 11) shl 16)
            out += BtEvent("inq", bdaddr(p, 1), rssi = rssi(u8(p, 14)), cod = cod, ad = parseAd(p.copyOfRange(15, p.size)))
        } else if (code == 0x01) {
            out += BtEvent("inq_done")
        }
        return out
    }
}

/** How often a device advertises, from the gaps between its packets (sniffowireless's AdvInterval). */
class AdvInterval(private val limit: Int = 20000) {
    private val d = HashMap<String, Pair<Double, List<Double>>>()

    fun update(mac: String, now: Double): Int? {
        val (last, old) = d[mac] ?: (null to emptyList())
        var gaps = old
        if (last != null && now - last in MIN_GAP..MAX_GAP) gaps = (gaps + (now - last)).takeLast(KEEP)
        if (d.size >= limit && mac !in d) d.clear()
        d[mac] = now to gaps
        if (gaps.size < NEED) return null
        val lo = gaps.min()
        val near = gaps.filter { it <= lo * 1.3 }.sorted()
        val est = near[near.size / 2] * 1000
        return if (est < 1000) (Math.rint(est / 10.0) * 10).toInt() else (Math.rint(est / 50.0) * 50).toInt()
    }

    private companion object {
        const val MIN_GAP = 0.020
        const val MAX_GAP = 5.0
        const val NEED = 8
        const val KEEP = 24
    }
}

/**
 * Builds sniffowireless "bt" observations from events, as its BluetoothSensor._handle does: the same fields, the same
 * 10-minute gate on repeating fingerprints, the same advertising-interval estimate.
 */
class BtObserver {
    private val fpSeen = HashMap<String, Pair<Map<String, Any>, Double>>()
    private val intervals = AdvInterval()
    private val intervalSent = HashMap<String, Int>()
    var adverts = 0L
        private set
    var inquiries = 0L
        private set

    fun observe(ev: BtEvent, now: Double): Map<String, Any?>? = when (ev.kind) {
        "adv" -> advert(ev, now)
        "inq" -> {
            inquiries++
            val ad = ev.ad
            linkedMapOf("t" to "bt", "kind" to "bt_classic", "ts" to now, "mac" to ev.mac, "rssi" to ev.rssi, "name" to ad["name"],
                "randomized" to false, "extra" to linkedMapOf("class" to (Ble.COD_MAJOR[(ev.cod shr 8) and 0x1F] ?: "uncategorized"),
                    "cod" to "0x%06x".format(ev.cod), "tx_power" to ad["tx_power"], "uuids" to ad["uuids"]))
        }
        else -> null
    }

    private fun advert(ev: BtEvent, now: Double): Map<String, Any?> {
        adverts++
        val ad = ev.ad
        val (label, randomized) = Ble.classifyLeAddr(ev.addrType, ev.mac)
        val cid = ad["company_id"] as Int?
        var fp: Map<String, Any>? = Ble.fingerprint(ad, ev.ext)
        val prev = fpSeen[ev.mac]
        if (fp!!.isNotEmpty() && (prev == null || !deepEquals(prev.first, fp) || now - prev.second > 600)) {
            if (fpSeen.size > 50_000) fpSeen.clear()
            fpSeen[ev.mac] = fp to now
        } else {
            fp = null
        }
        var est = if (ev.srsp) null else intervals.update(ev.mac, now)
        if (est != null) {
            val sent = intervalSent[ev.mac]
            if (sent != null && Math.abs(est - sent) <= maxOf(20.0, sent * 0.2)) {
                est = null
            } else {
                if (intervalSent.size > 50_000) intervalSent.clear()
                intervalSent[ev.mac] = est
            }
        }
        @Suppress("UNCHECKED_CAST")
        val auracast = ad["auracast"] as Map<String, Any?>?
        val broadcast = (linkedMapOf("name" to ad["broadcast_name"], "id" to ad["broadcast_id"]) + auracast.orEmpty()).filterValues { it != null }
        return linkedMapOf(
            "t" to "bt", "kind" to "ble", "ts" to now, "mac" to ev.mac, "rssi" to ev.rssi,
            "name" to (ad["name"] ?: ad["broadcast_name"]), "randomized" to randomized, "addr_random" to (label != "public"),
            "vendor" to if (label != "public") Companies.known(cid) else null,
            "extra" to linkedMapOf(
                "addr_type" to label, "company" to Companies.name(cid), "appearance" to ad["appearance"],
                "mfg_raw" to Ble.rawMfg(cid, ad["mfg"] as ByteArray?), "tx_power" to ad["tx_power"], "uuids" to ad["uuids"],
                "connectable" to ev.connectable, "phy" to if (ev.ext) ev.phy else null, "ext_adv" to if (ev.ext) true else null,
                "auracast" to broadcast.ifEmpty { null }, "esl" to if (ad["esl"] == true) true else null,
                "encrypted_ad" to if (ad["encrypted_ad"] == true) true else null, "matter" to ad["matter"], "flags" to ad["flags"],
                "conn_interval" to ad["conn_interval"], "ibeacon" to ad["ibeacon"], "adv_interval" to est,
            ),
            "fp" to fp?.let { mapOf("ble" to it) },
        )
    }

    private fun deepEquals(a: Any?, b: Any?): Boolean = Json.encode(a) == Json.encode(b)
}
