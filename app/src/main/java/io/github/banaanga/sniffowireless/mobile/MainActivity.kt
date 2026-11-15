package io.github.banaanga.sniffowireless.mobile

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.DeviceHub
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.banaanga.sniffowireless.mobile.ui.SniffoTheme
import io.github.banaanga.sniffowireless.mobile.ui.BluetoothScreen
import io.github.banaanga.sniffowireless.mobile.ui.IndexScreen
import io.github.banaanga.sniffowireless.mobile.ui.LookupScreen
import io.github.banaanga.sniffowireless.mobile.ui.NetworkScreen
import io.github.banaanga.sniffowireless.mobile.ui.PermissionBanner
import io.github.banaanga.sniffowireless.mobile.ui.WifiScreen
import io.github.banaanga.sniffowireless.mobile.ui.SensorScreen
import io.github.banaanga.sniffowireless.mobile.ui.Sniffo
import androidx.compose.material.icons.filled.Sensors
import io.github.banaanga.sniffowireless.mobile.ui.ZigbeeScreen

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
            val mode by vm.themeMode.collectAsStateWithLifecycle()
            val dark = mode.isDark()
            // Keep status/navigation bar icons readable for the chosen theme, not the system one.
            DisposableEffect(dark) {
                val style = if (dark) SystemBarStyle.dark(Color.TRANSPARENT)
                else SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT)
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

private enum class Tab(val label: String, val icon: ImageVector) {
    WIFI("Wi-Fi", Icons.Filled.Wifi),
    BLUETOOTH("Bluetooth", Icons.Filled.Bluetooth),
    ZIGBEE("Zigbee", Icons.Filled.Hub),
    NETWORK("Network", Icons.Filled.DeviceHub),
    INDEX("Index", Icons.Filled.History),
    LOOKUP("Lookup", Icons.Filled.Search),
    /** Not in the bottom bar: opened from the top bar, where it also shows whether sensor mode runs. */
    SENSOR("Sensor", Icons.Filled.Sensors),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SniffoApp(vm: MainViewModel, themeMode: ThemeMode, openSensor: androidx.compose.runtime.MutableState<Boolean>) {
    val context = LocalContext.current
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
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("sniffowireless-mobile", style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(tab.label, style = MaterialTheme.typography.titleMedium)
                    }
                },
                actions = {
                    IconButton(onClick = { tab = if (tab == Tab.SENSOR) Tab.NETWORK else Tab.SENSOR }) {
                        Icon(Icons.Filled.Sensors, contentDescription = if (sensorOn) "Sensor mode: on" else "Sensor mode",
                            tint = if (sensorOn) Sniffo.good else androidx.compose.material3.LocalContentColor.current)
                    }
                    IconButton(onClick = vm::cycleTheme) {
                        Icon(
                            when (themeMode) {
                                ThemeMode.DARK -> Icons.Filled.DarkMode
                                ThemeMode.LIGHT -> Icons.Filled.LightMode
                                ThemeMode.SYSTEM -> Icons.Filled.BrightnessAuto
                            },
                            contentDescription = "Theme: ${themeMode.label}",
                        )
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.filter { it != Tab.SENSOR }.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        // Six tabs leave little room: keep labels on one line.
                        label = {
                            // Phones with wider system fonts (Samsung) cut "Bluetooth" off at the default size.
                            Text(t.label, maxLines = 1, softWrap = false, fontSize = 10.sp, letterSpacing = 0.sp)
                        },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            if (!granted) PermissionBanner { launcher.launch(Permissions.required) }
            when (tab) {
                Tab.WIFI -> WifiScreen(vm)
                Tab.BLUETOOTH -> BluetoothScreen(vm)
                Tab.ZIGBEE -> ZigbeeScreen(vm)
                Tab.NETWORK -> NetworkScreen(vm)
                Tab.INDEX -> IndexScreen(vm)
                Tab.LOOKUP -> LookupScreen(vm)
                Tab.SENSOR -> SensorScreen(vm)
            }
        }
    }
}
