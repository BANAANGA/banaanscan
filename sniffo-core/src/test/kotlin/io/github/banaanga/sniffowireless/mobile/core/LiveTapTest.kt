package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class LiveTapTest {
    private fun bytes(hex: String) = hexBytes(hex)!!
    private val qosData = bytes("88413000" + "02a0c5112233" + "da12f3a4b5c6" + "02a0c5112233" + "1000" + "0000") + ByteArray(40) { 7 }
    private val adv = Recorder.hciEvent(0x3E, bytes("0201000111223344556603020106e1"))

    @Test fun `frames of the radios asked for wait for the next upload, while the server asks`() {
        var now = 1_000_000L
        val r = Recorder { now }
        assertFalse(r.tapping)
        r.live.applyRemote(mapOf("radios" to listOf("bt", "wifi", "sdr"), "seconds" to 99))
        assertTrue(r.tapping)
        assertEquals(setOf("bt", "wifi"), r.live.radios)
        assertEquals("held for at most 15 s after the answer", now + 15_000, r.live.until)
        r.record("bt", "bt:usb", adv)
        r.record("wifi", "wifi:mt7921u", Recorder.radiotap(2437, -50) + qosData, mapOf("tuned" to 2437, "rssi" to null))
        r.record("zigbee", "zigbee:ezsp", byteArrayOf(2, 0, 9))   // not asked for
        val got = r.live.drain()
        assertEquals(listOf("bt", "wifi"), got.map { it["radio"] })
        assertEquals(Recorder.hex(adv), got[0]["raw"])
        assertEquals(1000.0, got[0]["ts"])
        assertEquals("of data frames only the headers", 16 + 26, hexBytes(got[1]["raw"] as String)!!.size)
        assertEquals(0, r.live.pending)
        now += 15_001   // no answer asked again
        assertFalse(r.tapping)
        r.record("bt", "bt:usb", adv)
        assertEquals(0, r.live.pending)
    }

    @Test fun `an answer without live stops it at once`() {
        val r = Recorder()
        r.live.applyRemote(mapOf("radios" to listOf("bt"), "seconds" to 10))
        r.record("bt", "bt:usb", adv)
        r.live.applyRemote(null)
        assertFalse(r.live.active)
        assertEquals(0, r.live.pending)
    }

    @Test fun `with too many frames the oldest go, counted, and at most 3000 go per upload`() {
        val r = Recorder()
        r.live.applyRemote(mapOf("radios" to listOf("bt"), "seconds" to 10))
        repeat(LiveTap.BUFFER + 25) { r.record("bt", "bt:usb", adv) }
        assertEquals(25L, r.live.dropped)
        assertEquals(LiveTap.SEND_MAX, r.live.drain().size)
        assertEquals(LiveTap.BUFFER - LiveTap.SEND_MAX, r.live.pending)
    }

    @Test fun `the forwarder applies the answer and sends the frames with the next upload`() {
        val r = Recorder()
        val bodies = ArrayList<Map<*, *>>()
        var reply = """{"accepted":0,"live":{"radios":["bt"],"seconds":15}}"""
        val f = Forwarder(Target("http://server", "t"), StatusBoard(), LogBuffer(), emptyMap()) { _, _, b ->
            bodies += Json.parse(GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)) as Map<*, *>
            reply
        }
        f.live = r.live
        assertTrue(f.step())
        assertFalse("nothing asked before this upload", bodies.last().containsKey("frames"))
        r.record("bt", "bt:usb", adv)
        reply = """{"accepted":0}"""
        assertTrue(f.step())
        val sent = bodies.last()["frames"] as List<*>
        assertEquals(1, sent.size)
        assertEquals(0, (bodies.last()["frames_dropped"] as Number).toInt())
        assertFalse("the server stopped asking", r.live.active)
        assertTrue(f.step())
        assertFalse(bodies.last().containsKey("frames"))
        // SNIFFO_LIVE_SAMPLE=<path> keeps an upload's frames, to feed them to sniffowireless's own live_ingest.
        System.getenv("SNIFFO_LIVE_SAMPLE")?.let { File(it).writeText(Json.encode(bodies[1]["frames"])) }
    }
}
