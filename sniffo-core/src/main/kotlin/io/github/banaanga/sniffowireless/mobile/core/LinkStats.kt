package io.github.banaanga.sniffowireless.mobile.core

/**
 * Link quality per client, as sniffowireless's linkstats.LinkCounter counts it (release 34): every data frame between a client
 * and its access point, per direction, with its retry bit, the rate and MCS it went at and the client's signal. [drain] hands
 * them over once a minute as `link` observations; the server adds them up per hour and says what they point to (interference,
 * a weak signal, an old radio).
 *
 * The frame starts at the 802.11 header. [rate] (Mb/s) and [mcs] ("HE MCS 7 2SS 80", the server's labels) come from whatever the
 * radio reports; without them only frames and retries count.
 */
class LinkCounter {
    private class Entry(val bssid: String) {
        var frames = 0
        var retries = 0
        var rssiSum = 0L
        var rssiN = 0
        val rates = LinkedHashMap<String, Int>()
        val mcs = LinkedHashMap<String, Int>()
    }

    private val links = LinkedHashMap<Pair<String, String>, Entry>()
    private var last = 0.0

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun mac(b: ByteArray, at: Int) = (0 until 6).joinToString(":") { "%02x".format(u8(b, at + it)) }

    @Synchronized
    fun feed(f: ByteArray, rate: Double?, mcs: String?, rssi: Int?) {
        if (f.size < 24) return
        val fc0 = u8(f, 0)
        val fc1 = u8(f, 1)
        if ((fc0 shr 2) and 3 != 2 || (fc0 shr 4) and 4 != 0) return   // not a data frame, or one without data (null, QoS null)
        val way: String
        val clientAt: Int
        val bssidAt: Int
        when (fc1 and 3) {
            1 -> { way = "up"; clientAt = 10; bssidAt = 4 }
            2 -> { way = "down"; clientAt = 4; bssidAt = 10 }
            else -> return
        }
        if (u8(f, clientAt) and 1 != 0) return   // group-addressed
        val key = mac(f, clientAt) to way
        val e = links[key] ?: run {
            if (links.size >= MAX_LINKS) return
            Entry(mac(f, bssidAt)).also { links[key] = it }
        }
        e.frames++
        if (fc1 and 0x08 != 0) e.retries++
        if (rate != null && rate > 0) {
            val k = rateKey(rate)
            if (k in e.rates || e.rates.size < MAX_KEYS) e.rates[k] = (e.rates[k] ?: 0) + 1
        }
        if (mcs != null && (mcs in e.mcs || e.mcs.size < MAX_KEYS)) e.mcs[mcs] = (e.mcs[mcs] ?: 0) + 1
        if (way == "up" && rssi != null) { e.rssiSum += rssi; e.rssiN++ }
    }

    @Synchronized
    fun due(now: Double): Boolean {
        if (last == 0.0) last = now
        return now - last >= EMIT_EVERY
    }

    @Synchronized
    fun drain(now: Double): List<MutableMap<String, Any?>> {
        last = now
        val out = links.map { (k, e) ->
            linkedMapOf<String, Any?>("t" to "link", "ts" to now, "mac" to k.first, "dir" to k.second, "bssid" to e.bssid, "frames" to e.frames,
                "retries" to e.retries, "rates" to LinkedHashMap(e.rates), "mcs" to LinkedHashMap(e.mcs), "rssi_sum" to e.rssiSum, "rssi_n" to e.rssiN)
        }
        links.clear()
        return out
    }

    companion object {
        const val MAX_LINKS = 4000
        const val MAX_KEYS = 48
        const val EMIT_EVERY = 60.0

        /** The rate as Python's "%g" writes it: 24.0 -> "24", 58.5 -> "58.5". */
        fun rateKey(rate: Double): String {
            val r = Math.round(rate * 10) / 10.0
            return if (r == Math.floor(r)) r.toLong().toString() else r.toString()
        }
    }
}

/**
 * Data rates of HT, VHT and HE frames from MCS, spatial streams, channel width and guard interval (the 802.11 rate tables), as
 * linkstats.phy works them out, and the MCS labels the server reads.
 */
object PhyRates {
    private val HT_VHT_20 = doubleArrayOf(6.5, 13.0, 19.5, 26.0, 39.0, 52.0, 58.5, 65.0, 78.0, 86.7, 97.5, 108.3)
    private val HE_20 = doubleArrayOf(8.6, 17.2, 25.8, 34.4, 51.6, 68.8, 77.4, 86.0, 103.2, 114.7, 129.0, 143.4, 154.9, 172.1)
    private val WIDTH_HT = mapOf(20 to 1.0, 40 to 108.0 / 52, 80 to 234.0 / 52, 160 to 468.0 / 52)
    private val WIDTH_HE = mapOf(20 to 1.0, 40 to 468.0 / 234, 80 to 980.0 / 234, 160 to 1960.0 / 234)
    private val HE_GI = doubleArrayOf(1.0, 13.6 / 14.4, 13.6 / 16.0)   // 0.8, 1.6, 3.2 µs

    private fun r1(x: Double) = Math.round(x * 10) / 10.0

    /** (Mb/s, label) of an HT frame; [index] 0-31 carries the streams. */
    fun ht(index: Int, width: Int, shortGi: Boolean): Pair<Double, String>? {
        val m = index % 8
        val nss = index / 8 + 1
        val w = WIDTH_HT[width] ?: return null
        return r1(HT_VHT_20[m] * nss * w * (if (shortGi) 10.0 / 9 else 1.0)) to "HT MCS $m ${nss}SS $width"
    }

    fun vht(mcs: Int, nss: Int, width: Int, shortGi: Boolean): Pair<Double, String>? {
        if (mcs !in HT_VHT_20.indices || nss < 1) return null
        val w = WIDTH_HT[width] ?: return null
        return r1(HT_VHT_20[mcs] * nss * w * (if (shortGi) 10.0 / 9 else 1.0)) to "VHT MCS $mcs ${nss}SS $width"
    }

    /** [gi]: 0 = 0.8 µs, 1 = 1.6 µs, 2 = 3.2 µs. */
    fun he(mcs: Int, nss: Int, width: Int, gi: Int): Pair<Double, String>? {
        if (mcs !in HE_20.indices || nss < 1) return null
        val w = WIDTH_HE[width] ?: return null
        return r1(HE_20[mcs] * nss * w * (HE_GI.getOrNull(gi) ?: 1.0)) to "HE MCS $mcs ${nss}SS $width"
    }
}
