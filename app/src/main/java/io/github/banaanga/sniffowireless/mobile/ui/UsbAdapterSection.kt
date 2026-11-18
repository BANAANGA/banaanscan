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
import io.github.banaanga.sniffowireless.mobile.wifimon.UsbWifiMonitor
import io.github.banaanga.sniffowireless.mobile.sensor.SensorSettings
import io.github.banaanga.sniffowireless.mobile.core.Channels
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text

/**
 * Each plugged-in Wi-Fi adapter with the channels it gets and a field for its own channel list. [general] is the list
 * the adapters share (the Wi-Fi page's plan, or the Sensor page's channel list). Edits apply at the next hop.
 */
@Composable
fun AdapterChannels(mon: UsbWifiMonitor, settings: SensorSettings, general: List<Int>, editable: Boolean = true) {
    val p = Sniffo.c
    val adapters by mon.adapters.collectAsStateWithLifecycle()
    val states by mon.states.collectAsStateWithLifecycle()
    val running by mon.running.collectAsStateWithLifecycle()
    var version by remember { mutableIntStateOf(0) }  // re-plan after an edit
    val planned = remember(adapters, general, version) { mon.plan(general, 400).first }
    adapters.forEach { a ->
        key(a.name) {
            val st = states[a.name]
            var spec by remember(a.name) { mutableStateOf(settings.wifiChannelsFor(a.name)) }
            val bad = spec.isNotBlank() && runCatching { Channels.check(spec) }.isFailure
            Column(Modifier.padding(top = 10.dp)) {
                StatusLine("${a.kind.label} · ${a.name} · ${a.kind.bands.joinToString("/")} GHz",
                    when { st?.error != null -> Tone.BAD; running && st?.freq != null -> Tone.ON; else -> Tone.NONE }, Modifier)
                val chans = (if (running) st?.channels?.takeIf { it.isNotEmpty() } else null) ?: planned[a.name].orEmpty()
                Muted((if (spec.isBlank()) "Automatic: " else "Own list: ") + chans.joinToString(", ") { Channels.label(it) },
                    Modifier.padding(top = 2.dp))
                st?.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = p.bad) }
                    ?: st?.status?.takeIf { running }?.let { Muted(it) }
                if (editable) {
                    Field(spec, { v ->
                        spec = v
                        if (v.isBlank() || runCatching { Channels.check(v) }.isSuccess) {
                            settings.setWifiChannelsFor(a.name, v)
                            version++
                        }
                    }, "automatic share", Modifier.padding(top = 4.dp), mono = true, error = bad)
                }
            }
        }
    }
    if (adapters.size > 1 && editable) {
        Muted("The channels are split so no channel is listened to twice; an adapter that is the only one able to tune " +
            "a band keeps it. Give an adapter its own list (1,6,11 · 36-48 · 6ghz …) to override; a list set for it on " +
            "the server (Settings → Wi-Fi channels) wins in sensor mode.", Modifier.padding(top = 8.dp))
    }
}

/** Wi-Fi monitor mode through MT7921U and AR9271 USB adapters, several at once, driven by sniffowireless-mobile's own drivers. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UsbAdapterSection(vm: MainViewModel) {
    val mon = vm.wifiMonitor
    val p = Sniffo.c
    val adapters by mon.adapters.collectAsStateWithLifecycle()
    val firmware by mon.firmwarePresent.collectAsStateWithLifecycle()
    val running by mon.running.collectAsStateWithLifecycle()
    val status by mon.status.collectAsStateWithLifecycle()
    val error by mon.error.collectAsStateWithLifecycle()
    val states by mon.states.collectAsStateWithLifecycle()
    val networks by mon.networks.collectAsStateWithLifecycle()
    val clients by mon.clients.collectAsStateWithLifecycle()
    val channels by mon.channels.collectAsStateWithLifecycle()
    val frames by mon.frames.collectAsStateWithLifecycle()
    var plan by rememberSaveable { mutableStateOf(ChannelPlan.ALL) }
    val current = states.values.mapNotNull { it.freq }.toSet()

    LaunchedEffect(Unit) { mon.refresh() }

    LazyColumn {
        item(key = "card") {
            Panel(
                title = if (adapters.size > 1) "${adapters.size} adapters" else "Adapter",
                action = {
                    when {
                        running -> Btn("Stop", vm::stopWifiMonitor)
                        adapters.isNotEmpty() -> Btn("Listen", { vm.startWifiMonitor(plan) }, enabled = firmware)
                    }
                },
            ) {
                if (adapters.isEmpty()) {
                    StatusLine("No adapter plugged in", Tone.NONE, Modifier)
                    Muted("Connect an MT7921U adapter (BrosTrend AX9L, Alfa AWUS036AXML…) and/or an AR9271 one (2.4 GHz only: " +
                        "Alfa AWUS036NHA, TP-Link TL-WN722N v1…) with a USB-C cable, OTG adapter or USB hub. Several can run at " +
                        "once. Monitor mode runs on sniffowireless-mobile's own drivers, without root.", Modifier.padding(top = 4.dp))
                } else {
                    Muted(if (!firmware) "Firmware missing from this build: run tools/mt7921u/push-firmware.sh on your computer once."
                        else "Listens only: nothing is transmitted.")
                    Seg(ChannelPlan.entries.map { it to it.label }, plan, { plan = it },
                        Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), enabled = !running)
                    AdapterChannels(mon, vm.hub.settings, plan.freqs)
                }
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
                                Tag((if (c.freqMhz in current) "▶ " else "") + "${Channels.label(c.freqMhz)}: ${c.frames}",
                                    if (c.freqMhz in current) Tone.ON else Tone.NONE)
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
