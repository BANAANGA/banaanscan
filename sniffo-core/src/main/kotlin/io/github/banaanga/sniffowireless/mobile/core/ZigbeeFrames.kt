package io.github.banaanga.sniffowireless.mobile.core

/** One 802.15.4 frame as a capture delivers it: the MAC frame without its FCS, with the channel and signal. */
class Captured(val channel: Int, val data: ByteArray, val rssi: Int?, val lqi: Int?)

/** An 802.15.4 MAC header (zigbee_radio.MacFrame). Addresses are "0x1a2b" (short) or an EUI-64 written MSB first. */
class MacFrame(
    val type: String, val secured: Boolean, val sequence: Int?, val dstPan: Int?, val dst: String?, val srcPan: Int?,
    val src: String?, val payload: ByteArray, val secLevel: Int? = null, val keyMode: Int? = null,
) {
    val hint: Int get() = ZigbeeFrames.hint(this)
}

/**
 * sniffowireless's Zigbee frame decoding (zigbee_radio.py: parse_mac, parse_nwk, parse_gp, parse_thread_beacon and
 * FrameBatch), so a phone with a sniffing stick sends the same "zb_frames" summaries as one of its sensors.
 */
object ZigbeeFrames {
    val TYPE_NAMES = listOf("beacon", "data", "ack", "command", "other")
    const val HINT_UNKNOWN = 0
    const val HINT_ENCRYPTED = 1
    const val HINT_THREAD = 2
    const val HINT_ZIGBEE = 3

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun le16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    fun eui64(b: ByteArray, at: Int = 0) = (7 downTo 0).joinToString(":") { "%02x".format(u8(b, at + it)) }

    private fun pansPresent(version: Int, dstMode: Int, srcMode: Int, compressed: Boolean): Pair<Boolean, Boolean> {
        val hasDst = dstMode != 0
        val hasSrc = srcMode != 0
        if (version < 2) return hasDst to (hasSrc && !compressed)
        if (!hasDst && !hasSrc) return compressed to false
        if (hasDst && !hasSrc) return !compressed to false
        if (!hasDst) return false to !compressed
        if (dstMode == 3 && srcMode == 3) return !compressed to false
        return true to !compressed
    }

    fun parseMac(data: ByteArray): MacFrame? {
        if (data.size < 2) return null
        val control = le16(data, 0)
        val type = TYPE_NAMES[minOf(control and 7, 4)]
        val secured = control and 0x08 != 0
        val compressed = control and 0x40 != 0
        val version = (control shr 12) and 3
        val dstMode = (control shr 10) and 3
        val srcMode = (control shr 14) and 3
        if (dstMode == 1 || srcMode == 1 || version == 3) return null
        var at = 2
        var sequence: Int? = null
        if (!(control and 0x0100 != 0 && version == 2)) {
            if (at >= data.size) return null
            sequence = u8(data, at)
            at++
        }
        if (type == "other") return MacFrame(type, secured, sequence, null, null, null, null, ByteArray(0))
        fun pan(): Int {
            if (at + 2 > data.size) throw IndexOutOfBoundsException()
            return le16(data, at).also { at += 2 }
        }
        fun address(mode: Int): String {
            if (mode == 2) {
                if (at + 2 > data.size) throw IndexOutOfBoundsException()
                return "0x%04x".format(le16(data, at)).also { at += 2 }
            }
            if (at + 8 > data.size) throw IndexOutOfBoundsException()
            return eui64(data, at).also { at += 8 }
        }
        val dstPan: Int?
        val dst: String?
        val srcPan: Int?
        val src: String?
        try {
            val (dstPresent, srcPresent) = pansPresent(version, dstMode, srcMode, compressed)
            dstPan = if (dstPresent) pan() else null
            dst = if (dstMode != 0) address(dstMode) else null
            srcPan = if (srcPresent) pan() else if (srcMode != 0 && compressed) dstPan else null
            src = if (srcMode != 0) address(srcMode) else null
        } catch (e: IndexOutOfBoundsException) {
            return null
        }
        var secLevel: Int? = null
        var keyMode: Int? = null
        if (secured && at < data.size) {
            val c = u8(data, at)
            secLevel = c and 7
            keyMode = (c shr 3) and 3
            val suppressed = version == 2 && c and 0x20 != 0
            val aux = 1 + (if (suppressed) 0 else 4) + intArrayOf(0, 1, 5, 9)[keyMode]
            if (at + aux <= data.size) at += aux
        }
        return MacFrame(type, secured, sequence, dstPan, dst, srcPan, src, data.copyOfRange(at, data.size), secLevel, keyMode)
    }

    /** Offset of a beacon's payload after the superframe specification, GTS fields and pending addresses, or null. */
    private fun beaconBody(p: ByteArray): Int? {
        if (p.size < 4) return null
        val gts = u8(p, 2) and 7
        val at = 3 + (if (gts != 0) 1 else 0) + gts * 3
        if (at >= p.size) return null
        val pending = u8(p, at)
        val body = at + 1 + (pending and 7) * 2 + ((pending shr 4) and 7) * 8
        return if (body < p.size) body else null
    }

    internal fun hint(m: MacFrame): Int {
        val p = m.payload
        if (m.type == "beacon") {
            val body = beaconBody(p) ?: return HINT_UNKNOWN
            return when (u8(p, body)) { 0 -> HINT_ZIGBEE; 3 -> HINT_THREAD; else -> HINT_UNKNOWN }
        }
        if (m.secured) {
            if ((m.type == "data" || m.type == "command") && m.secLevel == 5 && (m.keyMode == 1 || m.keyMode == 2)) return HINT_THREAD
            return HINT_ENCRYPTED
        }
        if (m.type == "data" && p.size >= 8 && u8(p, 0) and 3 <= 1 && (u8(p, 0) shr 2) and 15 == 2) return HINT_ZIGBEE
        if (m.type == "data" && m.dst == "0xffff" && parseGp(p) != null) return HINT_ZIGBEE
        if (m.type == "data" && p.isNotEmpty() && u8(p, 0) and 0xE0 == 0x60) return HINT_THREAD
        return HINT_UNKNOWN
    }

    fun parseThreadBeacon(p: ByteArray): Map<String, Any>? {
        val body = beaconBody(p) ?: return null
        if (u8(p, body) != 3 || p.size - body < 26) return null
        val flags = u8(p, body + 1)
        val raw = p.copyOfRange(body + 2, body + 18)
        val end = raw.indexOf(0).let { if (it < 0) raw.size else it }
        val name = WifiAttrs.pyStrip(WifiAttrs.printable(String(raw, 0, end, Charsets.UTF_8)))
        return linkedMapOf("version" to (flags shr 4), "native" to (flags and 0x08 != 0), "joining" to (flags and 0x01 != 0),
            "name" to name.take(16), "ext_pan" to (0 until 8).joinToString(":") { "%02x".format(u8(p, body + 18 + it)) })
    }

    class Gp(val src: Long?, val toDevice: Boolean, val command: Int?)

    fun parseGp(p: ByteArray): Gp? {
        if (p.size < 2 || (u8(p, 0) shr 2) and 15 != 3 || u8(p, 0) and 3 > 1) return null
        var at = 1
        var app = 0
        var level = 0
        var toDevice = false
        if (u8(p, 0) and 0x80 != 0) {
            app = u8(p, 1) and 7
            level = (u8(p, 1) shr 3) and 3
            toDevice = u8(p, 1) and 0x80 != 0
            at = 2
        }
        if (app != 0 && app != 2) return null
        var src: Long? = null
        if (app == 0 && (u8(p, 0) and 3 == 0 || u8(p, 0) and 0x80 != 0)) {
            if (at + 4 > p.size) return null
            src = le16(p, at).toLong() or (le16(p, at + 2).toLong() shl 16)
            at += 4
        } else if (app == 2) {
            at += 1
        }
        if (level >= 2) at += 4
        val command = if (level < 3 && at < p.size) u8(p, at) else null
        return Gp(src, toDevice, command)
    }

    class Nwk(val type: String, val dst: Int, val src: Int, val radius: Int, val eui: String?, val dstEui: String?)

    fun parseNwk(p: ByteArray): Nwk? {
        if (p.size < 8 || u8(p, 0) and 3 > 1 || (u8(p, 0) shr 2) and 15 != 2) return null
        val fc = le16(p, 0)
        var at = 8
        var dstEui: String? = null
        var srcEui: String? = null
        var eui: String? = null
        if (fc and 0x0800 != 0) {
            if (at + 8 > p.size) return null
            dstEui = eui64(p, at); at += 8
        }
        if (fc and 0x1000 != 0) {
            if (at + 8 > p.size) return null
            srcEui = eui64(p, at); at += 8
        }
        if (fc and 0x0100 != 0) at += 1
        if (fc and 0x0400 != 0) {
            if (at + 2 > p.size) return null
            at += 2 + 2 * u8(p, at)
        }
        if (fc and 0x0200 != 0 && at + 14 <= p.size && u8(p, at) and 0x20 != 0) eui = eui64(p, at + 5)
        return Nwk(if (u8(p, 0) and 3 != 0) "command" else "data", le16(p, 2), le16(p, 4), u8(p, 6), eui ?: srcEui, dstEui)
    }

    fun isBroadcast(address: String?) = address != null && address.startsWith("0x") && address.substring(2).toInt(16) >= 0xFFFC
}

/**
 * Sums captured frames into per-channel counts and per-radio records (zigbee_radio.FrameBatch); [drain] gives the
 * "zb_frames" observation for everything since the last drain.
 */
class FrameBatch {
    private class Radio(var channels: Long = 0, var sent: Int = 0, var received: Int = 0, var encrypted: Int = 0, var hint: Int = 0,
                        var types: Int = 0, var rssi: Int? = null, var lqi: Int? = null)
    private class Detail(var roles: Int = 0, var eui: String? = null, var caps: Int? = null)

    private var channels = LinkedHashMap<Int, Int>()
    private var radios = LinkedHashMap<Pair<Int, String>, Radio>()
    private var threads = LinkedHashMap<Triple<Int, String, Int>, Map<String, Any?>>()
    private var detail = LinkedHashMap<Pair<Int, String>, Detail>()
    var frames = 0
        private set
    private var undecoded = 0

    private fun clear() {
        channels = LinkedHashMap(); radios = LinkedHashMap(); threads = LinkedHashMap(); detail = LinkedHashMap()
        frames = 0; undecoded = 0
    }

    fun add(c: Captured) {
        frames++
        channels[c.channel] = (channels[c.channel] ?: 0) + 1
        val mac = ZigbeeFrames.parseMac(c.data)
        if (mac == null) {
            undecoded++
            return
        }
        if (mac.type == "beacon") {
            val tb = ZigbeeFrames.parseThreadBeacon(mac.payload)
            val pan = mac.srcPan ?: mac.dstPan
            if (tb != null && pan != null && threads.size < MAX_NETWORKS) {
                threads[Triple(pan, tb["ext_pan"] as String, c.channel)] = tb + linkedMapOf("pan" to pan, "channel" to c.channel, "rssi" to c.rssi, "lqi" to c.lqi)
            }
        }
        behaviour(mac)
        val gp = if (mac.type == "data" && !mac.secured) ZigbeeFrames.parseGp(mac.payload) else null
        if (gp != null && !gp.toDevice) greenPower(mac, gp, c)
        if (mac.src != null) record(mac.srcPan ?: mac.dstPan, mac.src, mac, c, true)
        if (mac.dst != null && !ZigbeeFrames.isBroadcast(mac.dst)) record(mac.dstPan ?: mac.srcPan, mac.dst, mac, c, false)
    }

    private fun detail(pan: Int?, address: String): Detail? {
        val k = (pan ?: -1) to address
        return detail[k] ?: if (detail.size < MAX_RADIOS) Detail().also { detail[k] = it } else null
    }

    private fun greenPower(mac: MacFrame, gp: ZigbeeFrames.Gp, c: Captured) {
        val pan = mac.dstPan.takeIf { it != null && it != 0xFFFF } ?: mac.srcPan.takeIf { it != null && it != 0xFFFF }
        if (mac.dst != "0xffff") return
        val address: String
        if (gp.src != null && gp.src != 0L && mac.src == null) {
            address = "gpd:%08x".format(gp.src)
            record(pan, address, mac, c, true)
        } else if (gp.src == null && mac.src != null && !mac.src.startsWith("0x")) {
            address = mac.src
        } else {
            return
        }
        detail(pan, address)?.let { it.roles = it.roles or ROLE_GREEN_POWER or (if (gp.command != null && gp.command in 0x10 until 0x70) ROLE_GP_SWITCH else 0) }
    }

    private fun behaviour(mac: MacFrame) {
        val pan = mac.srcPan ?: mac.dstPan
        val src = mac.src ?: return
        val assoc = if (mac.type == "command" && !mac.secured && mac.payload.size == 2 && mac.payload[0].toInt() == 1) mac.payload[1].toInt() and 0xFF else null
        when {
            mac.type == "beacon" -> detail(pan, src)?.let { it.roles = it.roles or ROLE_BEACONS }
            assoc != null -> {
                detail(pan, src)?.let { it.caps = assoc }
                if (mac.dst != null && !ZigbeeFrames.isBroadcast(mac.dst)) detail(pan, mac.dst)?.let { it.roles = it.roles or ROLE_PARENT }
            }
            mac.type == "data" && !mac.secured && mac.payload.size >= 8 && mac.payload.copyOf(8).contentEquals(MLE_ALL_NODES) ->
                detail(pan, src)?.let { it.roles = it.roles or ROLE_MLE_ROUTER }
            mac.type == "command" && mac.payload.isNotEmpty() -> {
                val poll = (!mac.secured && mac.payload[0].toInt() == 4) ||
                    (mac.secured && mac.secLevel == 5 && mac.payload.size == 5 && mac.hint == ZigbeeFrames.HINT_THREAD)
                if (poll) {
                    detail(pan, src)?.let { it.roles = it.roles or ROLE_POLLS }
                    if (mac.dst != null && !ZigbeeFrames.isBroadcast(mac.dst)) detail(pan, mac.dst)?.let { it.roles = it.roles or ROLE_PARENT }
                }
            }
            mac.type == "data" && mac.hint == ZigbeeFrames.HINT_ZIGBEE -> {
                val n = ZigbeeFrames.parseNwk(mac.payload) ?: return
                val origin = "0x%04x".format(n.src)
                if (src != origin && src.startsWith("0x")) detail(pan, src)?.let { it.roles = it.roles or ROLE_RELAYS }
                if (n.dst == 0xFFFC) detail(pan, origin)?.let { it.roles = it.roles or ROLE_ROUTER_BROADCAST }
                if (n.eui != null && n.eui.substring(0, 2).toInt(16) and 1 == 0) detail(pan, origin)?.let { it.eui = n.eui }
            }
        }
    }

    private fun record(pan: Int?, address: String, mac: MacFrame, c: Captured, sent: Boolean) {
        val key = (pan ?: -1) to address
        val r = radios[key] ?: if (radios.size >= MAX_RADIOS) return else Radio().also { radios[key] = it }
        r.channels = r.channels or (1L shl c.channel)
        r.types = r.types or (1 shl ZigbeeFrames.TYPE_NAMES.indexOf(mac.type))
        r.hint = maxOf(r.hint, mac.hint)
        if (sent) {
            r.sent++
            if (mac.secured) r.encrypted++
            r.rssi = c.rssi
            r.lqi = c.lqi
        } else {
            r.received++
        }
    }

    fun drain(ts: Double): Map<String, Any?>? {
        if (frames == 0) return null
        val o = linkedMapOf<String, Any?>("t" to "zb_frames", "ts" to ts, "mac" to "-", "undecoded" to undecoded,
            "channels" to channels.entries.associate { it.key.toString() to it.value },
            "radios" to radios.map { (k, r) -> listOf(k.first, k.second, r.channels, r.sent, r.received, r.encrypted, r.hint, r.types, r.rssi, r.lqi) })
        if (threads.isNotEmpty()) o["networks"] = threads.values.toList()
        val d = detail.filter { (_, v) -> v.roles and ROLE_WIRE_MASK != 0 || v.eui != null }
            .map { (k, v) -> listOf(k.first, k.second, v.roles and ROLE_WIRE_MASK, v.eui) }
        if (d.isNotEmpty()) o["detail"] = d
        val more = detail.filter { (_, v) -> v.roles and ROLE_WIRE_MASK.inv() != 0 || v.caps != null }
            .map { (k, v) -> listOf(k.first, k.second, v.roles and ROLE_WIRE_MASK.inv(), v.caps) }
        if (more.isNotEmpty()) o["more"] = more
        clear()
        return o
    }

    companion object {
        const val MAX_RADIOS = 2000
        const val MAX_NETWORKS = 64
        const val ROLE_POLLS = 1
        const val ROLE_PARENT = 2
        const val ROLE_RELAYS = 4
        const val ROLE_BEACONS = 8
        const val ROLE_ROUTER_BROADCAST = 16
        const val ROLE_MLE_ROUTER = 32
        const val ROLE_GREEN_POWER = 64
        const val ROLE_GP_SWITCH = 128
        const val ROLE_WIRE_MASK = 31
        private val MLE_ALL_NODES = byteArrayOf(0x7f, 0x3b, 0x01, 0xf0.toByte(), 0x4d, 0x4c, 0x4d, 0x4c)
    }
}
