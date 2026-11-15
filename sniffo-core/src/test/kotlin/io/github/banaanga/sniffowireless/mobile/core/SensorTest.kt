package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.zip.GZIPInputStream

class SensorTest {
    private fun unzip(b: ByteArray) = GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)

    @Test fun `a batch carries observations, status, version and new log lines`() {
        val status = StatusBoard().apply { register("wifi:mt7921u", "type" to "wifi", "iface" to "mt7921u") }
        val logs = LogBuffer().apply { add("INFO", "test", "hello") }
        var url = ""
        var token = ""
        var body: Map<*, *>? = null
        val f = Forwarder(Target("http://server:8080/", "sniffowireless_abc"), status, logs, mapOf("version" to "mobile 1.0"),
            system = { mapOf("memory" to mapOf("total" to 8L)) }) { u, t, b -> url = u; token = t; body = Json.parse(unzip(b)) as Map<*, *>; """{"accepted":1}""" }
        f.put(mapOf("t" to "bt", "kind" to "ble", "ts" to 1.0, "mac" to "c3:11:22:33:44:55", "rssi" to null))
        assertTrue(f.step())
        assertEquals("http://server:8080/api/ingest", url)
        assertEquals("sniffowireless_abc", token)
        val b = body!!
        assertEquals(1, (b["obs"] as List<*>).size)
        assertFalse("nulls are left out", (b["obs"] as List<*>)[0].let { it as Map<*, *> }.containsKey("rssi"))
        assertEquals("wifi:mt7921u", ((b["status"] as List<*>)[0] as Map<*, *>)["name"])
        assertEquals("mobile 1.0", (b["version"] as Map<*, *>)["version"])
        assertEquals("hello", ((b["logs"] as List<*>)[0] as Map<*, *>)["msg"])
        assertEquals(logs.epoch, b["log_epoch"])
        assertTrue(b["sent_at"] is Double)
        // The next batch has no observations and only newer log lines.
        assertTrue(f.step())
        assertEquals(0, (body!!["obs"] as List<*>).size)
        assertEquals(0, (body!!["logs"] as List<*>).size)
        assertEquals(1L, f.sent)
    }

    @Test fun `a failed batch is kept and the wait doubles`() {
        var fail = true
        var got = 0
        val f = Forwarder(Target("http://x", "t"), StatusBoard(), LogBuffer(), emptyMap()) { _, _, b ->
            if (fail) throw IOException("down")
            got = ((Json.parse(unzip(b)) as Map<*, *>)["obs"] as List<*>).size
            "{}"
        }
        repeat(3) { f.put(mapOf("t" to "chan", "ts" to it.toDouble(), "mac" to "-", "freq" to 2412)) }
        assertFalse(f.step())
        assertEquals(4000, f.delayMs())
        assertFalse(f.step())
        assertEquals(8000, f.delayMs())
        assertEquals(3, f.pending)
        assertEquals("down", f.lastError)
        fail = false
        assertTrue(f.step())
        assertEquals(3, got)
        assertEquals(2000, f.delayMs())
        assertNull(f.lastError)
    }

    @Test fun `the buffer drops the oldest when full`() {
        val f = Forwarder(Target("http://x", "t"), StatusBoard(), LogBuffer(), emptyMap(), maxBuffer = 2) { _, _, _ -> "{}" }
        repeat(5) { f.put(mapOf("ts" to it)) }
        assertEquals(2, f.pending)
        assertEquals(3L, f.dropped)
    }

    @Test fun `channel settings from the reply`() {
        val wifi = WifiControl("mt7921u")
        val f = Forwarder(Target("http://x", "t"), StatusBoard(), LogBuffer(), emptyMap()) { _, _, _ ->
            """{"accepted":0,"rejected":0,"channel_config":{"rev":3,"channels":"1,6,11","per_iface":{},"regdomain":"nl","hop_interval":0.5}}"""
        }
        f.onChannelConfig = wifi::applyRemote
        assertTrue(f.step())
        assertEquals(3, wifi.rev)
        assertEquals(listOf(2412, 2437, 2462), wifi.channels(listOf(5180)))
        assertEquals(0.5, wifi.hopInterval(0.4), 0.0)
        assertEquals("NL", wifi.override!!.regdomain)
    }

    @Test fun `per-adapter list, invalid revisions and going back to the sensor's own`() {
        val wifi = WifiControl("mt7921u")
        wifi.applyRemote(mapOf("rev" to 1, "channels" to "default", "per_iface" to mapOf("mt7921u" to "6g:37")))
        assertEquals(listOf(6135), wifi.channels(emptyList()))
        wifi.applyRemote(mapOf("rev" to 2, "channels" to "1,6,999"))
        assertEquals("a bad revision is refused", 1, wifi.rev)
        wifi.applyRemote(mapOf("rev" to 2, "channels" to "1"))
        assertEquals("and not retried", 1, wifi.rev)
        wifi.applyRemote(mapOf("rev" to 4, "channels" to null, "per_iface" to emptyMap<String, String>(), "regdomain" to null, "hop_interval" to null))
        assertEquals(4, wifi.rev)
        assertNull(wifi.override)
        assertEquals(listOf(5180), wifi.channels(listOf(5180)))
    }

    @Test fun `zigbee settings`() {
        val zb = ZigbeeControl()
        zb.applyRemote(mapOf("rev" to 2, "channels" to "15,20-22", "hop_interval" to "3", "scan_interval" to 900, "sniff" to false))
        assertEquals(listOf(15, 20, 21, 22), zb.channels((11..26).toList()))
        assertEquals(3.0, zb.hopInterval(2.0), 0.0)
        assertEquals(900, zb.scanInterval(600))
        assertFalse(zb.sniff(true))
        zb.applyRemote(mapOf("rev" to 3, "channels" to "9-12"))
        assertEquals(2, zb.rev)
    }

    @Test fun `json round trip`() {
        val v = mapOf("a" to listOf(1, 2.5, "x\"y\n", true, null), "b" to mapOf("c" to -3L), "d" to null)
        assertEquals("""{"a":[1,2.5,"x\"y\n",true,null],"b":{"c":-3}}""", Json.encode(v))
        assertEquals(mapOf("a" to listOf(1L, 2.5, "x\"y\n", true, null), "b" to mapOf("c" to -3L)), Json.parse(Json.encode(v)))
        assertEquals("1700000000.0", Json.encode(1.7e9))
    }
}
