package io.github.banaanga.sniffowireless.mobile.ui

import android.Manifest
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.core.Channels
import io.github.banaanga.sniffowireless.mobile.sensor.SensorService
import io.github.banaanga.sniffowireless.mobile.sensor.SensorSettings
import kotlinx.coroutines.delay

/** Sensor mode: the phone as a remote sensor of a sniffowireless server. */
@Composable
fun SensorScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val hub = vm.hub
    val s = hub.settings
    val running by hub.sensorRunning.collectAsStateWithLifecycle()
    val usbWifi by hub.wifiMonitor.adapter.collectAsStateWithLifecycle()
    val usbWifiKind by hub.wifiMonitor.kind.collectAsStateWithLifecycle()
    val usbBt by hub.btUsb.adapter.collectAsStateWithLifecycle()
    val dongles by hub.zigbee.dongles.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf(s.serverUrl) }
    var token by rememberSaveable { mutableStateOf(s.token) }
    var channels by rememberSaveable { mutableStateOf(s.wifiChannels) }
    var tick by remember { mutableIntStateOf(0) }
    var pasteNote by remember { mutableStateOf<String?>(null) }
    // Sources, as switches; written to the settings at once.
    var phoneWifi by remember { mutableStateOf(s.phoneWifi) }
    var phoneBt by remember { mutableStateOf(s.phoneBt) }
    var usbWifiOn by remember { mutableStateOf(s.usbWifi) }
    var usbBtOn by remember { mutableStateOf(s.usbBt) }
    var zigbeeOn by remember { mutableStateOf(s.zigbee) }
    var loraOn by remember { mutableStateOf(s.lora) }
    val loraBoards by hub.lora.boards.collectAsStateWithLifecycle()
    var btPassive by remember { mutableStateOf(s.btPassive) }

    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            tick++
        }
    }
    val notify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { SensorService.start(context) }
    val channelError = runCatching { Channels.check(channels) }.exceptionOrNull()?.message
    val canStart = url.startsWith("http://") || url.startsWith("https://")

    fun start() {
        s.serverUrl = url
        s.token = token
        s.wifiChannels = channels
        if (Build.VERSION.SDK_INT >= 33) notify.launch(Manifest.permission.POST_NOTIFICATIONS) else SensorService.start(context)
    }

    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        Panel(title = "Remote sensor", action = {
            if (running) Btn("Stop", { SensorService.stop(context) })
            else Btn("Start", ::start, enabled = canStart && token.isNotBlank() && channelError == null)
        }) {
            Column {
                val f0 = hub.forwarder
                @Suppress("UNUSED_EXPRESSION") tick
                val (tone, label) = when {
                    !running -> Tone.NONE to "Off"
                    f0.lastError != null -> Tone.WARN to "Retrying"
                    f0.lastSuccess == null -> Tone.WARN to "Connecting"
                    else -> Tone.ON to "Sending"
                }
                StatusLine(label, tone, Modifier)
                Muted(
                    if (running) "Sending to ${s.serverUrl}" else "Send what this phone hears to a sniffowireless server, like one of its Raspberry Pi sensors.",
                    Modifier.padding(top = 2.dp),
                )
                hub.battery()?.let { b ->
                    val pct = (b["percent"] as Double)
                    val charging = b["charging"] == true
                    StatusLine(
                        "Battery %s%%".format(if (pct % 1.0 == 0.0) pct.toInt().toString() else pct.toString()) +
                            (if (charging) " · charging" + ((b["source"] as String?)?.let { " ($it)" } ?: "") else "") +
                            ((b["temperature"] as Double?)?.let { " · %.1f °C".format(it) } ?: "") +
                            if (running) " · sent to the server" else "",
                        when { charging || pct > 20 -> Tone.ON; pct > 10 -> Tone.WARN; else -> Tone.BAD },
                        Modifier.padding(top = 6.dp),
                    )
                }
                if (running) {
                    val f = hub.forwarder
                    Text(
                        "%,d sent · %,d waiting · %,d dropped".format(f.sent, f.pending, f.dropped) +
                            (f.lastSuccess?.let { " · last upload ${ageText(it)}" } ?: ""),
                        style = Mono, modifier = Modifier.padding(top = 8.dp),
                    )
                    f.lastError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                    val reply = f.lastReply
                    if (hub.wifiControl.rev > 0 || hub.zigbeeControl.rev > 0) {
                        Text(listOfNotNull(
                            hub.wifiControl.override?.let { "Wi-Fi channels from the server (revision ${hub.wifiControl.rev})" },
                            hub.zigbeeControl.override?.let { "Zigbee settings from the server (revision ${hub.zigbeeControl.rev})" },
                        ).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                    }
                    if (reply != null && (reply["rejected"] as? Number)?.toInt()?.let { it > 0 } == true) {
                        Text("The server rejected ${reply["rejected"]} observations in the last batch", color = Sniffo.c.warn, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        SectionTitle("Server")
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Muted("In sniffowireless: Settings → Sensors → Add remote sensor. Copy the docker command it shows (or just the token) and paste it here.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Btn("Paste from clipboard", enabled = !running, onClick = {
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
                    val (u, t) = SensorSettings.parsePaste(text)
                    u?.let { url = it }
                    t?.let { token = it }
                    pasteNote = when {
                        u != null && t != null -> "Server address and token filled in"
                        t != null -> "Token filled in; enter the server address"
                        u != null -> "Server address filled in; the token is missing"
                        else -> "The clipboard has no server address or sniffowireless token"
                    }
                })
            }
            pasteNote?.let { StatusLine(it, modifier = Modifier) }
            FieldLabel("Server address")
            Field(url, { url = it.trim() }, "http://192.168.1.10:8080", enabled = !running, mono = true,
                error = url.isNotEmpty() && !canStart, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri))
            if (url.isNotEmpty() && !canStart) Text("Starts with http:// or https://", style = MaterialTheme.typography.bodySmall, color = Sniffo.c.bad)
            FieldLabel("Sensor token")
            Field(token, { token = it.trim() }, "sniffowireless_…", enabled = !running, mono = true, visual = PasswordVisualTransformation())
        }

        SectionTitle("What to send")
        Column(Modifier.padding(horizontal = 12.dp)) {
            SourceRow("Phone Wi-Fi scan", "Access points around the phone (no clients: that needs monitor mode).", phoneWifi, !running) { phoneWifi = it; s.phoneWifi = it }
            SourceRow("Phone Bluetooth", "BLE adverts and classic devices the phone's own Bluetooth hears.", phoneBt, !running) { phoneBt = it; s.phoneBt = it }
            SourceRow("USB Wi-Fi adapter (MT7921U or AR9271)",
                if (usbWifi != null) "Connected: ${usbWifiKind?.label}, monitor mode, access points and clients on ${usbWifiKind?.bands?.joinToString(", ")} GHz." else "Not connected.", usbWifiOn, !running) { usbWifiOn = it; s.usbWifi = it }
            if (usbWifiOn) {
                FieldLabel("Channels when the server sets none")
                Field(channels, { channels = it }, "default,6ghz", enabled = !running, mono = true, error = channelError != null)
                Text(channelError ?: "As in sniffowireless: default, all, 6ghz, 1,6,11, 36-48, 6g:37 … The server's Settings → Wi-Fi channels wins.",
                    style = MaterialTheme.typography.bodySmall, color = if (channelError != null) Sniffo.c.bad else Sniffo.c.muted,
                    modifier = Modifier.padding(top = 4.dp, bottom = 6.dp))
            }
            SourceRow("USB Bluetooth adapter",
                if (usbBt != null) "Connected: ${usbBt!!.label}. Every advert, long range too." else "Not connected.", usbBtOn, !running) { usbBtOn = it; s.usbBt = it }
            if (usbBtOn) SourceRow("Listen only", "No scan requests or classic inquiry: transmits nothing, but finds fewer names.", btPassive, !running, indent = true) {
                btPassive = it; s.btPassive = it
            }
            val board = loraBoards.firstOrNull()
            SourceRow("LoRa board (lora-sniffer)", board?.let { "Connected: ${it.name}. Frames go to the server, which recognises them; profiles as set on the LoRa page or by the server's Settings → LoRa." }
                ?: "Not connected. A Heltec WiFi LoRa 32 V3 with sniffowireless's lora-sniffer firmware.", loraOn, !running) { loraOn = it; s.lora = it }
            val stick = dongles.firstOrNull { it.device != null && it.blocker == null }
            SourceRow("Zigbee stick", stick?.let { "Connected: ${it.name}. Scans every 10 minutes and listens in between, as the server's Settings → Zigbee says." } ?: "Not connected.",
                zigbeeOn, !running) { zigbeeOn = it; s.zigbee = it }
            Muted("Plug adapters in before pressing Start. The server's Sensors page shows each one with its state.", Modifier.padding(top = 4.dp))
        }

        if (running) {
            @Suppress("UNUSED_EXPRESSION") tick
            SectionTitle("Interfaces as the server sees them")
            Column(Modifier.padding(horizontal = 12.dp)) {
                hub.status.snapshot().forEach { e ->
                    val state = e["state"]?.toString() ?: "?"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 3.dp)) {
                        Dot(when { e["error"] != null -> Sniffo.c.bad; e["warning"] != null -> Sniffo.c.warn; else -> Sniffo.c.good }, 8)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${e["name"]} · $state" + (e["channel"]?.let { " · ch $it" } ?: ""), style = Mono)
                            Muted(listOfNotNull(e["packets"]?.let { "$it packets" }, e["pps"]?.let { "$it/s" },
                                (e["error"] ?: e["warning"])?.toString()).joinToString(" · "))
                        }
                    }
                }
                if (hub.status.snapshot().isEmpty()) Muted("No sources running: plug in an adapter or turn on a phone source, then restart sensor mode.")
            }
        }

        SectionTitle("Keep it running")
        Column(Modifier.padding(horizontal = 12.dp)) {
            val pm = context.getSystemService(PowerManager::class.java)
            val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            StatusLine(if (exempt) "Battery optimisation is off for this app: Android won't pause sensor mode." else
                "Android may pause the app to save battery. For a sensor that runs for days, turn battery optimisation off for it.",
                if (exempt) Tone.ON else Tone.WARN, Modifier)
            if (!exempt) Btn("Battery settings", { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } },
                Modifier.padding(top = 8.dp))
        }

        SectionTitle("Log")
        @Suppress("UNUSED_EXPRESSION") tick
        Column(Modifier.padding(horizontal = 12.dp)) {
            val lines = hub.logs.recent(40)
            if (lines.isEmpty()) Muted("Nothing yet.")
            lines.asReversed().forEach { l ->
                Text("${l["level"]} ${l["msg"]}", style = Mono,
                    color = if (l["level"] == "WARNING" || l["level"] == "ERROR") Sniffo.c.warn else Sniffo.c.muted)
            }
        }
    }
}

@Composable
private fun SourceRow(title: String, detail: String, checked: Boolean, enabled: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Muted(detail)
        }
        Switch(checked, onChange, enabled = enabled, colors = SwitchDefaults.colors(checkedTrackColor = Sniffo.c.good, checkedThumbColor = Sniffo.c.bg))
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelMedium, color = Sniffo.c.muted, modifier = Modifier.padding(top = 4.dp))
}
