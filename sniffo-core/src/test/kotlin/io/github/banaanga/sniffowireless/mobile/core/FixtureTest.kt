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

    @Test fun `connection steps`() {
        val failures = ArrayList<String>()
        @Suppress("UNCHECKED_CAST")
        val cases = fixture("joins.json")["cases"] as List<Map<String, Any?>>
        assertEquals(28, cases.size)
        for (c in cases) check(c["name"] as String, c["steps"], Joins.steps(hex(c["frame"] as String), -61), failures)
        done(failures)
    }

    @Test fun `link quality counting`() {
        val f = fixture("link.json")
        val c = LinkCounter()
        @Suppress("UNCHECKED_CAST")
        for (frame in f["frames"] as List<String>) c.feed(hex(frame), (f["rate"] as Number).toDouble(), null, (f["rssi"] as Number).toInt())
        val failures = ArrayList<String>()
        check("link", f["obs"], c.drain(1_700_000_000.0).sortedBy { it["dir"] as String }, failures)
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

    private fun profileMap(p: LoraProfile) = mapOf("name" to p.name, "label" to p.label, "freq" to p.freq, "bw" to p.bw, "sf" to p.sf, "cr" to p.cr,
        "sync" to p.sync, "preamble" to p.preamble, "hint" to p.hint, "describe" to p.describe())

    @Test fun `LoRa profiles, frames and the public Meshtastic channel`() {
        val failures = ArrayList<String>()
        val f = fixture("lora.json")
        @Suppress("UNCHECKED_CAST")
        for (c in f["profiles"] as List<Map<String, Any?>>) {
            val name = "profiles '${c["spec"]}' (${c["region"]})"
            val got = runCatching { if (c["region"] != null) LoraProfiles.parse(c["spec"] as String, c["region"] as String) else LoraProfiles.parse(c["spec"] as String) }
            if (c["error"] == true) {
                if (got.isSuccess) failures += "$name: expected an error, got ${got.getOrNull()}"
            } else {
                got.exceptionOrNull()?.let { failures += "$name: ${it.message}" } ?: check(name, c["profiles"], got.getOrThrow().map(::profileMap), failures)
            }
        }
        @Suppress("UNCHECKED_CAST")
        for ((key, freq) in f["meshtastic_freqs"] as Map<String, Any?>) {
            val (region, preset) = key.split("/")
            check("frequency $key", freq, LoraProfiles.meshtasticFrequency(region, preset), failures)
        }
        @Suppress("UNCHECKED_CAST")
        for (c in f["rx"] as List<Map<String, Any?>>) {
            val rx = LoraRx.parse(c["line"] as String)?.let {
                mapOf("freq" to it.freq, "bw" to it.bw, "sf" to it.sf, "sync" to it.sync, "rssi" to it.rssi, "snr" to it.snr, "ferr" to it.ferr, "hex" to it.hex)
            }
            check("rx '${c["line"]}'", c["rx"], rx, failures)
        }
        @Suppress("UNCHECKED_CAST")
        for ((i, c) in (f["classify"] as List<Map<String, Any?>>).withIndex()) {
            check("classify[$i] ${c["hex"]}", c["result"],
                LoraClassifier.classify(hex(c["hex"] as String), (c["sync"] as Number?)?.toInt(), c["hint"] as String?), failures)
        }
        @Suppress("UNCHECKED_CAST")
        for ((i, c) in (f["decode"] as List<Map<String, Any?>>).withIndex()) {
            check("decode[$i] ${c["hex"]}", c["result"], Meshtastic.decode(hex(c["hex"] as String)), failures)
        }
        @Suppress("UNCHECKED_CAST")
        for (c in f["airtime"] as List<Map<String, Any?>>) {
            val ms = LoraProfiles.airtimeMs((c["len"] as Number).toInt(), (c["sf"] as Number).toInt(), (c["bw"] as Number).toDouble(),
                (c["cr"] as Number).toInt(), (c["preamble"] as Number).toInt())
            if (kotlin.math.abs(ms - (c["ms"] as Number).toDouble()) > 1e-9) failures += "airtime $c: got $ms"
        }
        done(failures)
    }
}
