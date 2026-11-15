package io.github.banaanga.sniffowireless.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.mt7921u.Channel
import io.github.banaanga.sniffowireless.mobile.wifimon.ChannelPlan

/** Wi-Fi monitor mode through an MT7921U USB adapter, driven by sniffowireless-mobile's own driver. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsbAdapterSection(vm: MainViewModel) {
    val mon = vm.wifiMonitor
    val p = Sniffo.c
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
    var plan by rememberSaveable { mutableStateOf(ChannelPlan.ALL) }

    LaunchedEffect(Unit) { mon.refresh() }

    LazyColumn {
        item(key = "card") {
            Panel(
                title = "Adapter",
                action = {
                    when {
                        running -> Btn("Stop", vm::stopWifiMonitor)
                        adapter != null -> Btn("Listen", { vm.startWifiMonitor(plan) }, enabled = firmware)
                    }
                },
            ) {
                StatusLine(if (adapter != null) "MediaTek MT7921U connected" else "No adapter plugged in",
                    if (adapter != null) Tone.ON else Tone.NONE, Modifier)
                Muted(
                    if (adapter == null) "Connect an MT7921U adapter (BrosTrend AX9L, Alfa AWUS036AXML…) with a USB-C cable or OTG adapter. Monitor mode runs on sniffowireless-mobile's own driver, without root."
                    else if (!firmware) "Firmware missing from this build: run tools/mt7921u/push-firmware.sh on your computer once."
                    else "Listens only: nothing is transmitted.",
                    Modifier.padding(top = 4.dp),
                )
                Seg(ChannelPlan.entries.map { it to it.label }, plan, { plan = it },
                    Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), enabled = !running)
            }
        }
        item(key = "status") {
            Column {
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = p.ap, trackColor = p.panel2)
                status?.let { StatusLine(it, if (running) Tone.ON else Tone.NONE) }
                error?.let { StatusLine(it, Tone.BAD) }
                if (frames > 0) {
                    Muted("%,d frames · ${networks.size} networks · ${clients.size} devices".format(frames), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        H2("Frames per channel")
                        FlowRow {
                            channels.forEach { c ->
                                Tag((if (c.freqMhz == current) "▶ " else "") + "${Channel.fromFreq(c.freqMhz).number}: ${c.frames}",
                                    if (c.freqMhz == current) Tone.ON else Tone.NONE)
                            }
                        }
                    }
                }
            }
        }
        if (networks.isNotEmpty()) {
            item(key = "nets-title") { Column { Spacer(Modifier.height(8.dp)); ListHead("${networks.size} access points", "Signal") } }
        }
        items(networks, key = { "n:" + it.bssid }) { n ->
            DeviceRow(
                title = n.ssid?.ifEmpty { "‹hidden›" } ?: "‹unknown›",
                strong = !n.ssid.isNullOrEmpty(),
                kindColor = p.ap, kind = "AP", address = n.bssid,
                identified = n.vendor,
                trailing = { RssiText(n.rssi) },
                tags = {
                    Tag("ch ${Channel.fromFreq(n.freqMhz).number}")
                    Tag("${n.beacons} beacons")
                    if (n.clients > 0) Tag("${n.clients} devices")
                },
            )
        }
        if (clients.isNotEmpty()) {
            item(key = "clients-title") { Column { Spacer(Modifier.height(8.dp)); ListHead("${clients.size} Wi-Fi clients", "Signal") } }
        }
        items(clients, key = { "c:" + it.mac }) { c ->
            val ap = c.bssid?.let { b -> networks.firstOrNull { it.bssid == b }?.ssid?.takeIf(String::isNotEmpty) ?: b }
            DeviceRow(
                title = ap?.let { "on $it" } ?: "Not connected",
                strong = ap != null,
                kindColor = p.wifiClient, kind = "Wi-Fi client", address = c.mac,
                identified = c.vendor,
                note = c.probes.takeIf { it.isNotEmpty() }?.let { "Looking for: " + it.joinToString(", ") },
                trailing = { RssiText(c.rssi) },
                tags = {
                    if (c.randomized) Tag("rand", Tone.WARN)
                    Tag("${c.frames} frames")
                },
            )
        }
    }
}
