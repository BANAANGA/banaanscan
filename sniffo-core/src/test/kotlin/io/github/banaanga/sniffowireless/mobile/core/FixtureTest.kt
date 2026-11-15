package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Test

/**
 * The port against sniffowireless itself: each fixture holds what sniffowireless's Python code produced for an input
 * (tools/sniffowireless/make_fixtures.py), and the Kotlin has to produce the same.
 */
class FixtureTest {
    @Suppress("UNCHECKED_CAST")
    private fun fixture(name: String): Map<String, Any?> =
        Json.parse(FixtureTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>

    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Comparable form: no null map entries, numbers as Double, bytes as hex. */
    private fun norm(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.filter { it.value != null }.associate { it.key.toString() to norm(it.value) }.toSortedMap()
        is List<*> -> v.map(::norm)
        is Set<*> -> v.map(::norm)
        is Number -> v.toDouble()
        is ByteArray -> v.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        else -> v
    }

    private fun diff(path: String, a: Any?, b: Any?): String? {
        if (a is Map<*, *> && b is Map<*, *>) {
            for (k in (a.keys + b.keys).map { it.toString() }.toSortedSet()) diff("$path.$k", a[k], b[k])?.let { return it }
            return null
        }
        if (a is List<*> && b is List<*>) {
            if (a.size != b.size) return "$path: ${a.size} items expected, got ${b.size}: $a vs $b"
            a.indices.forEach { i -> diff("$path[$i]", a[i], b[i])?.let { return it } }
            return null
        }
        return if (a == b) null else "$path: expected $a, got $b"
    }

    private fun check(name: String, expected: Any?, actual: Any?, failures: MutableList<String>) {
        diff(name, norm(expected), norm(actual))?.let { failures += it }
    }

    private fun done(failures: List<String>) {
        if (failures.isNotEmpty()) fail(failures.size.toString() + " differences:\n" + failures.joinToString("\n"))
    }

    @Test fun `Wi-Fi frames`() {
        val failures = ArrayList<String>()
        @Suppress("UNCHECKED_CAST")
        val cases = fixture("wifi.json")["cases"] as List<Map<String, Any?>>
        assertEquals(43, cases.size)
        for (c in cases) {
            val obs = WifiFrames.parse(hex(c["frame"] as String), (c["rssi"] as Number?)?.toInt(), (c["freq"] as Number?)?.toInt(),
                (c["tuned"] as Number?)?.toInt(), 1_700_000_000.0, Gate(), AttrGates(), seqOn = true)
            check(c["name"] as String, c["obs"], obs, failures)
        }
        done(failures)
    }

    @Test fun `Bluetooth events`() {
        val failures = ArrayList<String>()
        val f = fixture("bluetooth.json")
        @Suppress("UNCHECKED_CAST")
        for (c in f["events"] as List<Map<String, Any?>>) {
            val ev = hex(c["event"] as String)
            val observer = BtObserver()
            val obs = Ble.parseEvent(ev[1].toInt() and 0xFF, ev.copyOfRange(3, minOf(ev.size, 3 + (ev[2].toInt() and 0xFF))))
                .mapNotNull { observer.observe(it, 1_700_000_000.0) }
            check(c["name"] as String, c["obs"], obs, failures)
        }
        @Suppress("UNCHECKED_CAST")
        val interval = f["interval"] as Map<String, Any?>
        val ev = hex(interval["event"] as String)
        val observer = BtObserver()
        @Suppress("UNCHECKED_CAST")
        val obs = (interval["times"] as List<Number>).flatMap { t ->
            Ble.parseEvent(ev[1].toInt() and 0xFF, ev.copyOfRange(3, ev.size)).mapNotNull { observer.observe(it, t.toDouble()) }
        }
        check("advertising interval", interval["obs"], obs, failures)
        @Suppress("UNCHECKED_CAST")
        for ((i, c) in (f["ad"] as List<Map<String, Any?>>).withIndex()) {
            check("parse_ad[$i]", c["ad"], Ble.parseAd(hex(c["data"] as String)), failures)
        }
        done(failures)
    }

    @Test fun `Zigbee frames`() {
        val failures = ArrayList<String>()
        val f = fixture("zigbee.json")
        @Suppress("UNCHECKED_CAST")
        val captured = (f["captured"] as List<Map<String, Any?>>).map {
            Captured((it["channel"] as Number).toInt(), hex(it["data"] as String), (it["rssi"] as Number?)?.toInt(), (it["lqi"] as Number?)?.toInt())
        }
        @Suppress("UNCHECKED_CAST")
        val macs = f["macs"] as List<Map<String, Any?>?>
        captured.forEachIndexed { i, c ->
            val m = ZigbeeFrames.parseMac(c.data)
            val actual = m?.let {
                mapOf("type" to it.type, "secured" to it.secured, "sequence" to it.sequence, "dst_pan" to it.dstPan, "dst" to it.dst,
                    "src_pan" to it.srcPan, "src" to it.src, "payload" to it.payload, "sec_level" to it.secLevel, "key_mode" to it.keyMode,
                    "hint" to it.hint)
            }
            check("parse_mac[$i]", macs[i], actual, failures)
        }
        val batch = FrameBatch()
        captured.forEach(batch::add)
        check("drain", f["drained"], batch.drain(1_700_000_000.0), failures)
        done(failures)
    }

    @Test fun `channel lists`() {
        val failures = ArrayList<String>()
        val f = fixture("channels.json")
        @Suppress("UNCHECKED_CAST")
        for (c in f["specs"] as List<Map<String, Any?>>) check("spec '${c["spec"]}'", c["freqs"], Channels.parse(c["spec"] as String), failures)
        @Suppress("UNCHECKED_CAST")
        for ((freq, label) in f["labels"] as Map<String, Any?>) check("label $freq", label, Channels.label(freq.toInt()), failures)
        done(failures)
    }
}
