package com.example.banaanscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Hub
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.zigbee.ChannelEnergy
import com.example.banaanscan.zigbee.UsbDongle
import com.example.banaanscan.zigbee.ZigbeeNetwork

@Composable
fun ZigbeeScreen(vm: MainViewModel) {
    val dongles by vm.zigbee.dongles.collectAsStateWithLifecycle()
    val networks by vm.zigbee.networks.collectAsStateWithLifecycle()
    val energy by vm.zigbee.energy.collectAsStateWithLifecycle()
    val info by vm.zigbee.dongleInfo.collectAsStateWithLifecycle()
    val running by vm.zigbee.running.collectAsStateWithLifecycle()
    val status by vm.zigbee.status.collectAsStateWithLifecycle()
    val error by vm.zigbee.error.collectAsStateWithLifecycle()
    var selectedKey by remember { mutableStateOf<String?>(null) }
    val sniffed by vm.zigbee.sniffedDevices.collectAsStateWithLifecycle()
    val activity by vm.zigbee.channelActivity.collectAsStateWithLifecycle()
    val currentChannel by vm.zigbee.currentChannel.collectAsStateWithLifecycle()
    val framesHeard by vm.zigbee.framesHeard.collectAsStateWithLifecycle()
    val framesUndecoded by vm.zigbee.framesUndecoded.collectAsStateWithLifecycle()
    var sniffer by rememberSaveable { mutableStateOf(false) }
    var sniffChannel by rememberSaveable { mutableStateOf<Int?>(null) }
    var selectedDevice by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.zigbee.refresh() }

    LazyColumn {
        item(key = "mode") {
            Row(Modifier.padding(start = 12.dp, top = 4.dp)) {
                FilterChip(selected = !sniffer, enabled = !running, onClick = { sniffer = false }, label = { Text("Network scan") })
                FilterChip(selected = sniffer, enabled = !running, onClick = { sniffer = true }, label = { Text("Sniffer") }, modifier = Modifier.padding(start = 6.dp))
            }
        }
        if (sniffer) {
            item(key = "sniffer-controls") { SnifferControls(sniffChannel, !running) { sniffChannel = it } }
        }
        item(key = "dongles") {
            if (dongles.isEmpty()) {
                NoDongleCard()
            } else {
                dongles.forEach { d ->
                    DongleCard(
                        d, running,
                        startLabel = if (sniffer) "Listen" else "Scan",
                        onScan = { if (sniffer) vm.sniffZigbee(d, sniffChannel) else vm.scanZigbee(d) },
                        onStop = vm::stopZigbee,
                    )
                }
            }
        }
        item(key = "status") {
            Column {
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(bottom = 4.dp))
                status?.let { Text(it, Modifier.padding(horizontal = 12.dp), style = MaterialTheme.typography.bodySmall) }
                error?.let { Text(it, Modifier.padding(horizontal = 12.dp), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                info?.let { i ->
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        InfoRow("Firmware", "${i.firmware} · ${i.family.label}")
                        i.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                }
            }
        }
        if (sniffer) {
            if (framesHeard > 0) {
                item(key = "activity") { SnifferActivity(activity, currentChannel, framesHeard, framesUndecoded) }
            }
            if (sniffed.isNotEmpty()) {
                item(key = "sniffed-divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            }
            items(sniffed, key = { "sniffed:" + it.key }) { d -> SniffedDeviceRow(d) { selectedDevice = d.key } }
        } else {
            if (energy.isNotEmpty()) {
                item(key = "energy") { EnergySection(energy) }
            }
            if (networks.isNotEmpty()) {
                item(key = "divider") { HorizontalDivider(Modifier.padding(top = 8.dp)) }
            }
            items(networks, key = { it.key }) { n -> NetworkRow(n) { selectedKey = n.key } }
        }
        if (!sniffer && info != null && !running) {
            item(key = "limits") {
                Text(
                    "A scan finds networks and the routers that answer it. Battery-powered sensors and the " +
                        "devices inside other networks don't respond, so they won't appear.",
                    Modifier.padding(12.dp),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    selectedDevice?.let { key ->
        sniffed.firstOrNull { it.key == key }?.let { d -> SniffedDeviceDetail(d) { selectedDevice = null } }
    }

    selectedKey?.let { key ->
        networks.firstOrNull { it.key == key }?.let { n -> NetworkDetail(n) { selectedKey = null } }
    }
}

@Composable
private fun NoDongleCard() {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text("Plug in a Zigbee USB dongle", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            Text(
                "Phones have no Zigbee radio, so scanning needs a coordinator stick connected through a USB-OTG " +
                    "adapter. Sticks running Z-Stack (Sonoff ZBDongle-P, CC2652) and EmberZNet (Sonoff ZBDongle-E, " +
                    "SkyConnect, ZBT-1) are supported.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
            Text(
                "Scanning is read-only and doesn't change anything stored on the stick. Close any other app using it first.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

@Composable
private fun DongleCard(dongle: UsbDongle, running: Boolean, startLabel: String, onScan: () -> Unit, onStop: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(dongle.name, fontWeight = FontWeight.Bold)
                Text(dongle.id, style = MaterialTheme.typography.labelSmall)
                dongle.blocker?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            }
            when {
                dongle.blocker != null -> Unit
                running -> OutlinedButton(onClick = onStop) { Text("Stop") }
                else -> Button(onClick = onScan) { Text(startLabel) }
            }
        }
    }
}

@Composable
private fun EnergySection(energy: List<ChannelEnergy>) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        SectionTitle("Channel interference")
        Text("Lower is better. Channels 15, 20 and 25 sit between the main Wi-Fi channels.",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        energy.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.channel.toString(), Modifier.width(28.dp), style = MaterialTheme.typography.labelSmall)
                LinearProgressIndicator(
                    progress = { ((e.rssi + 100) / 70f).coerceIn(0f, 1f) },
                    modifier = Modifier.weight(1f),
                    drawStopIndicator = {},
                )
                Text("${e.rssi} dBm", Modifier.padding(start = 8.dp).width(56.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

private fun panText(panId: Int) = "0x%04X".format(panId)

@Composable
private fun NetworkRow(n: ZigbeeNetwork, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Filled.Hub, contentDescription = null) },
        headlineContent = { Text("PAN ${panText(n.panId)}") },
        supportingContent = {
            Column {
                Text(listOfNotNull(n.extendedPanId, n.possibleVendor?.let { "$it?" }).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                FlowRow {
                    Tag("ch ${n.channel}")
                    if (n.permitJoining) Tag("Open for joining")
                    if (n.routers.size > 1) Tag("${n.routers.size} routers")
                }
            }
        },
        trailingContent = {
            if (n.rssi != null) RssiText(n.rssi)
            else n.lqi?.let { Text("LQI $it", style = MaterialTheme.typography.labelMedium) }
        },
    )
}

@Composable
private fun NetworkDetail(n: ZigbeeNetwork, onDismiss: () -> Unit) {
    DetailDialog("PAN ${panText(n.panId)}", onDismiss) {
        InfoRow("Extended ID", n.extendedPanId)
        InfoRow("Maybe made by", n.possibleVendor)
        InfoRow("Channel", n.channel.toString())
        InfoRow("Signal", n.rssi?.let { "$it dBm" })
        InfoRow("LQI", n.lqi?.let { "$it / 255" })
        InfoRow("Open to join", if (n.permitJoining) "Yes" else "No")
        InfoRow("Stack", when (n.stackProfile) {
            1 -> "Zigbee (2006/2007)"
            2 -> "Zigbee PRO"
            else -> "Profile ${n.stackProfile}"
        })
        InfoRow("Network update", n.updateId.toString())
        InfoRow("Room for", listOfNotNull("routers".takeIf { n.routerCapacity == true }, "devices".takeIf { n.deviceCapacity == true }).joinToString(" and "))
        if (n.routers.isNotEmpty()) {
            InfoRow("Answered by", n.routers.sorted().joinToString(", ") { if (it == 0) "coordinator" else "0x%04X".format(it) })
        }
        if (n.possibleVendor != null) {
            Text("The vendor is only a hint: it comes from the extended ID, which many coordinators pick at random.",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
