package io.github.banaanga.sniffowireless.mobile.zigbee

/** Release builds ship no simulated hardware; see the debug source set. */
object ZigbeeDebug {
    fun simulatedLink(kind: String): SerialLink? = null
}
