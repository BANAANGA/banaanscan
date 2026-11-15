package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Sends the fixture observations to a real sniffowireless server through the [Forwarder], as the app does. Skipped
 * unless SNIFFO_SERVER and SNIFFO_SENSOR_TOKEN are set (see tools/sniffowireless/README.md); check the result in the
 * server's web UI or API afterwards.
 */
class LiveServerTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Suppress("UNCHECKED_CAST")
    private fun fixture(name: String) =
        Json.parse(LiveServerTest::class.java.getResourceAsStream("/fixtures/$name")!!.readBytes().toString(Charsets.UTF_8)) as Map<String, Any?>

    @Test fun `upload to a real server`() {
        val server = System.getenv("SNIFFO_SERVER")
        val token = System.getenv("SNIFFO_SENSOR_TOKEN")
        assumeTrue("set SNIFFO_SERVER and SNIFFO_SENSOR_TOKEN to run", server != null && token != null)
        val status = StatusBoard()
        val logs = LogBuffer()
        val f = Forwarder(Target(server!!, token!!), status, logs, mapOf("version" to "mobile test", "arch" to "jvm"),
            system = { mapOf("memory" to mapOf("total" to 8_000_000_000L, "used" to 3_000_000_000L)) })
        val now = System.currentTimeMillis() / 1000.0
        status.register("wifi:mt7921u", "type" to "wifi", "iface" to "mt7921u", "state" to "capturing", "channel" to "36", "band" to "5",
            "channels" to listOf("1", "6", "11", "36"), "bands" to listOf("2.4", "5"), "chan_rev" to 0, "supported_bands" to listOf("2.4", "5", "6"),
            "hw_bus" to "usb", "hw_usb" to "0e8d:7961", "hw_driver" to "sniffowireless-mobile")
        status.register("bt:usb", "type" to "bluetooth", "iface" to "usb", "state" to "scanning", "bt_version" to "5.1", "chip" to "Realtek",
            "scan" to "extended", "hw_usb" to "2357:0604")
        status.register("zigbee:dongle-e", "type" to "zigbee", "iface" to "dongle-e", "state" to "capturing", "zb_channels" to (11..26).toList(), "zb_rev" to 0)
        logs.add("INFO", "sniffowireless.mobile", "Live upload test")

        val fp = Gate()
        val gates = AttrGates()
        var n = 0
        @Suppress("UNCHECKED_CAST")
        for (c in fixture("wifi.json")["cases"] as List<Map<String, Any?>>) {
            WifiFrames.parse(hex(c["frame"] as String), (c["rssi"] as Number?)?.toInt(), (c["freq"] as Number?)?.toInt(),
                (c["tuned"] as Number?)?.toInt(), now, fp, gates).forEach { f.put(it); n++ }
        }
        val bt = BtObserver()
        @Suppress("UNCHECKED_CAST")
        for (c in fixture("bluetooth.json")["events"] as List<Map<String, Any?>>) {
            val ev = hex(c["event"] as String)
            Ble.parseEvent(ev[1].toInt() and 0xFF, ev.copyOfRange(3, minOf(ev.size, 3 + (ev[2].toInt() and 0xFF))))
                .mapNotNull { bt.observe(it, now) }.forEach { f.put(it); n++ }
        }
        val batch = FrameBatch()
        @Suppress("UNCHECKED_CAST")
        (fixture("zigbee.json")["captured"] as List<Map<String, Any?>>).forEach {
            batch.add(Captured((it["channel"] as Number).toInt(), hex(it["data"] as String), (it["rssi"] as Number?)?.toInt(), (it["lqi"] as Number?)?.toInt()))
        }
        batch.drain(now)?.let { f.put(it); n++ }
        f.put(mapOf("t" to "chan", "ts" to now, "mac" to "-", "freq" to 5180, "dwell_ms" to 400, "frames" to 25, "bytes" to 4000,
            "airtime_us" to 1800, "retries" to 2, "noise_sum" to 0, "noise_cnt" to 0)); n++

        assertTrue("upload failed: ${f.lastError}", f.step())
        println("sent $n observations; server answered ${f.lastReply}")
        assertTrue("server rejected some: ${f.lastReply}", (f.lastReply?.get("rejected") as Number?)?.toInt() == 0)
        assertTrue((f.lastReply?.get("accepted") as Number?)?.toInt() == n)
    }
}
