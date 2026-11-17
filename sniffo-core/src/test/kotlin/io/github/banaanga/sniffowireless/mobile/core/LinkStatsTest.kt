package io.github.banaanga.sniffowireless.mobile.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The rate tables against the 802.11 tables (and the server's linkstats.phy), and the counter's limits. */
class LinkStatsTest {
    @Test fun `rates and labels`() {
        assertEquals(300.0 to "HT MCS 7 2SS 40", PhyRates.ht(15, 40, true))
        assertEquals(65.0 to "HT MCS 7 1SS 20", PhyRates.ht(7, 20, false))
        assertEquals(867.0 to "VHT MCS 9 2SS 80", PhyRates.vht(9, 2, 80, true))
        assertEquals(1201.1 to "HE MCS 11 2SS 80", PhyRates.he(11, 2, 80, 0))
        assertEquals(7.3, PhyRates.he(0, 1, 20, 2)!!.first, 0.01)
        assertNull(PhyRates.vht(12, 1, 20, false))
        assertNull(PhyRates.he(5, 1, 30, 0))
    }

    @Test fun `rate keys are written as the server writes them`() {
        assertEquals("24", LinkCounter.rateKey(24.0))
        assertEquals("5.5", LinkCounter.rateKey(5.5))
        assertEquals("1201.1", LinkCounter.rateKey(1201.1))
    }

    @Test fun `a minute passes before the counts go out`() {
        val c = LinkCounter()
        assertEquals(false, c.due(1000.0))
        assertEquals(false, c.due(1059.0))
        assertEquals(true, c.due(1060.0))
        c.drain(1060.0)
        assertEquals(false, c.due(1061.0))
    }

    @Test fun `a deauthentication flood is capped`() {
        val w = JoinWatcher()
        val deauth = byteArrayOf(0xC0.toByte(), 0, 0, 0) + ByteArray(6) { 0x02 } + ByteArray(6) { 0x04 } + ByteArray(6) { 0x04 } + byteArrayOf(0, 0, 7, 0)
        val n = (0 until 500).sumOf { w.feed(deauth, null, 100.0 + it / 1000.0).size }
        assertEquals(JoinWatcher.FLOOD, n)
    }
}
