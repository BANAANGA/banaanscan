package io.github.banaanga.sniffowireless.mobile.ui

import android.content.Intent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.data.DeviceIndex
import io.github.banaanga.sniffowireless.mobile.data.DeviceType
import io.github.banaanga.sniffowireless.mobile.data.OuiDatabase

@Composable
private fun DeviceIndex.Kind.color(): Color {
    val p = Sniffo.c
    return when (this) {
        DeviceIndex.Kind.LAN -> p.lan
        DeviceIndex.Kind.BLUETOOTH -> p.ble
        DeviceIndex.Kind.WIFI -> p.ap
        DeviceIndex.Kind.ZIGBEE, DeviceIndex.Kind.RADIO -> p.zigbee
    }
}

private val DeviceIndex.Kind.short: String
    get() = when (this) {
        DeviceIndex.Kind.LAN -> "LAN"
        DeviceIndex.Kind.BLUETOOTH -> "Bluetooth"
        DeviceIndex.Kind.WIFI -> "AP"
        DeviceIndex.Kind.ZIGBEE -> "Zigbee"
        DeviceIndex.Kind.RADIO -> "802.15.4"
    }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun IndexScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val entries by vm.index.entries.collectAsStateWithLifecycle()
    val ouiStatus by vm.ouiStatus.collectAsStateWithLifecycle()
    val ouiBusy by vm.ouiBusy.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<DeviceIndex.Kind?>(null) }
    var hideRandom by rememberSaveable { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }

    val shown = entries.filter { e ->
        (kind == null || e.kind == kind) &&
            (!hideRandom || e.address?.let(OuiDatabase::isLocallyAdministered) != true) &&
            (query.isBlank() || listOfNotNull(e.name, e.address, e.ip, e.vendor, e.type.label, e.network)
                .any { it.contains(query, ignoreCase = true) })
    }

    LazyColumn {
        item(key = "filters") { Column {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Btn("Export CSV", {
                        val send = Intent(Intent.ACTION_SEND).apply {
                            type = "text/csv"
                            putExtra(Intent.EXTRA_SUBJECT, "sniffowireless-mobile devices")
                            putExtra(Intent.EXTRA_TEXT, vm.index.toCsv())
                        }
                        context.startActivity(Intent.createChooser(send, "Export devices"))
                    })
                    Btn("Clear", { confirmClear = true }, enabled = entries.isNotEmpty())
                }
                Field(query, { query = it }, "Search MAC, name, IP, vendor, type")
                Seg(listOf<Pair<DeviceIndex.Kind?, String>>(null to "All ${entries.size}") +
                        DeviceIndex.Kind.entries.map { k -> k to "${k.short} ${entries.count { it.kind == k }}" },
                    kind, { kind = it }, Modifier.horizontalScroll(rememberScrollState()))
                Check("Hide randomized MACs", hideRandom, { hideRandom = it })
            }
            Spacer(Modifier.height(8.dp))
            ListHead("${shown.size} device" + (if (shown.size == 1) "" else "s"), "Last seen")
        } }
        items(shown, key = { it.key }) { e ->
            val random = e.address?.let(OuiDatabase::isLocallyAdministered) == true
            DeviceRow(
                title = e.name.ifBlank { "—" },
                strong = e.name.isNotBlank(),
                kindColor = e.kind.color(),
                kind = e.kind.short,
                address = e.address ?: e.ip,
                identified = listOfNotNull(e.vendor, e.type.label.takeIf { e.type != DeviceType.UNKNOWN },
                    e.ip?.takeIf { e.address != null }).joinToString(" · "),
                note = e.details,
                trailing = { Text(ageText(e.lastSeen), style = Mono, color = Sniffo.c.muted) },
                tags = {
                    if (random) Tag("rand", Tone.WARN)
                    e.network?.takeIf { e.kind == DeviceIndex.Kind.LAN }?.let { Tag(it) }
                    Tag("seen ${e.sightings}×")
                },
            )
        }
        item(key = "oui") {
            Panel(Modifier.padding(top = 12.dp, bottom = 12.dp), title = "Vendor database",
                action = { Btn(if (ouiBusy) "Downloading…" else "Update", vm::updateOui, enabled = !ouiBusy) }) {
                Text(ouiStatus, style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                Muted("IEEE MA-L / MA-M / MA-S registries, via Wireshark's manuf file.", Modifier.padding(top = 2.dp))
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = Sniffo.c.panel,
            title = { Text("Clear all devices?") },
            text = { Text("This removes all ${entries.size} remembered devices.") },
            confirmButton = { Btn("Clear", { vm.index.clear(); confirmClear = false }) },
            dismissButton = { Btn("Cancel", { confirmClear = false }) },
        )
    }
}
