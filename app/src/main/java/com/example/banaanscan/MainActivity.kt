package com.example.banaanscan

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.banaanscan.ui.BanaanScanTheme
import com.example.banaanscan.ui.BluetoothScreen
import com.example.banaanscan.ui.IndexScreen
import com.example.banaanscan.ui.LookupScreen
import com.example.banaanscan.ui.NetworkScreen
import com.example.banaanscan.ui.PermissionBanner
import com.example.banaanscan.ui.WifiScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            BanaanScanTheme {
                BanaanScanApp()
            }
        }
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
    NETWORK("Network", Icons.Filled.DeviceHub),
    INDEX("Index", Icons.Filled.History),
    LOOKUP("Lookup", Icons.Filled.Search),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BanaanScanApp(vm: MainViewModel = viewModel()) {
    val context = LocalContext.current
    var tab by rememberSaveable { mutableStateOf(Tab.NETWORK) }
    var granted by remember { mutableStateOf(Permissions.allGranted(context)) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = Permissions.allGranted(context)
    }
    LaunchedEffect(Unit) {
        if (!granted) launcher.launch(Permissions.required)
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("BanaanScan · ${tab.label}") }) },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = null) },
                        label = { Text(t.label) },
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
                Tab.NETWORK -> NetworkScreen(vm)
                Tab.INDEX -> IndexScreen(vm)
                Tab.LOOKUP -> LookupScreen(vm)
            }
        }
    }
}
