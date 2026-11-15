package io.github.banaanga.sniffowireless.mobile.core

/** Wi-Fi channel numbers, bands and channel lists, as sniffowireless's config.py and channels.py define them. */
object Channels {
    val CH_24 = (1..13).toList()
    val CH_5 = listOf(36, 40, 44, 48, 52, 56, 60, 64, 100, 104, 108, 112, 116, 120, 124, 128, 132, 136, 140, 144, 149, 153, 157, 161, 165)
    val CH_5_COMMON = listOf(36, 40, 44, 48, 149, 153, 157, 161, 165)
    val CH_6 = (1..233 step 4).toList()
    val CH_6_PSC = (5..229 step 16).toList()

    fun bandOf(freq: Int?): String? = when {
        freq == null || freq == 0 -> null
        freq >= 5925 -> "6"
        freq >= 4900 -> "5"
        else -> "2.4"
    }

    fun toFreq(ch: Int, band: String? = null): Int = when {
        band == "6" -> if (ch == 2) 5935 else 5950 + ch * 5
        band == "2.4" || (band == null && ch <= 14) -> if (ch == 14) 2484 else 2407 + ch * 5
        else -> 5000 + ch * 5
    }

    fun toChannel(f: Int?): Int? = when {
        f == null || f == 0 -> null
        f == 2484 -> 14
        f in 2412..2472 -> (f - 2407) / 5
        f in 5000..5900 -> (f - 5000) / 5
        f == 5935 -> 2
        f in 5955..7115 -> (f - 5950) / 5
        else -> null
    }

    fun label(freq: Int): String {
        val ch = toChannel(freq) ?: return "${freq}MHz"
        return if (bandOf(freq) == "6") "6G:$ch" else ch.toString()
    }

    val PRESETS: Map<String, List<Int>> = run {
        val p = linkedMapOf(
            "2.4ghz" to CH_24.map { toFreq(it, "2.4") },
            "5ghz" to CH_5.map { toFreq(it, "5") },
            "5common" to CH_5_COMMON.map { toFreq(it, "5") },
            "6ghz" to CH_6_PSC.map { toFreq(it, "6") },
            "6all" to CH_6.map { toFreq(it, "6") },
        )
        p["default"] = p["2.4ghz"]!! + p["5common"]!!
        p["all"] = p["2.4ghz"]!! + p["5ghz"]!! + p["6ghz"]!!
        p
    }
    private val ALIASES = mapOf("" to "default", "2.4" to "2.4ghz", "6g" to "6ghz", "6e" to "6ghz", "6psc" to "6ghz",
        "6g:psc" to "6ghz", "6g:all" to "6all")
    private val WHOLE_SPEC = mapOf("5" to "5ghz", "6" to "6ghz")
    val KNOWN_FREQS: Set<Int> = (PRESETS["all"]!! + PRESETS["6all"]!! + listOf(2484)).toSet()

    private fun valid(band: String?): Set<Int> = when (band) {
        "6" -> CH_6.toSet()
        "2.4" -> CH_24.toSet() + 14
        "5" -> CH_5.toSet()
        else -> CH_24.toSet() + CH_5.toSet()
    }

    /** A channel spec ("default,6ghz", "1,6,11", "36-48", "6g:37", "2g:6") as frequencies; throws on a bad token. */
    fun parse(spec: String?): List<Int> {
        val s = (spec ?: "").trim().lowercase()
        WHOLE_SPEC[s]?.let { return PRESETS[it]!! }
        val out = ArrayList<Int>()
        for (raw in if (s.isEmpty()) listOf("") else s.split(",").map { it.trim() }) {
            var tok = ALIASES[raw] ?: raw
            val preset = PRESETS[tok]
            if (preset != null) {
                out += preset
                continue
            }
            var band: String? = null
            for ((prefix, b) in listOf("6g:" to "6", "5g:" to "5", "2g:" to "2.4")) {
                if (tok.startsWith(prefix)) { band = b; tok = tok.removePrefix(prefix); break }
            }
            if (tok.isEmpty()) continue
            if ("-" in tok) {
                val (lo, hi) = tok.split("-", limit = 2).map { it.trim().toInt() }
                val ok = valid(band)
                out += (lo..hi).filter { it in ok }.map { toFreq(it, band) }
            } else {
                out += toFreq(tok.toInt(), band)
            }
        }
        return out.distinct()
    }

    /** [parse], refusing empty lists and channels sniffowireless doesn't know, with a message like its own. */
    fun check(spec: String?): List<Int> {
        if (spec.isNullOrBlank()) throw IllegalArgumentException("The channel list is empty")
        if (spec.length > 200) throw IllegalArgumentException("The channel list is too long")
        val freqs = try {
            parse(spec)
        } catch (e: NumberFormatException) {
            throw IllegalArgumentException("“$spec” is not a valid channel list")
        }
        val unknown = freqs.filter { it !in KNOWN_FREQS }
        if (unknown.isNotEmpty()) throw IllegalArgumentException("Unknown channel(s): " + unknown.take(6).joinToString(", ") { label(it) })
        return freqs
    }
}
