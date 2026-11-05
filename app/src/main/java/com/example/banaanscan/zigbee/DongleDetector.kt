package com.example.banaanscan.zigbee

import kotlinx.coroutines.delay

/**
 * Works out what firmware a dongle runs by asking. The USB chip can't tell us: the same CP210x
 * sits on both Z-Stack and EZSP sticks. Each probe is harmless to the other firmware, which
 * ignores bytes outside its own framing.
 */
object DongleDetector {
    private const val PIN_DELAY_MS = 150L
    private const val PING_TIMEOUT_MS = 500L
    private const val PING_ATTEMPTS = 3
    private const val ASH_RESET_TIMEOUT_MS = 3_000L
    private const val ASH_RESET_ATTEMPTS = 2
    private const val SKIP_BYTE_DELAY_MS = 2_500L
    private const val BOOTLOADER_SKIP_BYTE = 0xEF

    /**
     * [ezspFirst] is for sticks known to ship with EZSP firmware, such as the Sonoff ZBDongle-E. Its
     * DTR and RTS lines drive the chip's reset and boot pins, so they aren't touched unless it stays silent.
     */
    suspend fun connect(link: SerialLink, ezspFirst: Boolean = false, onProgress: (String) -> Unit = {}): ZigbeeDriver? {
        if (ezspFirst) {
            onProgress("Looking for a Silicon Labs coordinator…")
            EzspDriver.connect(link, ASH_RESET_TIMEOUT_MS)?.let { return it }
        }
        onProgress("Looking for a Z-Stack coordinator…")
        // This pin sequence leaves CC2652 sticks running: other patterns start their bootloader.
        for (rts in listOf(false, true, false)) {
            link.setPins(dtr = false, rts = rts)
            delay(PIN_DELAY_MS)
        }
        repeat(PING_ATTEMPTS) { ZnpDriver.connect(link, PING_TIMEOUT_MS)?.let { return it } }

        onProgress("Looking for a Silicon Labs coordinator…")
        // RTS must be high for sticks with hardware flow control to send anything.
        link.setPins(dtr = true, rts = true)
        delay(PIN_DELAY_MS)
        repeat(ASH_RESET_ATTEMPTS) { EzspDriver.connect(link, ASH_RESET_TIMEOUT_MS)?.let { return it } }

        onProgress("Trying to wake a Z-Stack bootloader…")
        // CC253x sticks wait in their bootloader until they receive this byte.
        link.setPins(dtr = false, rts = false)
        link.write(ByteArray(256) { BOOTLOADER_SKIP_BYTE.toByte() })
        delay(SKIP_BYTE_DELAY_MS)
        repeat(PING_ATTEMPTS) { ZnpDriver.connect(link, PING_TIMEOUT_MS * 2)?.let { return it } }
        return null
    }
}
