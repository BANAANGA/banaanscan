package com.example.banaanscan.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.classify.DeviceClassifier
import com.example.banaanscan.data.BtDevice

@Composable
fun BluetoothScreen(vm: MainViewModel) {
    val devices by vm.bt.devices.collectAsStateWithLifecycle()
    val scanning by vm.bt.scanning.collectAsStateWithLifecycle()
    val error by vm.bt.error.collectAsStateWithLifecycle()
    var namedOnly by rememberSaveable { mutableStateOf(false) }
    var selected by remember { mutableStateOf<BtDevice?>(null) }
    var enabled by remember { mutableStateOf(vm.bt.isEnabled) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = vm.bt.isEnabled
        if (enabled) vm.bt.start()
    }

    // Scanning drains the battery; stop when the user leaves this tab.
    DisposableEffect(Unit) { onDispose { vm.bt.stop() } }

    val shown = devices.values
        .filter { !namedOnly || it.name != null }
        .sortedByDescending { it.rssi ?: -200 }

    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { if (scanning) vm.bt.stop() else vm.bt.start() }) {
                Text(if (scanning) "Stop" else "Scan")
            }
            FilterChip(selected = namedOnly, onClick = { namedOnly = !namedOnly }, label = { Text("Named only") })
            TextButton(onClick = { vm.bt.clear() }) { Text("Clear") }
        }
        if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
        if (!vm.bt.isAvailable) {
            Text("This device has no Bluetooth.", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error)
        } else if (!enabled) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Bluetooth is off.", Modifier.weight(1f), color = MaterialTheme.colorScheme.error)
                TextButton(onClick = {
                    runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) }
                }) { Text("Turn on") }
            }
        }
        error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error) }
        Text("${shown.size} devices", Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelLarge)
        HorizontalDivider()
        LazyColumn {
            items(shown, key = { it.address }) { d -> BtRow(d) { selected = d } }
        }
    }

    selected?.let { sel ->
        val d = devices[sel.address] ?: sel
        DetailDialog(d.name ?: "Unnamed device", onDismiss = { selected = null }) {
            InfoRow("Address", d.address)
            InfoRow("Vendor", vendorText(d))
            InfoRow("Signal", d.rssi?.let { "$it dBm" })
            InfoRow("TX power", d.txPower?.let { "$it dBm" })
            InfoRow("Transport", listOfNotNull("BLE".takeIf { d.le }, "Classic".takeIf { d.classic }).joinToString(" + "))
            InfoRow("Paired", if (d.bonded) "Yes" else null)
            InfoRow("Connectable", d.connectable?.toString())
            InfoRow("Class", d.deviceClass?.let { "0x%04X (major 0x%04X)".format(it, d.majorClass ?: 0) })
            if (d.manufacturerData.isNotEmpty()) {
                SectionTitle("Manufacturer data")
                d.manufacturerData.forEach { (id, hex) ->
                    InfoRow(DeviceClassifier.BLE_COMPANIES[id] ?: "0x%04X".format(id), hex.take(48))
                }
            }
            if (d.serviceUuids.isNotEmpty()) {
                SectionTitle("Services")
                d.serviceUuids.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            GuessSection(d.guess)
            InfoRow("Last seen", ageText(d.lastSeen))
        }
    }
}

private fun vendorText(d: BtDevice): String = when {
    d.vendorReliable && d.vendor != null -> d.vendor
    d.manufacturerData.isNotEmpty() ->
        d.manufacturerData.keys.joinToString { DeviceClassifier.BLE_COMPANIES[it] ?: "company 0x%04X".format(it) } + " (from BLE data)"
    d.randomAddress || (d.le && !d.classic) -> "Random / private BLE address"
    else -> d.vendor ?: "Unknown"
}

@Composable
private fun BtRow(d: BtDevice, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(d.guess.type.icon(), contentDescription = d.guess.type.label) },
        headlineContent = { Text(d.name ?: "Unnamed device") },
        supportingContent = {
            Column {
                Text("${d.address} · ${vendorText(d)}", style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    Tag("${d.guess.type.label} ${d.guess.confidence}%")
                    if (d.le) Tag("BLE")
                    if (d.classic) Tag("Classic")
                    if (d.bonded) Tag("Paired")
                }
            }
        },
        trailingContent = { RssiText(d.rssi) },
    )
}
