package io.github.banaanga.sniffowireless.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.core.LoraProfiles
import io.github.banaanga.sniffowireless.mobile.lora.LoraFrame
import io.github.banaanga.sniffowireless.mobile.lora.LoraNode
import kotlinx.coroutines.launch

/** sniffowireless's colours per LoRa protocol (static/lora.js). */
@Composable
fun loraColor(protocol: String): Color = when (protocol) {
    "meshtastic" -> Sniffo.c.good
    "meshcore" -> Sniffo.c.ap
    "lorawan" -> Sniffo.c.warn
    "lora-aprs" -> Sniffo.c.lora
    else -> Sniffo.c.lan
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LoraScreen(vm: MainViewModel) {
    val lora = vm.lora
    val s = vm.hub.settings
    val p = Sniffo.c
    val scope = rememberCoroutineScope()
    val boards by lora.boards.collectAsStateWithLifecycle()
    val running by lora.running.collectAsStateWithLifecycle()
    val status by lora.status.collectAsStateWithLifecycle()
    val error by lora.error.collectAsStateWithLifecycle()
    val current by lora.current.collectAsStateWithLifecycle()
    val firmware by lora.firmware.collectAsStateWithLifecycle()
    val nodes by lora.nodes.collectAsStateWithLifecycle()
    val frames by lora.frames.collectAsStateWithLifecycle()
    val perProfile by lora.perProfile.collectAsStateWithLifecycle()
    val heard by lora.heard.collectAsStateWithLifecycle()
    val crc by lora.crcErrors.collectAsStateWithLifecycle()
    val sensorOn by vm.hub.sensorRunning.collectAsStateWithLifecycle()
    var region by rememberSaveable { mutableStateOf(s.loraRegion) }
    var profiles by rememberSaveable { mutableStateOf(s.loraProfiles) }
    var hop by rememberSaveable { mutableStateOf(s.loraHop.toString().removeSuffix(".0")) }
    var readPublic by rememberSaveable { mutableStateOf(s.meshtasticPublic) }
    var view by rememberSaveable { mutableStateOf("nodes") }
    var selected by remember { mutableStateOf<String?>(null) }

    val parsed = runCatching { LoraProfiles.parse(profiles, region) }
    val hopValue = hop.toDoubleOrNull()?.takeIf { it in 0.5..600.0 }
    val board = boards.firstOrNull()

    LaunchedEffect(Unit) { lora.refresh() }
    // The page's own listening stops when you leave it; sensor mode keeps its own.
    DisposableEffect(Unit) { onDispose { if (!sensorOn) lora.stop() } }

    fun save() {
        s.loraRegion = region
        s.loraProfiles = profiles
        hopValue?.let { s.loraHop = it }
        s.meshtasticPublic = readPublic
    }

    LazyColumn {
        item(key = "board") {
            Panel(
                title = "Board",
                action = {
                    when {
                        running -> Btn("Stop", lora::stop, enabled = !sensorOn)
                        board != null -> Btn("Listen", { save(); scope.launch { lora.run(board) } }, enabled = parsed.isSuccess && hopValue != null)
                        lora.simulator != null -> Btn("Listen", { save(); scope.launch { lora.runSimulated() } }, enabled = parsed.isSuccess && hopValue != null)
                    }
                },
            ) {
                StatusLine(
                    when {
                        board == null && lora.simulator != null -> "Simulated lora-sniffer board" + (firmware?.let { " · $it" } ?: "")
                        board == null -> "No LoRa board plugged in"
                        firmware != null -> "${board.name} · $firmware"
                        else -> board.name
                    },
                    if (running) Tone.ON else Tone.NONE, Modifier,
                )
                Muted(
                    if (board == null && lora.simulator != null) "Debug build: made-up frames, for trying the page without hardware."
                    else if (board == null) "Plug in a Heltec WiFi LoRa 32 V3 with sniffowireless's lora-sniffer firmware (flash it from the " +
                        "sniffowireless web interface: Settings → LoRa flasher). It only listens: the firmware has no transmit code."
                    else if (sensorOn && running) "Sensor mode is using the board and sending its frames to the server."
                    else "Hops over the profiles below and recognises Meshtastic, MeshCore, LoRaWAN and LoRa APRS frames. Nothing is transmitted.",
                    Modifier.padding(top = 4.dp),
                )
            }
        }
        item(key = "settings") {
            Panel(title = "Profiles") {
                Seg(listOf("EU868" to "EU868", "US915" to "US915", "ANZ" to "ANZ"), region, { region = it }, enabled = !running)
                Text("Profiles (empty: the region's defaults)", style = androidx.compose.material3.MaterialTheme.typography.labelMedium,
                    color = p.muted, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Field(profiles, { profiles = it }, LoraProfiles.REGION_DEFAULTS[region]?.joinToString(",") ?: "", enabled = !running, mono = true,
                    error = parsed.isFailure)
                Text(
                    parsed.exceptionOrNull()?.message ?: parsed.getOrNull()?.joinToString(" · ") { it.name }.orEmpty(),
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = if (parsed.isFailure) p.bad else p.muted, modifier = Modifier.padding(top = 4.dp),
                )
                Muted("As in sniffowireless: meshtastic-eu868, meshcore-eu868, lorawan:eu868:7-9, meshtastic:US:MediumFast, " +
                    "or your own name=freq/bw/sf/cr/sync. A profile set for this sensor on the server wins in sensor mode.", Modifier.padding(top = 4.dp))
                Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Seconds on each", style = androidx.compose.material3.MaterialTheme.typography.labelMedium, color = p.muted)
                    Field(hop, { hop = it }, "5", Modifier.padding(start = 10.dp).width(80.dp), enabled = !running, mono = true, error = hopValue == null,
                        keyboard = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Decimal))
                }
                Check("Read the public Meshtastic channel", readPublic, { readPublic = it; s.meshtasticPublic = it })
                Muted("Messages, names and positions on the default channel use a key published by Meshtastic, so anyone can read them. " +
                    "Private channels and direct messages stay unreadable.")
            }
        }
        item(key = "status") {
            Column {
                if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = p.lora, trackColor = p.panel2)
                status?.let { StatusLine(it, if (running) Tone.ON else Tone.NONE) }
                error?.let { StatusLine(it, Tone.BAD) }
                if (heard > 0 || crc > 0) Muted("%,d frames · %,d CRC errors · %d nodes".format(heard, crc, nodes.size), Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
                if (perProfile.isNotEmpty()) {
                    Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                        H2("Frames per profile")
                        FlowRow { perProfile.forEach { Tag("${it.profile}: ${it.frames}", if (it.profile == current?.name) Tone.ON else Tone.NONE) } }
                    }
                }
                Seg(listOf("nodes" to "Nodes ${nodes.size}", "frames" to "Frames ${frames.size}"), view, { view = it },
                    Modifier.padding(start = 16.dp, top = 10.dp, bottom = 6.dp))
                if (view == "nodes") ListHead("${nodes.size} nodes", "Signal") else ListHead("Last ${frames.size} frames", "Signal")
            }
        }
        if (view == "nodes") {
            items(nodes, key = { it.key }) { n -> NodeRow(n) { selected = n.key } }
        } else {
            items(frames) { f -> FrameRow(f) }
        }
        if (nodes.isNotEmpty() || frames.isNotEmpty()) item(key = "clear") {
            Row(Modifier.padding(16.dp)) { Btn("Clear", lora::clear) }
        }
    }

    selected?.let { key ->
        nodes.firstOrNull { it.key == key }?.let { n ->
            DetailDialog(n.name ?: n.address, onDismiss = { selected = null }) {
                InfoRow("Protocol", n.label)
                InfoRow("Address", n.address)
                InfoRow("Kind", n.kind)
                InfoRow("Hardware", n.hardware)
                InfoRow("Role", n.role)
                InfoRow("Position", if (n.lat != null && n.lon != null) "${n.lat}, ${n.lon}" else null)
                InfoRow("Telemetry", n.telemetry)
                InfoRow("Last message", n.lastMessage)
                InfoRow("Frames", n.packets.toString())
                InfoRow("Signal", n.rssi?.let { "%.1f dBm · SNR %.1f dB".format(it, n.snr ?: 0.0) })
                InfoRow("Profile", n.profile)
                InfoRow("Last seen", ageText(n.lastSeen))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeRow(n: LoraNode, onClick: () -> Unit) {
    DeviceRow(
        title = n.name ?: "—",
        strong = n.name != null,
        kindColor = loraColor(n.protocol),
        kind = n.label,
        address = n.address,
        identified = listOfNotNull(n.kind, n.hardware, n.role, n.telemetry).joinToString(" · "),
        note = n.lastMessage?.let { "“$it”" },
        onClick = onClick,
        trailing = { n.rssi?.let { RssiText(it.toInt()) } },
        tags = {
            Tag("${n.packets} frames")
            n.profile?.let { Tag(it) }
            if (n.lat != null) Tag("position", Tone.ON)
        },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameRow(f: LoraFrame) {
    DeviceRow(
        title = listOfNotNull(f.kind, f.src?.let { s -> "from $s" }, f.dst?.let { "to $it" }).joinToString(" "),
        strong = f.protocol != "unknown",
        kindColor = loraColor(f.protocol),
        kind = f.label,
        address = null,
        identified = listOfNotNull(f.decoded, "${f.bytes} bytes", f.hops?.let { "$it hops" }, "SNR %.1f dB".format(f.snr)).joinToString(" · "),
        note = "${f.profile} · ${ageText(f.ts)}",
        trailing = { RssiText(f.rssi.toInt()) },
        tags = if (f.protocol != "unknown") { { Tag("${f.confidence}%", if (f.confidence >= 80) Tone.ON else Tone.NONE) } } else null,
    )
}
