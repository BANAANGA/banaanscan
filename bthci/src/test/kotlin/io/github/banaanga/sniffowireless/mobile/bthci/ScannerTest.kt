package io.github.banaanga.sniffowireless.mobile.bthci

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScannerTest {
    private val scanner = Scanner(Hci(FakeIo { emptyList() }))

    private fun ev(s: String) = hex(s).let { Event(it[0].u8, it.copyOfRange(2, it.size)) }

    @Test fun `legacy advertising report`() {
        // ADV_IND, random address, flags + name "ABC", -60 dBm
        val a = scanner.decode(ev("3e 14 02 01 00 01 665544332211 08 020106 04 09 414243 c4")).single() as Advert
        assertEquals("11:22:33:44:55:66", a.address)
        assertTrue(a.random && a.connectable && a.legacy && !a.scanResponse)
        assertEquals(-60, a.rssi)
        assertEquals("ABC", a.ad.name)
        assertEquals(6, a.ad.flags)
    }

    @Test fun `scan response`() {
        val a = scanner.decode(ev("3e 0c 02 01 04 00 665544332211 00 b0")).single() as Advert
        assertTrue(a.scanResponse)
        assertFalse(a.connectable)
        assertEquals(-80, a.rssi)
    }

    private fun ext(type: String, data: String, phy: String = "03 00") =
        "3e %02x 0d 01 %s 01 665544332211 %s 02 7f d3 0000 00 000000000000 %02x %s".format(
            2 + 24 + hex(data).size, type, phy, hex(data).size, data)

    @Test fun `extended report on Coded PHY`() {
        val a = scanner.decode(ev(ext("0100", "03 ff 4c00"))).single() as Advert
        assertEquals(3, a.primaryPhy)
        assertEquals(-45, a.rssi)
        assertNull(a.txPower)
        assertFalse(a.legacy)
        assertTrue(a.connectable)
        assertArrayEquals(ByteArray(0), a.ad.manufacturer[0x004c])
    }

    @Test fun `extended report fragments are joined`() {
        // status 1 (more to come) in bits 5-6 of the event type
        assertTrue(scanner.decode(ev(ext("2000", "06 09 4c6f6e"))).isEmpty())
        val a = scanner.decode(ev(ext("0000", "6721"))).single() as Advert
        assertEquals("Long!", a.ad.name)
    }

    @Test fun `extended inquiry result`() {
        val eir = "05 09 50686f6e" + "00".repeat(10)
        val r = scanner.decode(ev("2f %02x 01 665544332211 01 00 0c025a 1234 d8 %s".format(15 + hex(eir).size, eir))).single() as InquiryResponse
        assertEquals("11:22:33:44:55:66", r.address)
        assertEquals(0x5a020c, r.classOfDevice)
        assertEquals(-40, r.rssi)
        assertEquals("Phon", r.eir!!.name)
    }

    @Test fun `inquiry result with RSSI and completion`() {
        val r = scanner.decode(ev("22 0f 01 665544332211 01 00 040420 1234 c0")).single() as InquiryResponse
        assertEquals(0x200404, r.classOfDevice)
        assertEquals(-64, r.rssi)
        assertEquals(listOf(InquiryComplete), scanner.decode(ev("01 01 00")))
    }

    @Test fun `short events are ignored`() {
        assertTrue(scanner.decode(ev("3e 05 02 01 00 01 66")).isEmpty())
    }

    @Test fun `advertising data fields`() {
        val ad = AdData.parse(hex("02 01 1a" + "05 03 0f18 0a18" + "11 07 fb349b5f800000800010000000feffff" + "03 08 4d79" + "00" + "03 09 4142"))
        assertEquals(0x1a, ad.flags)
        assertEquals(listOf("180f", "180a", "fffffe00-0000-1000-8000-00805f9b34fb"), ad.uuids)
        assertEquals("My", ad.name) // a zero length ends the list, as EIR padding does
        assertFalse(ad.completeName)
        val full = AdData.parse(hex("02 0a f4" + "03 19 c103" + "05 16 aafe 1020" + "05 ff 0600 0102" + "03 08 4d79" + "0a 09 4d7920446576696365" + "0000"))
        assertEquals("My Device", full.name)
        assertTrue(full.completeName)
        assertEquals(-12, full.txPower)
        assertEquals(0x03c1, full.appearance)
        assertArrayEquals(hex("1020"), full.serviceData["feaa"])
        assertArrayEquals(hex("0102"), full.manufacturer[6])
    }

    @Test fun `base UUIDs are shortened`() {
        assertEquals("180d", AdData.uuid128(hex("fb349b5f80000080001000000d180000"), 0))
        assertEquals("180d", AdData.uuid32(0x180d))
        assertEquals("1234abcd-0000-1000-8000-00805f9b34fb", AdData.uuid32(0x1234abcd))
    }
}
