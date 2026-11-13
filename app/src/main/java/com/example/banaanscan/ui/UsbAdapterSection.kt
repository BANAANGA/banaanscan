package com.example.banaanscan.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.wifimon.ChannelPlan

/** Wi-Fi monitor mode through an MT7921U USB adapter, driven by BanaanScan's own driver. */
@Composable
fun UsbAdapterSection(vm: MainViewModel) {
    val mon = vm.wifiMonitor
    val adapter by mon.adapter.collectAsStateWithLifecycle()
    val firmware by mon.firmwarePresent.collectAsStateWithLifecycle()
    val running by mon.running.collectAsStateWithLifecycle()
    val status by mon.status.collectAsStateWithLifecycle()
    val error by mon.error.collectAsStateWithLifecycle()
    val current by mon.currentFreq.collectAsStateWithLifecycle()
    val networks by mon.networks.collectAsStateWithLifecycle()
    val clients by mon.clients.collectAsStateWithLifecycle()
    val channels by mon.channels.collectAsStateWithLifecycle()
    val frames by mon.frames.collectAsStateWithLifecycle()
    var plan by rememberSaveable { mutableStateOf(ChannelPlan.BAND_24) }

    LaunchedEffect(Unit) { mon.refresh() }

    LazyColumn {
        item(key = "card") {
            Card(Modifier.fillMaxWidth().padding(12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(if (adapter != null) "MediaTek MT7921U connected" else "Plug in an MT7921U adapter", fontWeight = FontWeight.Bold)
                            Text(
                                if (adapter == null) "Connect it with a USB-C cable or OTG adapter. Monitor mode runs on BanaanScan's own driver, without root."
                                else if (!firmware) "Firmware missing: run tools/mt7921u/push-firmware.sh on your computer once."
                                else "Listens only: nothing is transmitted.",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        when {
                            running -> OutlinedButton(onClick = vm::stopWifiMonitor) { Text("Stop") }
                            adapter != null -> Button(onClick = { vm.startWifiMonitor(plan) }, enabled = firmware) { Text("Listen") }
                        }
                    }
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        ChannelPlan.entries.forEach { p ->
                            FilterChip(selected = plan == p, enabled = !running, onClick = { plan = p }, label = { Text(p.label) })
                        }
                    }
                }
            }
        }
        item(key = "status") {
            Column(Modifier.padding(horizontal = 12.dp)) {
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))
                status?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (frames > 0) {
                    Text("$frames frames · ${networks.size} networks · ${clients.size} devices", style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.padding(top = 4.dp))
                    FlowRow(Modifier.padding(vertical = 4.dp)) {
                        channels.forEach { c ->
                            Tag((if (c.freqMhz == current) "▶ " else "") + "${com.example.banaanscan.mt7921u.Channel.fromFreq(c.freqMhz).number}: ${c.frames}")
                        }
                    }
                }
            }
        }
        if (networks.isNotEmpty()) {
            item(key = "nets-title") { SectionTitle("Networks") }
        }
        items(networks, key = { "n:" + it.bssid }) { n ->
            ListItem(
                leadingContent = { Icon(Icons.Filled.Router, contentDescription = null) },
                headlineContent = { Text(n.ssid?.ifEmpty { "‹hidden›" } ?: "‹unknown›") },
                supportingContent = {
                    Column {
                        Text(listOfNotNull(n.bssid, n.vendor).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        FlowRow {
                            Tag("ch ${com.example.banaanscan.mt7921u.Channel.fromFreq(n.freqMhz).number}")
                            Tag("${n.beacons} beacons")
                            if (n.clients > 0) Tag("${n.clients} devices")
                        }
                    }
                },
                trailingContent = { RssiText(n.rssi) },
            )
        }
        if (clients.isNotEmpty()) {
            item(key = "clients-title") {
                Column {
                    HorizontalDivider(Modifier.padding(top = 8.dp))
                    SectionTitle("Devices")
                }
            }
        }
        items(clients, key = { "c:" + it.mac }) { c ->
            ListItem(
                leadingContent = { Icon(Icons.Filled.Smartphone, contentDescription = null) },
                headlineContent = { Text(c.mac) },
                supportingContent = {
                    Column {
                        Text(
                            listOfNotNull(c.vendor, if (c.randomized) "private address" else null,
                                c.bssid?.let { b -> networks.firstOrNull { it.bssid == b }?.ssid?.let { "on $it" } ?: "on $b" })
                                .joinToString(" · ").ifEmpty { "not connected" },
                            style = MaterialTheme.typography.bodySmall,
                        )
                        if (c.probes.isNotEmpty()) Text("Looking for: " + c.probes.joinToString(", "), style = MaterialTheme.typography.labelSmall)
                    }
                },
                trailingContent = {
                    Column(horizontalAlignment = Alignment.End) {
                        RssiText(c.rssi)
                        Text("${c.frames} frames", style = MaterialTheme.typography.labelSmall, modifier = Modifier.width(72.dp))
                    }
                },
            )
        }
    }
}
