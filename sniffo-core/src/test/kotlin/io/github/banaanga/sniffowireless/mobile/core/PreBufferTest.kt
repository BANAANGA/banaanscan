package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.zip.GZIPInputStream

/** The last seconds of frames for the server's automatic recording (sniffowireless 35), as capture.PREBUF keeps them. */
class PreBufferTest {
    private fun waitFor(cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5000
        while (!cond()) { check(System.currentTimeMillis() < end) { "timed out" }; Thread.sleep(5) }
    }

    @Test fun `keeps the last seconds of the radios asked for, while the server says so`() {
        var now = 1_000_000L
        val r = Recorder { now }
        assertFalse(r.tapping)
        r.pre.applyRemote(mapOf("radios" to listOf("wifi", "sdr"), "seconds" to 30))
        assertTrue(r.tapping)
        assertEquals(setOf("wifi"), r.pre.radios)
        r.record("wifi", "wifi:mt7921u", ByteArray(30))
        r.record("bt", "bt:usb", byteArrayOf(4, 0x3e, 0))   // not asked for
        assertEquals(1, r.pre.size)
        now += 31_000
        r.record("wifi", "wifi:mt7921u", ByteArray(31))
        assertEquals("the older frame went", 1, r.pre.size)
        now += 60_000
        assertFalse("a minute without the server asking: it lets go", r.pre.active)
        r.pre.applyRemote(null)
        assertEquals(0, r.pre.size)
    }

    @Test fun `a recording the server asks for with pre starts with the seconds before`() {
        val r = Recorder()
        r.pre.applyRemote(mapOf("radios" to listOf("zigbee"), "seconds" to 30))
        r.record("zigbee", "zigbee:ezsp", byteArrayOf(2, 0, 7))
        var sent: ByteArray? = null
        val rc = RemoteCapture(r, Files.createTempDirectory("pre").toFile(), upload = { f, _ -> sent = f.readBytes() }, pause = { Thread.sleep(5) })
        rc.handle(mapOf("action" to "capture", "id" to "0123456789abcdef", "seconds" to 60, "radios" to listOf("zigbee"), "pre" to 30))
        waitFor { r.active }
        r.record("zigbee", "zigbee:ezsp", byteArrayOf(2, 0, 8))
        r.stop()
        waitFor { sent != null }
        val lines = GZIPInputStream(sent!!.inputStream()).bufferedReader().readLines().map { Json.parse(it) as Map<*, *> }
        assertEquals(listOf("020007", "020008"), lines.map { it["raw"] })
    }

    @Test fun `the forwarder arms it from the server's answer`() {
        val r = Recorder()
        var reply = """{"accepted":0,"arm":{"radios":["wifi"],"seconds":20}}"""
        val f = Forwarder(Target("http://server", "t"), StatusBoard(), LogBuffer(), emptyMap()) { _, _, _ -> reply }
        f.pre = r.pre
        assertTrue(f.step())
        assertTrue(r.pre.active)
        assertEquals(20, r.pre.seconds)
        reply = """{"accepted":0}"""
        assertTrue(f.step())
        assertFalse(r.pre.active)
    }
}
