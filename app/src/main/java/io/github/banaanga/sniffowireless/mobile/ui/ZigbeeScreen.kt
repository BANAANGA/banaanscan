package io.github.banaanga.sniffowireless.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.zigbee.ChannelEnergy
import io.github.banaanga.sniffowireless.mobile.zigbee.UsbDongle
import io.github.banaanga.sniffowireless.mobile.zigbee.ZigbeeNetwork

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
            Seg(listOf(false to "Network scan", true to "Sniffer"), sniffer, { sniffer = it }, Modifier.padding(start = 16.dp, bottom = 6.dp), enabled = !running)
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
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = Sniffo.c.zigbee, trackColor = Sniffo.c.panel2)
                status?.let { StatusLine(it, if (running) Tone.ON else Tone.NONE) }
                error?.let { StatusLine(it, Tone.BAD) }
                info?.let { i ->
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                        InfoRow("Firmware", "${i.firmware} · ${i.family.label}")
                        i.notes.forEach { Muted(it) }
                    }
                }
            }
        }
        if (sniffer) {
            if (framesHeard > 0) {
                item(key = "activity") { SnifferActivity(activity, currentChannel, framesHeard, framesUndecoded) }
            }
            if (sniffed.isNotEmpty()) {
                item(key = "sniffed-divider") { Column { Spacer(Modifier.height(8.dp)); ListHead("${sniffed.size} radios", "Signal") } }
            }
            items(sniffed, key = { "sniffed:" + it.key }) { d -> SniffedDeviceRow(d) { selectedDevice = d.key } }
        } else {
            if (energy.isNotEmpty()) {
                item(key = "energy") { EnergySection(energy) }
            }
            if (networks.isNotEmpty()) {
                item(key = "divider") { Column { Spacer(Modifier.height(8.dp)); ListHead("${networks.size} networks", "Signal") } }
            }
            items(networks, key = { it.key }) { n -> NetworkRow(n) { selectedKey = n.key } }
        }
        if (!sniffer && info != null && !running) {
            item(key = "limits") {
                Hint(
                    "A scan finds networks and the routers that answer it. Battery-powered sensors and the " +
                        "devices inside other networks don't respond, so they won't appear.",
                    Modifier.padding(16.dp),
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
    Panel(title = "Stick") {
        StatusLine("No Zigbee stick plugged in", modifier = Modifier)
        Muted(
            "Phones have no Zigbee radio, so scanning needs a coordinator stick connected through a USB-OTG " +
                "adapter. Sticks running Z-Stack (Sonoff ZBDongle-P, CC2652) and EmberZNet (Sonoff ZBDongle-E, " +
                "SkyConnect, ZBT-1) are supported.",
            Modifier.padding(top = 4.dp),
        )
        Muted("Scanning is read-only and doesn't change anything stored on the stick. Close any other app using it first.",
            Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun DongleCard(dongle: UsbDongle, running: Boolean, startLabel: String, onScan: () -> Unit, onStop: () -> Unit) {
    Panel(title = "Stick", action = {
        when {
            dongle.blocker != null -> Unit
            running -> Btn("Stop", onStop)
            else -> Btn(startLabel, onScan)
        }
    }) {
        StatusLine("${dongle.name} connected", if (dongle.blocker == null) Tone.ON else Tone.WARN, Modifier)
        Text(dongle.id, style = Mono, color = Sniffo.c.muted, modifier = Modifier.padding(top = 2.dp))
        dongle.blocker?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Sniffo.c.bad, modifier = Modifier.padding(top = 4.dp)) }
    }
}

@Composable
private fun EnergySection(energy: List<ChannelEnergy>) {
    Panel(Modifier.padding(top = 6.dp), title = "Channel interference") {
        Muted("Lower is better. Channels 15, 20 and 25 sit between the main Wi-Fi channels.", Modifier.padding(bottom = 6.dp))
        energy.forEach { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.channel.toString(), Modifier.width(28.dp), style = Mono, color = Sniffo.c.muted)
                Bar((e.rssi + 100) / 70f, Sniffo.c.zigbee, Modifier.weight(1f))
                Text("${e.rssi} dBm", Modifier.padding(start = 8.dp).width(60.dp), style = Mono, color = Sniffo.c.muted)
            }
        }
    }
}

private fun panText(panId: Int) = "0x%04X".format(panId)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NetworkRow(n: ZigbeeNetwork, onClick: () -> Unit) {
    DeviceRow(
        title = "PAN ${panText(n.panId)}",
        kindColor = Sniffo.c.zigbee,
        kind = "Zigbee network",
        address = n.extendedPanId,
        identified = n.possibleVendor?.let { "$it?" },
        onClick = onClick,
        trailing = {
            if (n.rssi != null) RssiText(n.rssi)
            else n.lqi?.let { Text("LQI $it", style = Mono, color = Sniffo.c.muted) }
        },
        tags = {
            Tag("ch ${n.channel}")
            if (n.permitJoining) Tag("open for joining", Tone.WARN)
            if (n.routers.size > 1) Tag("${n.routers.size} routers")
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
            Muted("The vendor is only a hint: it comes from the extended ID, which many coordinators pick at random.")
        }
    }
}
