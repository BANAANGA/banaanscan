package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.util.zip.GZIPInputStream

class CaptureTest {
    private fun lines(f: File): List<Map<*, *>> =
        GZIPInputStream(f.inputStream()).bufferedReader().readLines().map { Json.parse(it) as Map<*, *> }

    private fun bytes(hex: String) = hexBytes(hex)!!

    private val beacon = bytes("80000000ffffffffffff02a0c511223302a0c51122334006") + ByteArray(12) + bytes("0007") + "HomeNet".toByteArray()
    private val qosData = bytes("88413000" + "02a0c5112233" + "da12f3a4b5c6" + "02a0c5112233" + "1000" + "0000") + ByteArray(40) { 7 }

    @Test fun `records the radios asked for, in sniffowireless's format`() {
        val dir = Files.createTempDirectory("rec").toFile()
        var now = 1_700_000_000_000L
        val r = Recorder { now }
        val f = r.start(dir, 30, listOf("wifi", "bt", "zigbee", "lora"))
        assertTrue(f.name.matches(Regex("capture-\\d{8}-\\d{6}\\.jsonl\\.gz")))
        r.record("wifi", "wifi:mt7921u", Recorder.radiotap(2437, -52) + beacon, mapOf("tuned" to 2437))
        r.record("wifi", "wifi:mt7921u", Recorder.radiotap(5180, null) + qosData, mapOf("tuned" to 5180))
        r.record("bt", "bt:usb", Recorder.hciEvent(0x3E, bytes("0201000111223344556603020106e1")))
        r.record("zigbee", "zigbee:ezsp", bytes("0200 28".replace(" ", "")), mapOf("channel" to 15, "rssi" to -60, "lqi" to 200))
        r.record("lora", "lora:heltec", Recorder.loraLine(LoraRx(869.525, 250.0, 11, 0x2B, -104.0, -3.5, -1200.0, "deadbeef")).toByteArray())
        r.record("sdr", "x", ByteArray(3))   // not a radio it knows
        assertEquals(5, r.frames)
        assertEquals(f, r.stop())
        val got = lines(f)
        assertEquals(listOf("wifi", "wifi", "bt", "zigbee", "lora"), got.map { it["radio"] })
        assertEquals(1_700_000_000.0, got[0]["ts"])
        assertEquals("wifi:mt7921u", got[0]["src"])
        assertEquals(2437, (got[0]["tuned"] as Number).toInt())
        assertEquals(15, (got[3]["channel"] as Number).toInt())
        val rt = bytes(got[0]["raw"] as String)
        assertEquals(16, rt[2].toInt())
        assertEquals(0x2A, rt[4].toInt())                    // flags, channel, antenna signal
        assertEquals(2437, (rt[10].toInt() and 0xFF) or ((rt[11].toInt() and 0xFF) shl 8))
        assertEquals(-52, rt[14].toInt())
        assertArrayEquals(beacon, rt.copyOfRange(16, rt.size))
        assertEquals("of a data frame only its headers are kept", 16 + 26, bytes(got[1]["raw"] as String).size)
        assertEquals(0x0A, bytes(got[1]["raw"] as String)[4].toInt())  // flags and channel: no signal, so its present bit is not set
        assertArrayEquals(bytes("043e0f0201000111223344556603020106e1"), bytes(got[2]["raw"] as String))
        assertEquals("RX,0,869.525,250.0,11,0x2b,-104.0,-3.5,-1200.0,4,deadbeef", String(bytes(got[4]["raw"] as String)))
        // SNIFFO_CAPTURE_SAMPLE=<path> keeps the file, to open it with sniffowireless's own decoders.
        System.getenv("SNIFFO_CAPTURE_SAMPLE")?.let { f.copyTo(File(it), overwrite = true) }
    }

    @Test fun `a recording stops when its time is up, with or without frames`() {
        val dir = Files.createTempDirectory("rec").toFile()
        var now = 0L
        val r = Recorder { now }
        r.start(dir, 2, listOf("bt"))
        now = 1_000
        r.tick()
        assertTrue(r.active)
        now = 2_000
        r.tick()
        assertFalse(r.active)
        r.record("bt", "bt:usb", ByteArray(4))
        assertEquals(0, r.frames)
        r.start(dir, 2, listOf("bt"))
        now = 4_500
        r.record("bt", "bt:usb", byteArrayOf(4, 0x0E, 0))   // this one is written, then the time is up
        assertFalse(r.active)
        assertEquals(1, r.frames)
    }

    @Test fun `only one recording at a time`() {
        val r = Recorder()
        r.start(Files.createTempDirectory("rec").toFile(), 60, listOf("wifi"))
        val e = runCatching { r.start(Files.createTempDirectory("rec").toFile(), 60, listOf("wifi")) }.exceptionOrNull()
        assertEquals("A recording is already running", e?.message)
        r.stop()
    }

    private fun waitFor(what: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!what() && System.currentTimeMillis() < end) Thread.sleep(10)
        assertTrue(what())
    }

    @Test fun `records at the server's request, reports, uploads and removes the file`() {
        val dir = Files.createTempDirectory("rc").toFile()
        val r = Recorder()
        var sent: Pair<String, ByteArray>? = null
        val rc = RemoteCapture(r, dir, upload = { f, id -> sent = id to f.readBytes() }, pause = { Thread.sleep(5) })
        assertNull(rc.report())
        rc.handle(mapOf("action" to "capture", "name" to "capture", "id" to "0123456789abcdef", "seconds" to 60, "radios" to listOf("zigbee")))
        waitFor { r.active }
        r.record("zigbee", "zigbee:ezsp", byteArrayOf(2, 0, 9))
        r.record("wifi", "wifi:mt7921u", ByteArray(30))   // not asked for
        assertEquals(mapOf("id" to "0123456789abcdef", "state" to "recording", "frames" to 1), rc.report())
        r.stop()
        waitFor { sent != null && rc.report() == null }
        assertEquals("0123456789abcdef", sent!!.first)
        assertEquals(1, GZIPInputStream(sent!!.second.inputStream()).bufferedReader().readLines().size)
        assertEquals("the file is gone from the phone", 0, dir.listFiles()!!.size)
    }

    @Test fun `a failed upload is retried, then reported`() {
        val r = Recorder()
        var tries = 0
        val rc = RemoteCapture(r, Files.createTempDirectory("rc").toFile(), upload = { _, _ -> tries++; throw java.io.IOException("HTTP 502") },
            pause = { })
        rc.handle(mapOf("id" to "00000000000000ff", "seconds" to 60))
        waitFor { r.active }
        r.stop()
        waitFor { rc.report()?.get("state") == "failed" }
        assertEquals(4, tries)
        assertEquals("HTTP 502", rc.report()!!["error"])
    }

    @Test fun `requests that are not well formed are ignored`() {
        val r = Recorder()
        val rc = RemoteCapture(r, Files.createTempDirectory("rc").toFile(), upload = { _, _ -> })
        rc.handle(mapOf("id" to "../../etc"))
        rc.handle(mapOf("seconds" to 5))
        Thread.sleep(50)
        assertFalse(r.active)
        assertNull(rc.report())
    }

    @Test fun `the forwarder passes the request on and sends the progress`() {
        val r = Recorder()
        val rc = RemoteCapture(r, Files.createTempDirectory("rc").toFile(), upload = { _, _ -> }, pause = { Thread.sleep(5) })
        var body: Map<*, *>? = null
        var reply = """{"accepted":0,"commands":[{"action":"capture","name":"capture","id":"0123456789abcdef","seconds":60,"radios":["bt"]}]}"""
        val f = Forwarder(Target("http://server", "t"), StatusBoard(), LogBuffer(), emptyMap()) { _, _, b ->
            body = Json.parse(GZIPInputStream(b.inputStream()).readBytes().toString(Charsets.UTF_8)) as Map<*, *>
            reply
        }
        f.capture = rc
        assertTrue(f.step())
        assertFalse("nothing to report before", body!!.containsKey("capture"))
        waitFor { r.active }
        reply = """{"accepted":0}"""
        assertTrue(f.step())
        assertEquals("recording", (body!!["capture"] as Map<*, *>)["state"])
        r.stop()
        waitFor { rc.report() == null }
    }

    @Test fun `trims data frames to their headers like the server does`() {
        val rt = Recorder.radiotap(2412, -40)
        assertArrayEquals(rt + beacon, Recorder.trimWifi(rt + beacon))
        assertEquals(16 + 26, Recorder.trimWifi(rt + qosData).size)
        val fourAddr = bytes("08030000") + ByteArray(26) + ByteArray(50)
        assertEquals(16 + 30, Recorder.trimWifi(rt + fourAddr).size)
        assertArrayEquals(byteArrayOf(1, 2), Recorder.trimWifi(byteArrayOf(1, 2)))
    }
}
