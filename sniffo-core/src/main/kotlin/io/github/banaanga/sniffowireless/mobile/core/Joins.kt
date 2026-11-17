package io.github.banaanga.sniffowireless.mobile.core

/**
 * The steps of a client's connections, as sniffowireless's joins.py picks them out of what a sensor hears (release 34):
 * authentication, (re)association with their status codes, EAP and the 4-way handshake messages, deauthentication and
 * disassociation with their reason codes, 802.11v BSS transition and 802.11k neighbour reports. The server groups them into
 * attempts, names the step that failed and why, and finds roams with their duration.
 *
 * Only the step, the addresses, the codes and the time are sent; nothing of the keys. The frame starts at the 802.11 header
 * (no radiotap, no FCS); [rssi] is what the radio reported.
 */
object Joins {
    private val SNAP_EAPOL = byteArrayOf(0xAA.toByte(), 0xAA.toByte(), 0x03, 0, 0, 0, 0x88.toByte(), 0x8E.toByte())
    private val EAP_TYPES = mapOf(1 to "Identity", 4 to "MD5", 13 to "EAP-TLS", 17 to "LEAP", 18 to "EAP-SIM", 21 to "EAP-TTLS", 23 to "EAP-AKA",
        25 to "PEAP", 26 to "MSCHAPv2", 43 to "EAP-FAST", 50 to "EAP-AKA'", 52 to "EAP-pwd", 55 to "TEAP")

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun be16(b: ByteArray, i: Int) = (u8(b, i) shl 8) or u8(b, i + 1)
    private fun mac(b: ByteArray, at: Int) = (0 until 6).joinToString(":") { "%02x".format(u8(b, at + it)) }

    /** The connection steps in one frame, as maps of mac (the client), bssid, from_ap, rssi, step and code / algo / seq / detail. */
    fun steps(f: ByteArray, rssi: Int?): List<MutableMap<String, Any?>> {
        if (f.size < 24) return emptyList()
        return try { read(f, rssi) } catch (e: IndexOutOfBoundsException) { emptyList() }
    }

    private fun read(f: ByteArray, rssi: Int?): List<MutableMap<String, Any?>> {
        val fc0 = u8(f, 0)
        val fc1 = u8(f, 1)
        val type = (fc0 shr 2) and 3
        val sub = fc0 shr 4
        if (fc1 and 0x40 != 0 && type == 0 && sub != 13) return emptyList()   // a protected management frame
        val a1 = mac(f, 4)
        val a2 = mac(f, 10)
        val a3 = mac(f, 16)
        val fromAp = a2 == a3
        var client = if (fromAp) a1 else a2
        var bssid = if (fromAp) a2 else a1
        if (u8(f, if (fromAp) 4 else 10) and 1 != 0) return emptyList()
        val body = 24
        val n = f.size - body
        fun base(fromAp: Boolean): MutableMap<String, Any?> =
            linkedMapOf("mac" to client, "bssid" to bssid, "from_ap" to fromAp, "rssi" to if (fromAp) null else rssi)
        val out = ArrayList<MutableMap<String, Any?>>()
        if (type == 0) {
            when {
                sub == 11 && n >= 6 -> out += base(fromAp).apply {
                    put("step", "auth"); put("algo", le16(f, body)); put("seq", le16(f, body + 2)); put("code", le16(f, body + 4))
                }
                sub == 0 || sub == 2 -> out += base(fromAp).apply {
                    put("step", if (sub == 0) "assoc" else "reassoc")
                    if (sub == 2 && n >= 10) put("detail", mac(f, body + 4))   // the access point it comes from
                }
                (sub == 1 || sub == 3) && n >= 4 -> out += base(fromAp).apply {
                    put("step", if (sub == 1) "assoc_resp" else "reassoc_resp"); put("code", le16(f, body + 2))
                }
                (sub == 10 || sub == 12) && n >= 2 -> out += base(fromAp).apply {
                    put("step", if (sub == 12) "deauth" else "disassoc"); put("code", le16(f, body))
                }
                sub == 13 && n >= 2 && fc1 and 0x40 == 0 -> {
                    val cat = u8(f, body)
                    val act = u8(f, body + 1)
                    if (cat == 10 && (act == 7 || act == 8)) {
                        out += base(fromAp).apply {
                            put("step", if (act == 7) "btm_req" else "btm_resp")
                            if (act == 8 && n >= 4) {
                                put("code", u8(f, body + 3))
                                if (u8(f, body + 3) == 0 && n >= 11) put("detail", mac(f, body + 5))   // the access point it will move to
                            }
                        }
                    } else if (cat == 5 && (act == 4 || act == 5)) {
                        out += base(fromAp).apply { put("step", if (act == 4) "nr_req" else "nr_resp") }
                    }
                }
            }
        } else if (type == 2 && fc1 and 0x40 == 0) {
            val ds = fc1 and 3
            if (ds != 1 && ds != 2) return emptyList()
            val hdr = 24 + (if (sub and 8 != 0) 2 else 0) + (if (sub and 8 != 0 && fc1 and 0x80 != 0) 4 else 0)
            if (f.size < hdr + 12) return emptyList()
            for (i in 0 until 8) if (f[hdr + i] != SNAP_EAPOL[i]) return emptyList()
            client = if (ds == 1) a2 else a1
            bssid = if (ds == 1) a1 else a2
            val up = ds == 1
            val etype = u8(f, hdr + 9)
            val e = hdr + 12
            val left = f.size - e
            when {
                etype == 1 -> out += base(!up).apply { put("step", "eapol_start") }
                etype == 0 && left >= 4 -> {
                    val code = u8(f, e)
                    if (code == 3 || code == 4) out += base(!up).apply { put("step", if (code == 3) "eap_success" else "eap_failure") }
                    else if ((code == 1 || code == 2) && left >= 5) {
                        val t = u8(f, e + 4)
                        out += base(!up).apply { put("step", "eap"); put("code", t); put("detail", EAP_TYPES[t] ?: "type $t") }
                    }
                }
                etype == 3 && left >= 3 -> {
                    val ki = be16(f, e + 1)
                    if (ki and 0x08 == 0) return emptyList()   // the group key handshake
                    val ack = ki and 0x80 != 0
                    val mic = ki and 0x100 != 0
                    val secure = ki and 0x200 != 0
                    val install = ki and 0x40 != 0
                    val m = when {
                        ack && !mic -> 1
                        ack && mic && install -> 3
                        mic && !secure -> 2
                        mic && secure -> 4
                        else -> null
                    }
                    if (m != null) out += base(!up).apply { put("step", "m$m") }
                }
            }
        }
        return out
    }
}

/** The sensor side: the steps of every frame as `join` observations, at most [FLOOD] per client and 10 seconds (a deauth flood). */
class JoinWatcher {
    private val counts = HashMap<String, Int>()
    private var window = 0L

    @Synchronized
    fun feed(frame: ByteArray, rssi: Int?, ts: Double): List<MutableMap<String, Any?>> {
        val found = Joins.steps(frame, rssi)
        if (found.isEmpty()) return found
        val w = (ts / 10).toLong()
        if (w != window) { counts.clear(); window = w }
        val out = ArrayList<MutableMap<String, Any?>>()
        for (s in found) {
            val m = s["mac"] as String
            val n = counts[m] ?: 0
            if (n >= FLOOD) continue
            counts[m] = n + 1
            out += linkedMapOf<String, Any?>("t" to "join", "ts" to ts).apply { putAll(s) }
        }
        return out
    }

    companion object { const val FLOOD = 60 }
}
