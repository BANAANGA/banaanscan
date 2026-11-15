package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.ArrayDeque

/** The lora-sniffer firmware as its main.cpp answers, for the driver tests. */
private class FakeBoard(private val frames: List<String> = emptyList(), private val refuse: Set<Double> = emptySet()) : LineLink {
    val written = ArrayList<String>()
    private val out = ArrayDeque<String>()
    var sniffer: LoraSniffer? = null

    override fun writeLine(text: String) {
        written += text
        when {
            text == "PING" -> out += "PONG"
            text == "INFO" -> out += "INFO,lora-sniffer,1,heltec_v3,sx1262,idle,0.0000,0.0,0,0x00,0,0,2"
            text.startsWith("CFG,") -> {
                val freq = text.split(",")[1].toDouble()
                if (freq in refuse) out += "ERR,CFG,-12" else { out += "OK,CFG,$freq"; out.addAll(frames) }
            }
        }
    }

    override fun readLine(timeoutMs: Long): String? = out.pollFirst() ?: run { sniffer?.stopped = true; null }
}

class LoraSnifferTest {
    @Test fun `hops over the profiles and turns RX lines into lora_frame observations`() {
        val board = FakeBoard(listOf("RX,5,869.5250,250.0,11,0x2B,-97.5,6.25,-1200,4,deadbeef", "STAT,10000,1,3", "noise"))
        val obs = ArrayList<Map<String, Any?>>()
        val status = HashMap<String, Any?>()
        val profiles = LoraProfiles.parse("meshtastic-eu868,meshcore-eu868")
        val s = LoraSniffer(board, { profiles to 5.0 }, onStatus = { status.putAll(it) }, onFrame = { o, _, _ -> obs += o })
        board.sniffer = s
        s.run()
        assertEquals("PING", board.written[0])
        assertEquals("CFG,869.525,250,11,5,0x2B,8", board.written.first { it.startsWith("CFG") })
        assertEquals(1, obs.size)
        val o = obs[0]
        assertEquals("lora_frame", o["t"])
        assertEquals("meshtastic-eu868", o["profile"])
        assertEquals(0x2B, o["sync"])
        assertEquals("deadbeef", o["hex"])
        assertEquals(3L, status["crc_errors"])
        assertEquals(2, status["fw_version"])
        assertEquals("listening", status["state"])
    }

    @Test fun `a refused profile is reported and skipped`() {
        val board = FakeBoard(refuse = setOf(433.775))
        val status = HashMap<String, Any?>()
        val s = LoraSniffer(board, { LoraProfiles.parse("lora-aprs-433,lorawan-eu868") to 1.0 }, onStatus = { status.putAll(it) }, onFrame = { _, _, _ -> })
        board.sniffer = s
        s.run()
        assertTrue(board.written.any { it.startsWith("CFG,868.1,") })
    }

    @Test fun `a board that doesn't answer is not a sniffer`() {
        val silent = object : LineLink {
            override fun writeLine(text: String) {}
            override fun readLine(timeoutMs: Long): String? = null
        }
        var now = 0L
        val s = LoraSniffer(silent, { LoraProfiles.parse("") to 5.0 }, onFrame = { _, _, _ -> }, clock = { now += 600; now })
        assertTrue(runCatching { s.run() }.exceptionOrNull() is LoraException)
    }

    @Test fun `profiles from the server`() {
        val c = LoraControl()
        c.applyRemote(mapOf("rev" to 3, "profiles" to "lorawan:eu868:7-8", "hop_interval" to 2))
        val (list, hop) = c.effective(LoraProfiles.parse(""), 5.0)
        assertEquals(6, list.size)
        assertEquals(2.0, hop, 0.0)
        c.applyRemote(mapOf("rev" to 4, "profiles" to "nonsense"))
        assertEquals(3, c.rev)
        c.applyRemote(mapOf("rev" to 5, "profiles" to null, "hop_interval" to null))
        assertNull(c.override)
        assertEquals("the EU868 defaults", 5, c.effective(LoraProfiles.parse(""), 5.0).first.size)
        assertFalse(runCatching { LoraControl.clean(mapOf("hop_interval" to 0.1)) }.isSuccess)
    }

    @Test fun `the forwarder hands lora_config to the control`() {
        val c = LoraControl()
        val f = Forwarder(Target("http://x", "t"), StatusBoard(), LogBuffer(), emptyMap()) { _, _, _ ->
            """{"accepted":0,"lora_config":{"rev":7,"profiles":"meshtastic:US","hop_interval":3}}"""
        }
        f.onLoraConfig = c::applyRemote
        assertTrue(f.step())
        assertEquals(7, c.rev)
        assertEquals("meshtastic-us", c.effective(emptyList(), 5.0).first.single().name)
    }
}
