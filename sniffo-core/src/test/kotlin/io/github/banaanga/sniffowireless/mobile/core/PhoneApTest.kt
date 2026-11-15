package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhoneApTest {
    private fun hex(s: String) = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    @Suppress("UNCHECKED_CAST")
    private val cases = (Json.parse(PhoneApTest::class.java.getResourceAsStream("/fixtures/wifi.json")!!.readBytes().toString(Charsets.UTF_8))
        as Map<String, Any?>)["cases"] as List<Map<String, Any?>>

    @Test fun `phone scan with elements matches a captured beacon`() {
        for (name in listOf("beacon full WPA2", "beacon WPA3 only", "beacon transition", "beacon WPA1 + WPA2", "beacon 6 GHz", "beacon CSA on DFS")) {
            val c = cases.single { it["name"] == name }
            val frame = hex(c["frame"] as String)
            val beacon = WifiFrames.parse(frame, -50, (c["freq"] as Number).toInt(), null, 1.7e9, Gate(), AttrGates()).single()
            val elts = WifiFrames.elements(frame, 36)
            val privacy = frame[34].toInt() and 0x10 != 0
            val phone = WifiFrames.phoneAp(beacon["mac"] as String, null, -50, (c["freq"] as Number).toInt(), elts,
                if (privacy) "[WEP]" else "[ESS]", 1.7e9, Gate(), AttrGates())!!
            for (k in listOf("mac", "freq", "band", "channel", "ssid", "enc", "csa", "fp")) {
                assertEquals("$name: $k", Json.encode(beacon[k]), Json.encode(phone[k]))
            }
            @Suppress("UNCHECKED_CAST")
            val ap = ((beacon["extra"] as Map<String, Any?>)["ap"] as Map<String, Any?>).filterKeys { it !in setOf("bsig", "boot", "beacon_tu") }
            @Suppress("UNCHECKED_CAST")
            assertEquals("$name: extra.ap", Json.encode(ap), Json.encode((phone["extra"] as Map<String, Any?>)["ap"]))
        }
    }

    @Test fun `capability strings without elements`() {
        assertEquals("WPA2/PSK", WifiFrames.encFromCapabilities("[WPA2-PSK-CCMP][RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA3/SAE", WifiFrames.encFromCapabilities("[RSN-SAE-CCMP][ESS][MFPR][MFPC]"))
        assertEquals("WPA3-transition/PSK", WifiFrames.encFromCapabilities("[RSN-PSK+SAE-CCMP][ESS][MFPC]"))
        assertEquals("WPA/PSK,WPA2/PSK", WifiFrames.encFromCapabilities("[WPA-PSK-TKIP][RSN-PSK-CCMP][ESS]"))
        assertEquals("WPA2/802.1X", WifiFrames.encFromCapabilities("[RSN-EAP-CCMP][ESS]"))
        assertEquals("WEP", WifiFrames.encFromCapabilities("[WEP][ESS]"))
        assertEquals("OPN", WifiFrames.encFromCapabilities("[ESS]"))
        val o = WifiFrames.phoneAp("02:11:22:33:44:55", "", -70, 2437, null, "[ESS]", 1.0)!!
        assertEquals(6, o["channel"])
        assertNull(o["ssid"])
        assertEquals(mapOf("hidden" to true), o["extra"])
        assertNull("multicast BSSID", WifiFrames.phoneAp("03:11:22:33:44:55", "x", -70, 2437, null, "[ESS]", 1.0))
    }
}
