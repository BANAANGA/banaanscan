package com.example.banaanscan.mt7921u

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private fun hex(s: String) = s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class Dot11Test {
    private val ap = "02 11 22 33 44 55"
    private val sta = "06 aa bb cc dd ee"

    @Test fun `beacon gives network and name`() {
        val f = Dot11.parse(hex("8000 0000 ffffffffffff $ap $ap 1000" + "0000000000000000 6400 1104" + "00 07 486f6d654e6574"))!!
        assertTrue(f.isBeacon)
        assertEquals("02:11:22:33:44:55", f.bssid)
        assertEquals("02:11:22:33:44:55", f.transmitter)
        assertEquals("HomeNet", f.ssid)
    }

    @Test fun `hidden network and wildcard probe give an empty name`() {
        assertEquals("", Dot11.parse(hex("8000 0000 ffffffffffff $ap $ap 1000" + "00".repeat(12) + "00 04 00000000"))!!.ssid)
        val probe = Dot11.parse(hex("4000 0000 ffffffffffff $sta ffffffffffff 2000 0000"))!!
        assertTrue(probe.isProbeRequest)
        assertEquals("", probe.ssid)
        assertEquals("06:aa:bb:cc:dd:ee", probe.transmitter)
    }

    @Test fun `data frames are attributed to their network`() {
        val toAp = Dot11.parse(hex("8841 0000 $ap $sta ffffffffffff 3000 0000"))!!
        assertTrue(toAp.isData && toAp.protected)
        assertEquals("02:11:22:33:44:55", toAp.bssid)
        val fromAp = Dot11.parse(hex("8842 0000 $sta $ap 0a0b0c0d0e0f 3000 0000"))!!
        assertEquals("02:11:22:33:44:55", fromAp.bssid)
        assertNull(Dot11.parse(hex("8843 0000 $sta $ap 0a0b0c0d0e0f 3000 0000 0102030405"))!!.bssid)
        assertFalse(Dot11.parse(hex("0802 0000 $sta $ap $ap 3000"))!!.protected)
    }

    @Test fun `control frames and short frames`() {
        val ack = Dot11.parse(hex("d400 0000 $sta"))!!
        assertEquals(1, ack.type)
        assertNull(ack.transmitter)
        val rts = Dot11.parse(hex("b400 0000 $ap $sta"))!!
        assertEquals("06:aa:bb:cc:dd:ee", rts.transmitter)
        assertNull(Dot11.parse(hex("8000 0000")))
        assertNull(Dot11.parse(hex("8000 0000 ffffffffffff $ap")))
    }

    @Test fun `broken name elements are ignored`() {
        assertNull(Dot11.parse(hex("8000 0000 ffffffffffff $ap $ap 1000" + "00".repeat(12) + "00 30 41"))!!.ssid)
    }
}
