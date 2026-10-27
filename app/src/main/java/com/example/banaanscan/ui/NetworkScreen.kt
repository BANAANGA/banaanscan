package com.example.banaanscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.data.LanDevice
import com.example.banaanscan.scan.LocalNetwork
import com.example.banaanscan.scan.Probes

@Composable
fun NetworkScreen(vm: MainViewModel) {
    val net by vm.lan.network.collectAsStateWithLifecycle()
    val devices by vm.lan.devices.collectAsStateWithLifecycle()
    val running by vm.lan.running.collectAsStateWithLifecycle()
    val progress by vm.lan.progress.collectAsStateWithLifecycle()
    val status by vm.lan.status.collectAsStateWithLifecycle()
    var selectedIp by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) { vm.lan.refreshNetwork() }

    // One scrolling list, so the header doesn't crowd out the devices in landscape.
    LazyColumn {
        item(key = "header") {
            Column {
                NetworkCard(net)
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(status, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                    if (running) OutlinedButton(onClick = vm::stopLan) { Text("Stop") }
                    else Button(onClick = vm::scanLan) { Text("Scan") }
                }
                if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                if (devices.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
                        devices.groupingBy { it.guess.type }.eachCount().entries.sortedByDescending { it.value }
                            .forEach { (type, n) -> Tag("${type.label}: $n") }
                    }
                }
                HorizontalDivider()
            }
        }
        items(devices, key = { it.ip }) { d -> LanRow(d) { selectedIp = d.ip } }
    }

    selectedIp?.let { ip ->
        devices.firstOrNull { it.ip == ip }?.let { d -> LanDetail(d) { selectedIp = null } }
    }
}

@Composable
private fun NetworkCard(net: LocalNetwork?) {
    Card(Modifier.fillMaxWidth().padding(12.dp)) {
        Column(Modifier.padding(12.dp)) {
            if (net == null) {
                Text("Not connected to Wi-Fi or Ethernet", fontWeight = FontWeight.Bold)
                return@Column
            }
            Text(net.ssid ?: net.transport, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            InfoRow("This device", "${net.ip}/${net.prefix}")
            InfoRow("Subnet", net.cidr)
            InfoRow("Gateway", net.gateway)
            InfoRow("DNS", net.dns.joinToString(", "))
            InfoRow("AP BSSID", net.bssid)
        }
    }
}

@Composable
private fun LanRow(d: LanDevice, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(d.guess.type.icon(), contentDescription = d.guess.type.label) },
        headlineContent = { Text(d.displayName, fontWeight = if (d.isSelf || d.isGateway) FontWeight.Bold else FontWeight.Normal) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(d.ip, d.mac, d.vendor).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow {
                    Tag(d.guess.short())
                    if (d.isSelf) Tag("You")
                    if (d.isGateway) Tag("Gateway")
                    if (d.openPorts.isNotEmpty()) Tag("${d.openPorts.size} open port" + if (d.openPorts.size == 1) "" else "s")
                    if (d.mdnsServices.isNotEmpty()) Tag("mDNS")
                    if (d.ssdp != null) Tag("UPnP")
                }
            }
        },
    )
}

@Composable
private fun LanDetail(d: LanDevice, onDismiss: () -> Unit) {
    DetailDialog(d.displayName, onDismiss) {
        InfoRow("IP", d.ip)
        InfoRow("MAC", d.mac?.let { "$it (${d.macSource ?: "?"})" } ?: "Hidden by Android")
        InfoRow("Vendor", d.vendor)
        InfoRow("Note", d.note)
        InfoRow("Hostname", d.hostname)
        InfoRow("NetBIOS", listOfNotNull(d.netbiosName, d.netbiosGroup?.let { "workgroup $it" }).joinToString(" · "))
        InfoRow("mDNS names", (d.mdnsNames + d.mdnsHosts).joinToString(", "))
        if (d.openPorts.isNotEmpty()) {
            SectionTitle("Open ports")
            d.openPorts.sorted().forEach { InfoRow(it.toString(), Probes.COMMON_PORTS[it] ?: "") }
        }
        if (d.mdnsServices.isNotEmpty()) {
            SectionTitle("mDNS services")
            d.mdnsServices.sorted().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (d.mdnsTxt.isNotEmpty()) {
            SectionTitle("mDNS TXT")
            d.mdnsTxt.forEach { (k, v) -> InfoRow(k, v) }
        }
        d.ssdp?.let { u ->
            SectionTitle("UPnP")
            InfoRow("Name", u.friendlyName)
            InfoRow("Maker", u.manufacturer)
            InfoRow("Model", u.modelName)
            InfoRow("Type", u.deviceType)
            InfoRow("Server", u.server)
        }
        if (d.httpServer != null || d.httpTitle != null) {
            SectionTitle("Web interface")
            InfoRow("Server", d.httpServer)
            InfoRow("Title", d.httpTitle)
        }
        GuessSection(d.guess)
    }
}
