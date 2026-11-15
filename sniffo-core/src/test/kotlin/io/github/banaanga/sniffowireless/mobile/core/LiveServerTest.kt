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
            system = { mapOf("memory" to mapOf("total" to 8_000_000_000L, "used" to 3_000_000_000L),
                "battery" to mapOf("percent" to 54.0, "charging" to true, "source" to "usb", "temperature" to 31.5)) })
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
        // LoRa frames as the lora-sniffer reports them, through the driver: the recognised ones from the fixtures.
        status.register("lora:usb1", "type" to "lora", "iface" to "usb", "profiles" to listOf("meshtastic-eu868", "meshcore-eu868", "lorawan-eu868"),
            "lora_rev" to 0, "hw_usb" to "10c4:ea60")
        @Suppress("UNCHECKED_CAST")
        val loraLines = (fixture("lora.json")["classify"] as List<Map<String, Any?>>)
            .filter { (it["result"] as Map<String, Any?>)["protocol"] != "unknown" && (it["hex"] as String).isNotEmpty() }.take(40)
            .map { c ->
                val sync = (c["sync"] as Number).toInt()
                val (freq, bw, sf) = when (sync) { 0x2B -> Triple("869.5250", "250.0", 11); 0x34 -> Triple("868.1000", "125.0", 7); else -> Triple("869.6180", "62.5", 8) }
                "RX,1,$freq,$bw,$sf,0x%02X,-97.5,6.25,-1200,${(c["hex"] as String).length / 2},${c["hex"]}".format(sync)
            }
        val board = object : LineLink {
            val out = java.util.ArrayDeque<String>()
            var sniffer: LoraSniffer? = null
            var configured = false
            override fun writeLine(text: String) { if (text == "PING") out += "PONG" else if (text.startsWith("CFG")) { configured = true; out += "OK,CFG"; out.addAll(loraLines) } }
            override fun readLine(timeoutMs: Long): String? = out.pollFirst() ?: run { if (configured) sniffer?.stopped = true; null }
        }
        val sniffer = LoraSniffer(board, { LoraProfiles.parse("meshtastic-eu868") to 5.0 }, onFrame = { o, _, _ -> f.put(o); n++ })
        board.sniffer = sniffer
        sniffer.run()
        f.put(mapOf("t" to "chan", "ts" to now, "mac" to "-", "freq" to 5180, "dwell_ms" to 400, "frames" to 25, "bytes" to 4000,
            "airtime_us" to 1800, "retries" to 2, "noise_sum" to 0, "noise_cnt" to 0)); n++

        assertTrue("upload failed: ${f.lastError}", f.step())
        println("sent $n observations; server answered ${f.lastReply}")
        assertTrue("server rejected some: ${f.lastReply}", (f.lastReply?.get("rejected") as Number?)?.toInt() == 0)
        assertTrue((f.lastReply?.get("accepted") as Number?)?.toInt() == n)
    }
}
