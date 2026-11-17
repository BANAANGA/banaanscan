package io.github.banaanga.sniffowireless.mobile.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.location.LocationManagerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.data.DeviceType
import io.github.banaanga.sniffowireless.mobile.data.WifiNetwork

/** Phone or USB adapter, as a segmented control at the top of the Wi-Fi and Bluetooth pages. */
@Composable
fun SourceSeg(usb: Boolean, onChange: (Boolean) -> Unit) {
    Seg(listOf(false to "Phone", true to "USB adapter"), usb, onChange, Modifier.padding(start = 16.dp, bottom = 6.dp))
}

@Composable
fun WifiScreen(vm: MainViewModel) {
    // The phone's own Wi-Fi can only scan; an MT7921U or AR9271 adapter adds real monitor mode.
    var usbAdapter by rememberSaveable { mutableStateOf(false) }
    Column {
        SourceSeg(usbAdapter) { usbAdapter = it }
        if (usbAdapter) UsbAdapterSection(vm) else PhoneWifiScan(vm)
    }
}

fun securityTone(security: String): Tone = when {
    security.equals("Open", true) || security.startsWith("OPN") -> Tone.BAD
    security.startsWith("WEP") -> Tone.BAD
    else -> Tone.NONE
}

@Composable
private fun PhoneWifiScan(vm: MainViewModel) {
    val context = LocalContext.current
    val results by vm.wifi.results.collectAsStateWithLifecycle()
    val status by vm.wifi.status.collectAsStateWithLifecycle()
    var band by rememberSaveable { mutableStateOf("All") }
    var selected by remember { mutableStateOf<WifiNetwork?>(null) }
    val locationOn = remember {
        context.getSystemService(android.location.LocationManager::class.java)
            ?.let { LocationManagerCompat.isLocationEnabled(it) } ?: true
    }

    LaunchedEffect(Unit) { vm.wifi.scan() }

    val shown = results.filter { band == "All" || it.band == band }
    LazyColumn {
        item(key = "controls") {
            Column {
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Seg(listOf("All" to "All", "2.4 GHz" to "2.4", "5 GHz" to "5", "6 GHz" to "6 GHz"), band, { band = it })
                    Spacer(Modifier.weight(1f))
                    Btn("Scan", { vm.wifi.scan() }, Modifier.padding(start = 8.dp), icon = Icons.Filled.Refresh)
                }
                if (!locationOn) {
                    Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        StatusLine("Location services are off: Android hides Wi-Fi results.", Tone.BAD, Modifier.weight(1f))
                        Btn("Settings", { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) })
                    }
                }
                status?.let { StatusLine(it, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
                ChannelUsage(shown)
                ListHead("${shown.size} access point" + if (shown.size == 1) "" else "s", "Signal")
            }
        }
        items(shown, key = { it.bssid + it.frequency }) { n -> WifiRow(n) { selected = n } }
    }

    selected?.let { n ->
        DetailDialog(n.ssid.ifBlank { "Hidden network" }, onDismiss = { selected = null }) {
            InfoRow("BSSID", n.bssid)
            InfoRow("Vendor", n.vendor ?: if (n.randomizedBssid) "Locally administered (virtual / hotspot)" else "Unknown")
            InfoRow("Signal", "${n.rssi} dBm")
            InfoRow("Frequency", "${n.frequency} MHz (${n.band}, channel ${n.channel})")
            InfoRow("Width", n.channelWidthMhz?.let { "$it MHz" })
            InfoRow("Standard", n.standard)
            InfoRow("Security", n.security)
            InfoRow("Connected", if (n.isConnected) "Yes" else null)
            GuessSection(n.guess)
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChannelUsage(networks: List<WifiNetwork>) {
    if (networks.isEmpty()) return
    val byChannel = networks.groupBy { it.band to it.channel }.toSortedMap(compareBy({ it.first }, { it.second }))
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        H2("Channels")
        FlowRow { byChannel.forEach { (key, list) -> Tag("${key.second} × ${list.size}") } }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WifiRow(n: WifiNetwork, onClick: () -> Unit) {
    val vendor = n.vendor ?: if (n.randomizedBssid) "Private / virtual BSSID" else null
    DeviceRow(
        title = n.ssid.ifBlank { "‹hidden›" },
        strong = n.ssid.isNotBlank(),
        kindColor = Sniffo.c.ap,
        kind = "AP",
        address = n.bssid,
        live = n.isConnected,
        identified = listOfNotNull(vendor, n.guess.type.label.takeIf { n.guess.type != DeviceType.UNKNOWN }).joinToString(" · "),
        onClick = onClick,
        trailing = { RssiText(n.rssi) },
        tags = {
            Tag(n.security, securityTone(n.security))
            Tag(n.band)
            Tag("ch ${n.channel}")
            n.standard?.let { Tag(it) }
            if (n.isConnected) Tag("connected", Tone.ON)
        },
    )
}

/** Text in the muted colour, for counts and notes between controls. */
@Composable
fun Muted(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = Sniffo.c.muted)
}
