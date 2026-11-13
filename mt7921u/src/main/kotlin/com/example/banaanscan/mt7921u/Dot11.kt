package com.example.banaanscan.mt7921u

/** The parts of an 802.11 frame header a monitor needs: who sent it, to whom, in which network, and SSIDs. */
class Dot11Frame(
    val type: Int,
    val subtype: Int,
    val receiver: String?,
    val transmitter: String?,
    val bssid: String?,
    /** Network name from a beacon, probe request or probe response; empty for hidden or wildcard. */
    val ssid: String?,
    val protected: Boolean,
) {
    val isBeacon get() = type == 0 && subtype == 8
    val isProbeResponse get() = type == 0 && subtype == 5
    val isProbeRequest get() = type == 0 && subtype == 4
    val isData get() = type == 2
}

object Dot11 {
    private fun mac(b: ByteArray, at: Int) = (0 until 6).joinToString(":") { "%02x".format(b[at + it]) }

    /** Returns null for frames too short to hold their header. */
    fun parse(f: ByteArray): Dot11Frame? {
        if (f.size < 10) return null
        val fc0 = f[0].toInt() and 0xff
        val fc1 = f[1].toInt() and 0xff
        val type = (fc0 shr 2) and 3
        val subtype = (fc0 shr 4) and 15
        val toDs = fc1 and 1 != 0
        val fromDs = fc1 and 2 != 0
        val protected = fc1 and 0x40 != 0
        val receiver = mac(f, 4)
        if (type == 1) {
            // Control frames: ACK and CTS only carry a receiver address.
            val ta = if (f.size >= 16 && subtype != 12 && subtype != 13) mac(f, 10) else null
            return Dot11Frame(type, subtype, receiver, ta, null, null, protected)
        }
        if (f.size < 24) return null
        val a2 = mac(f, 10)
        val a3 = mac(f, 16)
        val bssid = when {
            type == 0 -> a3
            !toDs && !fromDs -> a3
            toDs && !fromDs -> receiver
            !toDs && fromDs -> a2
            else -> null // between access points: no single network
        }
        val ssid = if (type == 0) ssidElement(f, subtype) else null
        return Dot11Frame(type, subtype, receiver, a2, bssid, ssid, protected)
    }

    /** Beacons and probe responses have 12 fixed bytes before their elements; probe requests have none. */
    private fun ssidElement(f: ByteArray, subtype: Int): String? {
        val start = when (subtype) {
            8, 5 -> 24 + 12
            4 -> 24
            else -> return null
        }
        if (start + 2 > f.size || f[start].toInt() != 0) return null
        val len = f[start + 1].toInt() and 0xff
        if (len > 32 || start + 2 + len > f.size) return null
        val raw = f.copyOfRange(start + 2, start + 2 + len)
        if (raw.all { it.toInt() == 0 }) return ""
        return String(raw, Charsets.UTF_8).filter { it >= ' ' }
    }
}
