package com.example.banaanscan.zigbee

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Expected bytes were computed by a separate Python script following bellows and zigpy-znp. */
class ZigbeeCodecTest {
    @Test fun `ZNP frames match reference bytes`() {
        assertArrayEquals(hex("FE 00 21 01 20"), ZnpCodec.encode(0x21, 0x01))
        assertArrayEquals(hex("FE 00 21 02 23"), ZnpCodec.encode(0x21, 0x02))
        assertArrayEquals(hex("FE 03 21 08 84 00 00 AE"), ZnpCodec.encode(0x21, 0x08, hex("84 00 00")))
        assertArrayEquals(
            hex("FE 05 25 26 00 F8 FF 07 04 02"),
            ZnpCodec.encode(0x25, 0x26, u32leBytes(ALL_CHANNELS_MASK) + 4.toByte()),
        )
    }

    @Test fun `ZNP parser reassembles split frames and skips garbage`() {
        val parser = ZnpCodec.Parser()
        val good = ZnpCodec.encode(0x61, 0x01, hex("D9 07"))
        val corrupt = ZnpCodec.encode(0x61, 0x02, hex("01 02")).also { it[it.size - 1] = 0 }
        val stream = hex("00 7E 11") + corrupt + good
        val frames = stream.flatMap { parser.feed(byteArrayOf(it)) }
        assertEquals(1, frames.size)
        assertEquals(0x61, frames[0].cmd0)
        assertEquals(0x01, frames[0].cmd1)
        assertArrayEquals(hex("D9 07"), frames[0].data)
    }

    @Test fun `ZNP beacon fields land in the right place`() {
        val network = ZnpCodec.parseBeacons(byteArrayOf(1) + SimulatedZnpDongle.BEACON_HOME_ROUTER).single()
        assertEquals("11:22:33:44:55:66:77:88", network.extendedPanId)
        assertEquals(0x1234, network.panId)
        assertEquals(15, network.channel)
        assertTrue(network.permitJoining)
        assertEquals(true, network.routerCapacity)
        assertEquals(true, network.deviceCapacity)
        assertEquals(2, network.protocolVersion)
        assertEquals(2, network.stackProfile)
        assertEquals(220, network.lqi)
        assertEquals(5, network.updateId)
        assertEquals(setOf(0xABCD), network.routers)
    }

    @Test fun `truncated beacon list does not crash`() {
        assertEquals(0, ZnpCodec.parseBeacons(hex("03 00 00 34")).size)
        assertEquals(0, ZnpCodec.parseBeacons(ByteArray(0)).size)
    }

    @Test fun `CRC matches the CCITT-FALSE check value`() {
        assertEquals(0x29B1, AshCodec.crc16("123456789".toByteArray()))
    }

    @Test fun `ASH frames match reference bytes`() {
        val reset = AshCodec.encodeReset()
        assertArrayEquals(ByteArray(32) { 0x1A }, reset.copyOf(32))
        assertArrayEquals(hex("C0 38 BC 7E"), reset.copyOfRange(32, reset.size))
        assertArrayEquals(hex("C1 02 0B 0A 52 7E"), AshCodec.encodeRStack(0x0B))
        assertArrayEquals(hex("83 40 1B 7E"), AshCodec.encodeAck(3))
        assertArrayEquals(hex("A1 44 3B 7E"), AshCodec.encodeNak(1))
        assertArrayEquals(hex("00 42 21 A8 50 ED 2C 7E"), AshCodec.encodeData(0, false, 0, hex("00 00 00 04")))
        assertArrayEquals(
            hex("21 47 21 A9 4E 2A 14 B2 A1 6B 4D 21 33 F4 7E"),
            AshCodec.encodeData(2, false, 1, hex("05 00 01 1A 00 01 00 F8 FF 07 04")),
        )
        assertArrayEquals(
            hex("5C 3C 30 D5 47 32 0F 4D 59 63 56 7E"),
            AshCodec.encodeData(5, true, 4, hex("7E 11 7D 13 18 1A FF 00")),
        )
    }

    @Test fun `ASH round trips every byte value including reserved ones`() {
        val parser = AshCodec.Parser()
        val payload = ByteArray(200) { it.toByte() }
        // Also cover bytes that only become reserved after randomisation.
        val wire = AshCodec.encodeData(3, false, 6, payload) + AshCodec.encodeData(4, true, 7, ByteArray(200) { (255 - it).toByte() })
        val frames = wire.flatMap { parser.feed(byteArrayOf(it)).frames }
        assertEquals(2, frames.size)
        val first = frames[0] as AshCodec.Frame.Data
        assertEquals(3, first.frameNumber)
        assertEquals(6, first.ackNumber)
        assertFalse(first.retransmit)
        assertArrayEquals(payload, first.payload)
        assertTrue((frames[1] as AshCodec.Frame.Data).retransmit)
    }

    @Test fun `ASH parser reports damaged data frames and honours cancel and substitute`() {
        val parser = AshCodec.Parser()
        val damaged = AshCodec.encodeData(0, false, 0, hex("01 02 03")).also { it[1] = (it[1] + 1).toByte() }
        assertEquals(1, parser.feed(damaged).corruptData)
        assertEquals(0, parser.feed(hex("05 06 1A") + AshCodec.encodeAck(1)).corruptData)
        assertEquals(0, parser.feed(hex("05 18 06") + AshCodec.encodeAck(2)).frames.size)
        assertEquals(AshCodec.Frame.Ack(3), parser.feed(AshCodec.encodeAck(3)).frames.single())
    }

    @Test fun `EZSP headers follow the protocol version`() {
        assertArrayEquals(hex("07 00 1A 09"), EzspFrames.encode(4, 7, 0x1A, hex("09")))
        assertArrayEquals(hex("07 00 FF 00 1A 09"), EzspFrames.encode(7, 7, 0x1A, hex("09")))
        assertArrayEquals(hex("07 00 01 1A 00 09"), EzspFrames.encode(13, 7, 0x1A, hex("09")))
        assertEquals(0x1A, EzspFrames.decode(4, hex("07 80 1A 09"))?.frameId)
        assertEquals(0x1A, EzspFrames.decode(7, hex("07 80 FF 00 1A 09"))?.frameId)
        assertEquals(0x1A, EzspFrames.decode(13, hex("07 80 01 1A 00 09"))?.frameId)
        assertNull(EzspFrames.decode(13, hex("07 80")))
    }

    @Test fun `EZSP network found fields land in the right place`() {
        val network = EzspFrames.parseNetworkFound(SimulatedEzspDongle.NETWORK_HOME)
        assertNotNull(network)
        assertEquals("11:22:33:44:55:66:77:88", network!!.extendedPanId)
        assertEquals(0x1234, network.panId)
        assertEquals(15, network.channel)
        assertTrue(network.permitJoining)
        assertEquals(2, network.stackProfile)
        assertEquals(5, network.updateId)
        assertEquals(200, network.lqi)
        assertEquals(-60, network.rssi)
        assertNull(EzspFrames.parseNetworkFound(ByteArray(15)))
    }

    @Test fun `merging keeps the best signal and every router`() {
        val a = ZnpCodec.parseBeacons(byteArrayOf(1) + SimulatedZnpDongle.BEACON_HOME_COORDINATOR).single()
        val b = ZnpCodec.parseBeacons(byteArrayOf(1) + SimulatedZnpDongle.BEACON_HOME_ROUTER).single()
        assertEquals(a.key, b.key)
        val merged = a.mergedWith(b)
        assertEquals(220, merged.lqi)
        assertEquals(setOf(0x0000, 0xABCD), merged.routers)
        assertTrue(merged.permitJoining)
        assertEquals(a, a.mergedWith(null))
    }
}

class ZigbeeDriverTest {
    private class Collector : ScanListener {
        val networks = ArrayList<ZigbeeNetwork>()
        val energy = ArrayList<ChannelEnergy>()
        override fun onNetwork(network: ZigbeeNetwork) { networks += network }
        override fun onEnergy(energy: ChannelEnergy) { this.energy += energy }
        override fun onProgress(message: String) = Unit
    }

    private fun scan(link: SerialLink): Pair<ZigbeeDriver, Collector> = runBlocking {
        val driver = checkNotNull(DongleDetector.connect(link)) { "dongle not detected" }
        val collector = Collector()
        driver.scan(collector)
        driver to collector
    }

    @Test fun `detects a Z-Stack dongle and finds its networks`() {
        val (driver, result) = scan(SimulatedZnpDongle())
        assertEquals(DongleFamily.ZSTACK, driver.info.family)
        assertEquals("Z-Stack 2.7.1 (build 20223387)", driver.info.firmware)
        assertEquals(3, result.networks.size)
        assertEquals(setOf(0x1234, 0xABCD), result.networks.map { it.panId }.toSet())
        assertTrue(result.energy.isEmpty())
    }

    @Test fun `Z-Stack scan only sends read-only commands`() {
        val dongle = SimulatedZnpDongle()
        scan(dongle)
        // Ping, version, NV read, network discovery. Crucially no NV write (0x09) or reset.
        assertEquals(setOf(0x21 to 0x01, 0x21 to 0x02, 0x21 to 0x08, 0x25 to 0x26), dongle.received.toSet())
    }

    @Test fun `warns when a Z-Stack dongle is limited to some channels`() {
        val (driver, _) = scan(SimulatedZnpDongle(configuredChannels = 1L shl 15))
        assertTrue(driver.info.notes.any { "channel 15" in it })
    }

    @Test fun `Z-Stack scan refusal becomes a readable error`() {
        val message = runCatching { scan(SimulatedZnpDongle(scanStatus = 1)) }.exceptionOrNull()
        assertTrue(message is ZigbeeException)
    }

    @Test fun `detects an EZSP dongle and runs energy and network scans`() {
        val (driver, result) = scan(SimulatedEzspDongle())
        assertEquals(DongleFamily.EZSP, driver.info.family)
        assertEquals("EmberZNet 7.4.3.0 (EZSP v13)", driver.info.firmware)
        assertEquals((11..26).toList(), result.energy.map { it.channel })
        assertEquals(-79, result.energy.first().rssi)
        assertEquals(2, result.networks.size)
        assertEquals(-60, result.networks[0].rssi)
    }

    @Test fun `EZSP scan only sends read-only commands`() {
        val dongle = SimulatedEzspDongle()
        scan(dongle)
        assertEquals(setOf(0x00, 0x1A), dongle.received.toSet())
    }

    @Test fun `supports old and new EZSP versions`() {
        for (version in listOf(4, 7, 8, 14, 19)) {
            val (driver, result) = scan(SimulatedEzspDongle(ezspVersion = version))
            assertTrue("v$version", "EZSP v$version" in driver.info.firmware)
            assertEquals("v$version", 2, result.networks.size)
        }
    }

    @Test fun `EZSP retransmits a lost frame`() {
        val dongle = SimulatedEzspDongle(dropFrames = 1)
        val (_, result) = scan(dongle)
        assertEquals(1, dongle.retransmissions)
        assertEquals(2, result.networks.size)
    }

    @Test fun `EZSP scan refusal becomes a readable error`() {
        val failure = runCatching { scan(SimulatedEzspDongle(scanStatus = 0x35)) }.exceptionOrNull()
        assertTrue(failure is ZigbeeException)
        assertTrue("35" in failure!!.message!!)
    }

    @Test fun `a silent device is reported as unrecognised`() = runBlocking {
        assertNull(DongleDetector.connect(SilentLink()))
    }
}

class Ieee802154Test {
    @Test fun `Zigbee data frame shares one PAN`() {
        val f = Ieee802154.parse(SimulatedEzspDongle.FRAME_ZIGBEE_DATA)!!
        assertEquals(FrameType.DATA, f.type)
        assertFalse(f.secured)
        assertEquals(0x2A, f.sequence)
        assertEquals(0x1234, f.destinationPan)
        assertEquals(0x1234, f.sourcePan)
        assertTrue(f.destination!!.isBroadcast)
        assertEquals(0x0000, f.source!!.short)
        assertEquals(ProtocolHint.ZIGBEE, f.hint)
    }

    @Test fun `beacon payload reveals Zigbee and Thread`() {
        assertEquals(ProtocolHint.ZIGBEE, Ieee802154.parse(SimulatedEzspDongle.FRAME_ZIGBEE_BEACON)!!.hint)
        val thread = hex("00 80 2B 34 12 00 00 FF CF 00 00 03 21")
        assertEquals(ProtocolHint.THREAD, Ieee802154.parse(thread)!!.hint)
        val other = hex("00 80 2B 34 12 00 00 FF CF 00 00 7A 21")
        assertEquals(ProtocolHint.UNKNOWN, Ieee802154.parse(other)!!.hint)
    }

    @Test fun `radio layer encryption is reported and extended addresses read MSB first`() {
        val f = Ieee802154.parse(SimulatedEzspDongle.FRAME_ENCRYPTED)!!
        assertTrue(f.secured)
        assertEquals(0xABCD, f.sourcePan)
        assertEquals(0x0001, f.destination!!.short)
        assertEquals("01:02:03:04:05:06:07:08", f.source!!.extended)
        assertEquals(ProtocolHint.ENCRYPTED, f.hint)
    }

    @Test fun `ack and beacon request carry what they should`() {
        val ack = Ieee802154.parse(SimulatedEzspDongle.FRAME_ACK)!!
        assertEquals(FrameType.ACK, ack.type)
        assertNull(ack.source)
        assertNull(ack.destination)
        val request = Ieee802154.parse(hex("03 08 05 FF FF FF FF 07"))!!
        assertEquals(FrameType.COMMAND, request.type)
        assertEquals(0xFFFF, request.destinationPan)
        assertNull(request.source)
        assertArrayEquals(hex("07"), request.payload)
    }

    @Test fun `2015 revision omits sequence and PAN when told to`() {
        // Data, PAN compression, sequence suppressed, extended to extended, version 2.
        val f = Ieee802154.parse(hex("41 ED 01 02 03 04 05 06 07 08 11 12 13 14 15 16 17 18 AA"))!!
        assertNull(f.sequence)
        assertNull(f.destinationPan)
        assertNull(f.sourcePan)
        assertEquals("08:07:06:05:04:03:02:01", f.destination!!.extended)
        assertEquals("18:17:16:15:14:13:12:11", f.source!!.extended)
        assertArrayEquals(hex("AA"), f.payload)
    }

    @Test fun `truncated and reserved frames are rejected without crashing`() {
        assertNull(Ieee802154.parse(hex("41")))
        assertNull(Ieee802154.parse(hex("41 88")))
        assertNull(Ieee802154.parse(hex("41 88 2A 34")))
        assertNull(Ieee802154.parse(hex("41 88 2A 34 12 FF FF 00")))
        assertNull(Ieee802154.parse(hex("41 84 2A 34 12 FF FF 00 00"))) // Reserved address mode.
        val random = java.util.Random(42)
        repeat(20_000) {
            val junk = ByteArray(random.nextInt(40)).also(random::nextBytes)
            Ieee802154.parse(junk)?.hint
        }
    }

    @Test fun `log groups radios and keeps the most telling evidence`() {
        val log = SnifferLog(vendorOf = { if (it.startsWith("01:02:03")) "ACME" else null })
        log.add(CapturedFrame(15, 200, -60, SimulatedEzspDongle.FRAME_ZIGBEE_BEACON), now = 1)
        log.add(CapturedFrame(15, 180, -70, SimulatedEzspDongle.FRAME_ZIGBEE_DATA), now = 2)
        log.add(CapturedFrame(15, 180, -70, SimulatedEzspDongle.FRAME_ACK), now = 3)
        log.add(CapturedFrame(20, 90, -80, SimulatedEzspDongle.FRAME_ENCRYPTED), now = 4)
        log.add(CapturedFrame(20, 90, -80, hex("FF")), now = 5)
        assertEquals(5, log.totalFrames)
        assertEquals(1, log.undecodedFrames)
        assertEquals(listOf(15 to 3, 20 to 2), log.channels().map { it.channel to it.frames })
        assertEquals(-60, log.channels()[0].strongestRssi)

        val devices = log.devices().associateBy { it.key }
        val coordinator = devices.getValue("${0x1234}/0x0000")
        assertEquals(2, coordinator.framesSent)
        assertEquals(ProtocolHint.ZIGBEE, coordinator.hint)
        assertEquals(setOf(FrameType.BEACON, FrameType.DATA), coordinator.types)
        // The broadcast address isn't a device.
        assertFalse(devices.keys.any { it.endsWith("0xFFFF") })

        val sender = devices.getValue("${0xABCD}/01:02:03:04:05:06:07:08")
        assertEquals(1, sender.encryptedFrames)
        assertEquals("ACME", sender.possibleVendor)
        assertEquals(-80, sender.rssi)
        assertEquals(ProtocolHint.ENCRYPTED, sender.hint)

        val receiver = devices.getValue("${0xABCD}/0x0001")
        assertFalse(receiver.heardDirectly)
        assertNull(receiver.rssi)
    }

    @Test fun `log stops growing at its device limit`() {
        val log = SnifferLog(maxDevices = 2)
        for (i in 1..5) log.add(CapturedFrame(11, 1, -1, hex("41 88 01 34 12 FF FF 0$i 00")))
        assertEquals(2, log.devices().size)
        assertEquals(5, log.totalFrames)
    }
}

class CaptureDriverTest {
    private fun capture(dongle: SimulatedEzspDongle, channels: List<Int>, runMs: Long): List<CapturedFrame> = runBlocking {
        val heard = ArrayList<CapturedFrame>()
        val driver = DongleDetector.connect(dongle, ezspFirst = true) as FrameCapture
        kotlinx.coroutines.withTimeoutOrNull(runMs) {
            driver.capture(channels, 100, onChannel = {}, onFrame = { heard += it })
        }
        heard
    }

    @Test fun `captures frames per channel and strips the filler bytes`() {
        val frames = capture(SimulatedEzspDongle(), listOf(15, 20), 800)
        val first = frames.first { it.channel == 15 }
        assertArrayEquals(SimulatedEzspDongle.FRAME_ZIGBEE_BEACON, first.data)
        assertEquals(200, first.lqi)
        assertEquals(-58, first.rssi)
        assertTrue(frames.any { it.channel == 20 && it.data.contentEquals(SimulatedEzspDongle.FRAME_ENCRYPTED) })
    }

    @Test fun `capture never transmits and restarts the stick when stopped`() {
        val dongle = SimulatedEzspDongle()
        capture(dongle, listOf(15, 20), 500)
        // Version, mfglib start, set channel and end. In particular no send packet (0x89) or tone.
        assertEquals(setOf(0x00, 0x83, 0x8A, 0x84), dongle.received.toSet())
        assertEquals(2, dongle.resets)
    }

    @Test fun `staying on one channel doesn't keep retuning`() {
        val dongle = SimulatedEzspDongle()
        capture(dongle, listOf(15), 500)
        assertEquals(1, dongle.received.count { it == 0x8A })
    }

    @Test fun `works across EZSP versions`() {
        for (version in listOf(8, 13, 14, 19)) {
            val frames = capture(SimulatedEzspDongle(ezspVersion = version), listOf(15), 300)
            assertTrue("v$version", frames.isNotEmpty())
        }
    }

    @Test fun `firmware without capture mode gets a clear message`() {
        val failure = runCatching { capture(SimulatedEzspDongle(hasCaptureMode = false), listOf(15), 500) }.exceptionOrNull()
        assertTrue(failure is ZigbeeException)
        assertTrue("capture mode" in failure!!.message!!)
    }

    @Test fun `known EZSP sticks don't have their reset lines touched`() = runBlocking {
        var pinChanges = 0
        val dongle = object : SerialLink by SimulatedEzspDongle() {
            override fun setPins(dtr: Boolean, rts: Boolean) { pinChanges++ }
        }
        assertNotNull(DongleDetector.connect(dongle, ezspFirst = true))
        assertEquals(0, pinChanges)
    }

    @Test fun `Z-Stack firmware offers no capture`() = runBlocking {
        assertFalse(DongleDetector.connect(SimulatedZnpDongle()) is FrameCapture)
    }
}
