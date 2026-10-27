package com.example.banaanscan.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.data.WifiNetwork

@Composable
fun WifiScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val results by vm.wifi.results.collectAsStateWithLifecycle()
    val status by vm.wifi.status.collectAsStateWithLifecycle()
    var band by rememberSaveable { mutableStateOf("All") }
    var selected by remember { mutableStateOf<WifiNetwork?>(null) }
    val locationOn = remember {
        context.getSystemService(android.location.LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
    }

    LaunchedEffect(Unit) { vm.wifi.scan() }

    val shown = results.filter { band == "All" || it.band == band }
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "2.4 GHz", "5 GHz", "6 GHz").forEach { b ->
                FilterChip(selected = band == b, onClick = { band = b }, label = { Text(b) })
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("${shown.size} access points", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge)
            Button(onClick = { vm.wifi.scan() }) {
                Icon(Icons.Filled.Refresh, null)
                Text("Scan", Modifier.padding(start = 4.dp))
            }
        }
        if (!locationOn) {
            Row(Modifier.padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("Location services are off – Android hides Wi-Fi results.", Modifier.weight(1f),
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }) { Text("Settings") }
            }
        }
        status?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
        ChannelUsage(shown)
        HorizontalDivider()
        LazyColumn {
            items(shown, key = { it.bssid + it.frequency }) { n ->
                WifiRow(n) { selected = n }
            }
        }
    }

    selected?.let { n ->
        DetailDialog(n.ssid.ifBlank { "Hidden network" }, onDismiss = { selected = null }) {
            InfoRow("BSSID", n.bssid)
            InfoRow("Vendor", n.vendor ?: if (n.randomizedBssid) "Locally administered (virtual / hotspot)" else "Unknown")
            InfoRow("Signal", "${n.rssi} dBm")
            InfoRow("Frequency", "${n.frequency} MHz (${n.band}, channel ${n.channel})")
            InfoRow("Width", n.channelWidthMhz?.let { "$it MHz" })
            InfoRow("Standard", n.standard)
            InfoRow("Security", n.security)
            InfoRow("Connected", if (n.isConnected) "Yes" else null)
            GuessSection(n.guess)
        }
    }
}

@Composable
private fun ChannelUsage(networks: List<WifiNetwork>) {
    if (networks.isEmpty()) return
    val byChannel = networks.groupBy { it.band to it.channel }.toSortedMap(compareBy({ it.first }, { it.second }))
    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("Channels: ", style = MaterialTheme.typography.labelSmall)
        byChannel.forEach { (key, list) -> Tag("${key.second}×${list.size}") }
    }
}

@Composable
private fun WifiRow(n: WifiNetwork, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Icon(if (n.security == "Open") Icons.Filled.LockOpen else Icons.Filled.Lock, contentDescription = n.security)
        },
        headlineContent = {
            Text(
                n.ssid.ifBlank { "‹hidden›" } + if (n.isConnected) "  ● connected" else "",
                fontWeight = if (n.isConnected) FontWeight.Bold else FontWeight.Normal,
            )
        },
        supportingContent = {
            Column {
                Text("${n.bssid} · ${n.vendor ?: "unknown vendor"}", style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    Tag(n.band)
                    Tag("ch ${n.channel}")
                    Tag(n.security)
                    n.standard?.let { Tag(it) }
                    if (n.guess.type != com.example.banaanscan.data.DeviceType.ROUTER) Tag(n.guess.type.label)
                }
            }
        },
        trailingContent = { RssiText(n.rssi) },
    )
}
