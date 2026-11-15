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
        Card(Modifier.fillMaxWidth().padding(12.dp)) {
            Column(Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val f = hub.forwarder
                    @Suppress("UNUSED_EXPRESSION") tick
                    val (color, label) = when {
                        !running -> MaterialTheme.colorScheme.outline to "Off"
                        f.lastError != null -> Sniffo.warn to "Retrying"
                        f.lastSuccess == null -> Sniffo.warn to "Connecting"
                        else -> Sniffo.good to "Sending"
                    }
                    Spacer(Modifier.size(10.dp).clip(CircleShape).background(color))
                    Spacer(Modifier.width(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Remote sensor · $label", fontWeight = FontWeight.Bold)
                        Text(
                            if (running) "Sending to ${s.serverUrl}" else "Send what this phone hears to a sniffowireless server, like one of its Raspberry Pi sensors.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    if (running) OutlinedButton(onClick = { SensorService.stop(context) }) { Text("Stop") }
                    else Button(onClick = ::start, enabled = canStart && token.isNotBlank() && channelError == null) { Text("Start") }
                }
                if (running) {
                    val f = hub.forwarder
                    Text(
                        "%,d sent · %,d waiting · %,d dropped".format(f.sent, f.pending, f.dropped) +
                            (f.lastSuccess?.let { " · last upload ${ageText(it)}" } ?: ""),
                        style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 8.dp),
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
                        Text("The server rejected ${reply["rejected"]} observations in the last batch", color = Sniffo.warn, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }

        SectionTitle("Server")
        Column(Modifier.padding(horizontal = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("In sniffowireless: Settings → Sensors → Add remote sensor. Copy the docker command it shows (or just the token) and paste it here.",
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(enabled = !running, onClick = {
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
                }) { Text("Paste from clipboard") }
            }
            pasteNote?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            OutlinedTextField(url, { url = it.trim() }, Modifier.fillMaxWidth(), enabled = !running, singleLine = true,
                label = { Text("Server address") }, placeholder = { Text("http://192.168.1.10:8080") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = url.isNotEmpty() && !canStart, supportingText = { if (url.isNotEmpty() && !canStart) Text("Starts with http:// or https://") })
            OutlinedTextField(token, { token = it.trim() }, Modifier.fillMaxWidth(), enabled = !running, singleLine = true,
                label = { Text("Sensor token") }, visualTransformation = PasswordVisualTransformation())
        }

        SectionTitle("What to send")
        Column(Modifier.padding(horizontal = 12.dp)) {
            SourceRow("Phone Wi-Fi scan", "Access points around the phone (no clients: that needs monitor mode).", phoneWifi, !running) { phoneWifi = it; s.phoneWifi = it }
            SourceRow("Phone Bluetooth", "BLE adverts and classic devices the phone's own Bluetooth hears.", phoneBt, !running) { phoneBt = it; s.phoneBt = it }
            SourceRow("USB Wi-Fi adapter (MT7921U)",
                if (usbWifi != null) "Connected: monitor mode, access points and clients on 2.4, 5 and 6 GHz." else "Not connected.", usbWifiOn, !running) { usbWifiOn = it; s.usbWifi = it }
            if (usbWifiOn) {
                OutlinedTextField(channels, { channels = it }, Modifier.fillMaxWidth().padding(bottom = 4.dp), enabled = !running, singleLine = true,
                    label = { Text("Channels when the server sets none") }, isError = channelError != null,
                    supportingText = { Text(channelError ?: "As in sniffowireless: default, all, 6ghz, 1,6,11, 36-48, 6g:37 … The server's Settings → Wi-Fi channels wins.") })
            }
            SourceRow("USB Bluetooth adapter",
                if (usbBt != null) "Connected: ${usbBt!!.label}. Every advert, long range too." else "Not connected.", usbBtOn, !running) { usbBtOn = it; s.usbBt = it }
            if (usbBtOn) SourceRow("Listen only", "No scan requests or classic inquiry: transmits nothing, but finds fewer names.", btPassive, !running, indent = true) {
                btPassive = it; s.btPassive = it
            }
            val stick = dongles.firstOrNull { it.device != null }
            SourceRow("Zigbee stick", stick?.let { "Connected: ${it.name}. Scans every 10 minutes and listens in between, as the server's Settings → Zigbee says." } ?: "Not connected.",
                zigbeeOn, !running) { zigbeeOn = it; s.zigbee = it }
            Text("Plug adapters in before pressing Start. The server's Sensors page shows each one with its state.", style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp))
        }

        if (running) {
            @Suppress("UNUSED_EXPRESSION") tick
            SectionTitle("Interfaces as the server sees them")
            Column(Modifier.padding(horizontal = 12.dp)) {
                hub.status.snapshot().forEach { e ->
                    val state = e["state"]?.toString() ?: "?"
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
                        Spacer(Modifier.size(8.dp).clip(CircleShape).background(
                            when { e["error"] != null -> Sniffo.bad; e["warning"] != null -> Sniffo.warn; else -> Sniffo.good }))
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("${e["name"]} · $state" + (e["channel"]?.let { " · ch $it" } ?: ""), style = MaterialTheme.typography.bodyMedium)
                            Text(listOfNotNull(e["packets"]?.let { "$it packets" }, e["pps"]?.let { "$it/s" },
                                (e["error"] ?: e["warning"])?.toString()).joinToString(" · "), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                if (hub.status.snapshot().isEmpty()) Text("No sources running: plug in an adapter or turn on a phone source, then restart sensor mode.",
                    style = MaterialTheme.typography.bodySmall)
            }
        }

        SectionTitle("Keep it running")
        Column(Modifier.padding(horizontal = 12.dp)) {
            val pm = context.getSystemService(PowerManager::class.java)
            val exempt = pm?.isIgnoringBatteryOptimizations(context.packageName) == true
            Text(if (exempt) "Battery optimisation is off for this app: Android won't pause sensor mode." else
                "Android may pause the app to save battery. For a sensor that runs for days, turn battery optimisation off for it.",
                style = MaterialTheme.typography.bodySmall)
            if (!exempt) TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } }) {
                Text("Battery settings")
            }
        }

        SectionTitle("Log")
        @Suppress("UNUSED_EXPRESSION") tick
        Column(Modifier.padding(horizontal = 12.dp)) {
            val lines = hub.logs.recent(40)
            if (lines.isEmpty()) Text("Nothing yet.", style = MaterialTheme.typography.bodySmall)
            lines.asReversed().forEach { l ->
                Text("${l["level"]} ${l["msg"]}", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace,
                    color = if (l["level"] == "WARNING" || l["level"] == "ERROR") Sniffo.warn else Color.Unspecified)
            }
        }
        HorizontalDivider(Modifier.padding(top = 12.dp))
    }
}

@Composable
private fun SourceRow(title: String, detail: String, checked: Boolean, enabled: Boolean, indent: Boolean = false, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(start = if (indent) 16.dp else 0.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked, onChange, enabled = enabled)
    }
}
