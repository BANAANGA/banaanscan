package io.github.banaanga.sniffowireless.mobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.banaanga.sniffowireless.mobile.sensor.hub
import io.github.banaanga.sniffowireless.mobile.data.OuiDatabase
import io.github.banaanga.sniffowireless.mobile.ui.ThemeMode
import io.github.banaanga.sniffowireless.mobile.wifimon.ChannelPlan
import io.github.banaanga.sniffowireless.mobile.zigbee.UsbDongle
import io.github.banaanga.sniffowireless.mobile.zigbee.ZigbeeDebug
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.sample
import kotlinx.coroutines.launch

class MainViewModel(private val app: Application) : AndroidViewModel(app) {
    /** Shared with the sensor service, so sensor mode and the screens see the same scanners. */
    val hub = app.hub
    val wifi = hub.wifi
    val bt = hub.bt
    val lan = hub.lan
    val lora = hub.lora
    val zigbee = hub.zigbee
    val wifiMonitor = hub.wifiMonitor
    val btUsb = hub.btUsb
    val index = hub.index

    private val prefs = app.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
    val themeMode = MutableStateFlow(
        runCatching { ThemeMode.valueOf(prefs.getString("theme", null) ?: "") }.getOrDefault(ThemeMode.DARK)
    )

    val ouiStatus = MutableStateFlow("Loading vendor database…")
    val ouiBusy = MutableStateFlow(false)
    private var lanJob: Job? = null
    private var zigbeeJob: Job? = null
    private var wifiMonitorJob: Job? = null
    private var btUsbJob: Job? = null
    private var simulatedKind: String? = null

    init {
        viewModelScope.launch {
            index.load()
            val n = OuiDatabase.init(app)
            if (n == 0) updateOui() else ouiStatus.value = "%,d vendor prefixes loaded".format(n)
        }
        viewModelScope.launch { wifi.results.collect { index.recordWifi(it) } }
        viewModelScope.launch { bt.devices.sample(2000).collect { index.recordBt(it.values) } }
        viewModelScope.launch { btUsb.devices.sample(2000).collect { index.recordBt(it.values) } }
        viewModelScope.launch { lan.devices.collect { index.recordLan(it, lan.network.value?.ssid) } }
        viewModelScope.launch { zigbee.networks.collect { index.recordZigbee(it) } }
        viewModelScope.launch { zigbee.sniffedDevices.sample(2000).collect { index.recordSniffed(it) } }
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

    fun scanZigbee(dongle: UsbDongle) {
        if (zigbeeJob?.isActive == true) return
        val simulated = if (dongle.device == null) simulatedKind?.let(ZigbeeDebug::simulatedLink) else null
        zigbeeJob = viewModelScope.launch {
            if (simulated != null) zigbee.scanSimulated(simulated) else zigbee.scan(dongle)
        }
    }

    fun sniffZigbee(dongle: UsbDongle, channel: Int?) {
        if (zigbeeJob?.isActive == true) return
        val simulated = if (dongle.device == null) simulatedKind?.let(ZigbeeDebug::simulatedLink) else null
        zigbeeJob = viewModelScope.launch {
            if (simulated != null) zigbee.sniffSimulated(simulated, channel) else zigbee.sniff(dongle, channel)
        }
    }

    fun startWifiMonitor(plan: ChannelPlan) {
        if (wifiMonitorJob?.isActive == true) return
        wifiMonitorJob = viewModelScope.launch { wifiMonitor.run(plan) }
    }

    fun stopWifiMonitor() {
        wifiMonitorJob?.cancel()
    }

    fun startBtUsb(passive: Boolean) {
        if (btUsbJob?.isActive == true) return
        btUsbJob = viewModelScope.launch { btUsb.run(passive) }
    }

    fun stopBtUsb() {
        btUsbJob?.cancel()
    }

    fun stopZigbee() {
        zigbeeJob?.cancel()
    }

    /** Debug builds only: lists a simulated dongle, because emulators have no USB host. */
    /** Debug builds only: a simulated LoRa board, because emulators have no USB host. */
    fun addSimulatedLoraBoard() {
        lora.simulator = io.github.banaanga.sniffowireless.mobile.lora.LoraDebug.simulatedBoard()
    }

    fun addSimulatedZigbeeDongle(kind: String) {
        if (simulatedKind != null || ZigbeeDebug.simulatedLink(kind) == null) return
        simulatedKind = kind
        zigbee.addSimulatedDongle("Simulated $kind dongle")
    }

    override fun onCleared() {
        // The scanners belong to the app (the sensor service may still use them); just let go of this screen's scans.
        bt.stop()
        if (!hub.sending) wifi.stop()
    }
}
