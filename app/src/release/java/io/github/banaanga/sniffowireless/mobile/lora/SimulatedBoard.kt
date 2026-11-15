package io.github.banaanga.sniffowireless.mobile.lora

import io.github.banaanga.sniffowireless.mobile.core.LineLink

/** Release builds ship no simulated hardware; see the debug source set. */
object LoraDebug {
    fun simulatedBoard(): (() -> LineLink)? = null
}
