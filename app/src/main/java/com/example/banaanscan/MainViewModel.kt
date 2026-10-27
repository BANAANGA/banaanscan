package com.example.banaanscan

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.banaanscan.data.DeviceIndex
import com.example.banaanscan.data.OuiDatabase
import com.example.banaanscan.scan.BluetoothScanner
import com.example.banaanscan.scan.LanScanner
import com.example.banaanscan.scan.WifiScanner
import com.example.banaanscan.ui.ThemeMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    val wifi = WifiScanner(app)
    val bt = BluetoothScanner(app)
    val lan = LanScanner(app)
    val index = DeviceIndex(app)

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.DARK)
    )

    val ouiStatus = MutableStateFlow("Loading vendor database…")
    val ouiBusy = MutableStateFlow(false)
    private var lanJob: Job? = null

    init {
        viewModelScope.launch {
            index.load()
            val n = OuiDatabase.init(app)
            if (n == 0) updateOui() else ouiStatus.value = "%,d vendor prefixes loaded".format(n)
        }
        viewModelScope.launch { wifi.results.collect { index.recordWifi(it) } }
        viewModelScope.launch { bt.devices.sample(2000).collect { index.recordBt(it.values) } }
        viewModelScope.launch { lan.devices.collect { index.recordLan(it, lan.network.value?.ssid) } }
    }

    fun updateOui() {
        if (ouiBusy.value) return
        viewModelScope.launch {
            ouiBusy.value = true
            ouiStatus.value = "Downloading vendor database…"
            OuiDatabase.download(app).fold(
                onSuccess = { ouiStatus.value = "%,d vendor prefixes loaded".format(it) },
                onFailure = {
                    ouiStatus.value = "Vendor database download failed: ${it.message}. " +
                        if (OuiDatabase.size > 0) "Using ${OuiDatabase.size} cached prefixes." else "Vendor lookup unavailable."
                },
            )
            ouiBusy.value = false
        }
    }

    /** Cycles Dark → Light → Follow system and remembers the choice. */
    fun cycleTheme() {
        val next = ThemeMode.entries[(themeMode.value.ordinal + 1) % ThemeMode.entries.size]
        themeMode.value = next
        prefs.edit().putString("theme", next.name).apply()
    }

    fun scanLan() {
        if (lanJob?.isActive == true) return
        lanJob = viewModelScope.launch { lan.scan() }
    }

    fun stopLan() {
        lanJob?.cancel()
    }

    override fun onCleared() {
        bt.stop()
        wifi.stop()
    }
}
