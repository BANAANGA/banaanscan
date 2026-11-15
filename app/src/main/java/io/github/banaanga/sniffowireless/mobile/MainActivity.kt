package io.github.banaanga.sniffowireless.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color as AndroidColor
import androidx.compose.ui.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.banaanga.sniffowireless.mobile.ui.Dot
import io.github.banaanga.sniffowireless.mobile.ui.PageHead
import io.github.banaanga.sniffowireless.mobile.ui.StatusLine
import io.github.banaanga.sniffowireless.mobile.ui.Tone
import androidx.activity.SystemBarStyle
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.IconButton
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.ui.ThemeMode
import io.github.banaanga.sniffowireless.mobile.ui.isDark
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.banaanga.sniffowireless.mobile.ui.SniffoTheme
import io.github.banaanga.sniffowireless.mobile.ui.BluetoothScreen
import io.github.banaanga.sniffowireless.mobile.ui.IndexScreen
import io.github.banaanga.sniffowireless.mobile.ui.NetworkScreen
import io.github.banaanga.sniffowireless.mobile.ui.PermissionBanner
import io.github.banaanga.sniffowireless.mobile.ui.WifiScreen
import io.github.banaanga.sniffowireless.mobile.ui.SensorScreen
import io.github.banaanga.sniffowireless.mobile.ui.Sniffo
import io.github.banaanga.sniffowireless.mobile.ui.ZigbeeScreen
import io.github.banaanga.sniffowireless.mobile.ui.LoraScreen

class MainActivity : ComponentActivity() {
    /** Set when opened from the sensor notification: show the Sensor screen. */
    private val openSensor = androidx.compose.runtime.mutableStateOf(false)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_SENSOR, false)) openSensor.value = true
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra(EXTRA_SENSOR, false)) openSensor.value = true
        setContent {
            val vm: MainViewModel = viewModel()
            intent.getStringExtra("simulate_zigbee")?.let(vm::addSimulatedZigbeeDongle)
            if (intent.getBooleanExtra("simulate_lora", false)) vm.addSimulatedLoraBoard()
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val dark = mode.isDark()
            // Keep status/navigation bar icons readable for the chosen theme, not the system one.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(AndroidColor.TRANSPARENT)
                else SystemBarStyle.light(AndroidColor.TRANSPARENT, AndroidColor.TRANSPARENT)
                enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
                onDispose { }
            }
            SniffoTheme(dark) {
                SniffoApp(vm, mode, openSensor)
            }
        }
    }

    companion object {
        const val EXTRA_SENSOR = "open_sensor"
    }
}

object Permissions {
    val required: Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= 31) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.NEARBY_WIFI_DEVICES)
    }.toTypedArray()

    fun allGranted(context: Context) = required.all {
        ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
    }
}

private enum class Tab(val label: String, val title: String, val sub: String) {
    WIFI("Wi-Fi", "Wi-Fi", "Access points around you, and with a USB adapter the devices talking to them"),
    BLUETOOTH("Bluetooth", "Bluetooth", "BLE and classic devices nearby"),
    ZIGBEE("Zigbee", "Zigbee & 802.15.4", "Networks and radios, through a USB stick"),
    LORA("LoRa", "LoRa", "Meshtastic, MeshCore, LoRaWAN and LoRa APRS, through a Heltec V3 board"),
    NETWORK("LAN", "LAN", "Hosts on the network this phone is connected to"),
    INDEX("Devices", "Devices", "Everything seen so far, remembered on this phone"),
    SENSOR("Sensor", "Sensor", "Send what this phone hears to a sniffowireless server"),
}

@Composable
private fun SniffoApp(vm: MainViewModel, themeMode: ThemeMode, openSensor: androidx.compose.runtime.MutableState<Boolean>) {
    val context = LocalContext.current
    val p = Sniffo.c
    var tab by rememberSaveable { mutableStateOf(if (vm.hub.sending) Tab.SENSOR else Tab.NETWORK) }
    val sensorOn by vm.hub.sensorRunning.collectAsStateWithLifecycle()
    LaunchedEffect(openSensor.value) {
        if (openSensor.value) {
            tab = Tab.SENSOR
            openSensor.value = false
        }
    }
    var granted by remember { mutableStateOf(Permissions.allGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Permissions.allGranted(context)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Permissions.required)
    }

    Scaffold(
        containerColor = p.bg,
        topBar = {
            // sniffowireless's narrow-screen header: the brand, then the pages as a row of links.
            Column(Modifier.background(p.bg).statusBarsPadding()) {
                Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(14.dp).clip(CircleShape).background(p.text.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(p.text))
                    }
                    Text("sniffowireless", Modifier.padding(start = 8.dp), style = MaterialTheme.typography.titleMedium)
                    Text("-mobile", style = MaterialTheme.typography.titleMedium, color = p.muted, fontWeight = FontWeight.Normal)
                    Spacer(Modifier.weight(1f))
                    if (sensorOn) StatusLine("Sensor on", Tone.ON, Modifier.clickable { tab = Tab.SENSOR }.padding(horizontal = 8.dp, vertical = 6.dp))
                    IconButton(onClick = vm::cycleTheme) {
                        Icon(
                            when (themeMode) {
                                ThemeMode.DARK -> Icons.Filled.DarkMode
                                ThemeMode.LIGHT -> Icons.Filled.LightMode
                                ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                            },
                            contentDescription = "Theme: ${themeMode.label}",
                            tint = p.muted,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 10.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    Tab.entries.forEach { t ->
                        val on = t == tab
                        Row(
                            Modifier.clip(RoundedCornerShape(6.dp)).background(if (on) p.panel2 else Color.Transparent)
                                .clickable { tab = t }.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (t == Tab.SENSOR && sensorOn) { Dot(p.good); Spacer(Modifier.width(6.dp)) }
                            Text(t.label, style = MaterialTheme.typography.bodyMedium, color = if (on) p.text else p.muted, maxLines = 1)
                        }
                    }
                }
                HorizontalDivider(color = p.border)
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            PageHead(tab.title, tab.sub)
            if (!granted) PermissionBanner { launcher.launch(Permissions.required) }
            when (tab) {
                Tab.WIFI -> WifiScreen(vm)
                Tab.BLUETOOTH -> BluetoothScreen(vm)
                Tab.ZIGBEE -> ZigbeeScreen(vm)
                Tab.LORA -> LoraScreen(vm)
                Tab.NETWORK -> NetworkScreen(vm)
                Tab.INDEX -> IndexScreen(vm)
                Tab.SENSOR -> SensorScreen(vm)
            }
        }
    }
}
