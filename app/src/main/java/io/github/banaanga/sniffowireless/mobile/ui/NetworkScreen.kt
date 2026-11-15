package io.github.banaanga.sniffowireless.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.data.DeviceType
import io.github.banaanga.sniffowireless.mobile.data.LanDevice
import io.github.banaanga.sniffowireless.mobile.scan.LocalNetwork
import io.github.banaanga.sniffowireless.mobile.scan.Probes

@OptIn(ExperimentalLayoutApi::class)
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
                NetworkCard(net) { if (running) Btn("Stop", vm::stopLan) else Btn("Scan", vm::scanLan) }
                if (running) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                    color = Sniffo.c.lan, trackColor = Sniffo.c.panel2, drawStopIndicator = {})
                StatusLine(status, if (running) Tone.ON else Tone.NONE)
                if (devices.isNotEmpty()) {
                    FlowRow(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        devices.groupingBy { it.guess.type }.eachCount().entries.sortedByDescending { it.value }
                            .forEach { (type, n) -> Tag("${type.label}: $n") }
                    }
                }
                Spacer(Modifier.height(6.dp))
                ListHead("${devices.size} hosts", "Ports")
            }
        }
        items(devices, key = { it.ip }) { d -> LanRow(d) { selectedIp = d.ip } }
    }

    selectedIp?.let { ip ->
        devices.firstOrNull { it.ip == ip }?.let { d -> LanDetail(d) { selectedIp = null } }
    }
}

@Composable
private fun NetworkCard(net: LocalNetwork?, action: @Composable RowScope.() -> Unit) {
    Panel(title = net?.let { it.ssid ?: it.transport } ?: "Network", action = action) {
        if (net == null) {
            StatusLine("Not connected to Wi-Fi or Ethernet", Tone.BAD, Modifier)
            return@Panel
        }
        InfoRow("This device", "${net.ip}/${net.prefix}")
        InfoRow("Subnet", net.cidr)
        InfoRow("Gateway", net.gateway)
        InfoRow("DNS", net.dns.joinToString(", "))
        InfoRow("AP BSSID", net.bssid)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanRow(d: LanDevice, onClick: () -> Unit) {
    DeviceRow(
        title = d.displayName,
        strong = d.displayName != d.ip,
        kindColor = Sniffo.c.lan,
        kind = "LAN",
        address = d.ip,
        identified = listOfNotNull(d.mac, d.vendor, d.guess.short().takeIf { d.guess.type != DeviceType.UNKNOWN }).joinToString(" · "),
        onClick = onClick,
        trailing = { if (d.openPorts.isNotEmpty()) Text("${d.openPorts.size}", style = Mono, color = Sniffo.c.muted) },
        tags = if (d.isSelf || d.isGateway || d.mdnsServices.isNotEmpty() || d.ssdp != null) {
            {
                if (d.isSelf) Tag("this phone", Tone.ON)
                if (d.isGateway) Tag("gateway")
                if (d.mdnsServices.isNotEmpty()) Tag("mDNS")
                if (d.ssdp != null) Tag("UPnP")
            }
        } else null,
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
            DetailTitle("Open ports")
            d.openPorts.sorted().forEach { InfoRow(it.toString(), Probes.COMMON_PORTS[it] ?: "") }
        }
        if (d.mdnsServices.isNotEmpty()) {
            DetailTitle("mDNS services")
            d.mdnsServices.sorted().forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        if (d.mdnsTxt.isNotEmpty()) {
            DetailTitle("mDNS TXT")
            d.mdnsTxt.forEach { (k, v) -> InfoRow(k, v) }
        }
        d.ssdp?.let { u ->
            DetailTitle("UPnP")
            InfoRow("Name", u.friendlyName)
            InfoRow("Maker", u.manufacturer)
            InfoRow("Model", u.modelName)
            InfoRow("Type", u.deviceType)
            InfoRow("Server", u.server)
        }
        if (d.httpServer != null || d.httpTitle != null) {
            DetailTitle("Web interface")
            InfoRow("Server", d.httpServer)
            InfoRow("Title", d.httpTitle)
        }
        GuessSection(d.guess)
    }
}
