package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The Wi-Fi port against sniffowireless on real traffic. Real captures hold network names, so they stay out of the
 * repository: export one with sniffowireless's observations (see tools/sniffowireless/README.md), then run
 * `SNIFFO_REAL_CAPTURE=/path/export.json ./gradlew :sniffo-core:test --tests '*RealCaptureTest*'`. Without that file
 * the test is skipped. It reports which fields differ, not their values, and allows 0.1% of frames to differ.
 */
class RealCaptureTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private fun norm(v: Any?): Any? = when (v) {
        is Map<*, *> -> v.entries.filter { it.value != null }.associate { it.key.toString() to norm(it.value) }.toSortedMap()
        is List<*> -> v.map(::norm)
        is Number -> v.toDouble()
        else -> v
    }

    private fun diffs(path: String, a: Any?, b: Any?, out: MutableList<String>) {
        when {
            a is Map<*, *> && b is Map<*, *> -> (a.keys + b.keys).map { it.toString() }.toSet().forEach { diffs("$path.$it", a[it], b[it], out) }
            a is List<*> && b is List<*> && a.size == b.size -> a.indices.forEach { diffs(path, a[it], b[it], out) }
            a is List<*> && b is List<*> -> out += "$path (list length)"
            a != b -> out += path
        }
    }

    @Test fun `real capture matches sniffowireless`() {
        val path = System.getenv("SNIFFO_REAL_CAPTURE")
        assumeTrue("set SNIFFO_REAL_CAPTURE to run", path != null && File(path).isFile)
        @Suppress("UNCHECKED_CAST")
        val files = Json.parse(File(path!!).readText()) as List<Map<String, Any?>>
        val report = StringBuilder()
        var totalBad = 0
        var totalFrames = 0
        for (f in files) {
            val fpGate = Gate()
            val gates = AttrGates()
            val byField = sortedMapOf<String, Int>()
            var frames = 0
            var bad = 0
            var obsCount = 0
            @Suppress("UNCHECKED_CAST")
            for (fr in f["frames"] as List<Map<String, Any?>>) {
                frames++
                val actual = WifiFrames.parse(hex(fr["frame"] as String), (fr["rssi"] as Number?)?.toInt(), (fr["freq"] as Number?)?.toInt(),
                    null, (fr["ts"] as Number).toDouble(), fpGate, gates, seqOn = true,
                    phyRate = (fr["phy_rate"] as Number?)?.toDouble(), noise = (fr["noise"] as Number?)?.toInt())
                obsCount += actual.size
                val d = ArrayList<String>()
                diffs("obs", norm(fr["obs"]), norm(actual), d)
                if (d.isNotEmpty()) {
                    bad++
                    d.toSet().forEach { byField[it] = (byField[it] ?: 0) + 1 }
                }
            }
            totalBad += bad
            totalFrames += frames
            report.append("${f["file"]}: $frames frames, $obsCount observations, $bad frames differ\n")
            byField.entries.sortedByDescending { it.value }.take(25).forEach { report.append("   ${it.value}x ${it.key}\n") }
        }
        println(report)
        // A few frames with malformed elements are expected to differ: scapy's per-element parsers ignore the length there
        // (an OBSS element of 2 bytes read as 14 hides the next elements), which the port doesn't copy.
        if (totalBad * 1000 > totalFrames) throw AssertionError("Differences from sniffowireless:\n$report")
    }
}
