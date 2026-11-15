package io.github.banaanga.sniffowireless.mobile.ui

import android.bluetooth.BluetoothAdapter
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import io.github.banaanga.sniffowireless.mobile.classify.DeviceClassifier
import io.github.banaanga.sniffowireless.mobile.data.BtDevice
import io.github.banaanga.sniffowireless.mobile.data.DeviceType

@Composable
fun BluetoothScreen(vm: MainViewModel) {
    var usbAdapter by rememberSaveable { mutableStateOf(false) }
    Column {
        SourceSeg(usbAdapter) { usbAdapter = it }
        if (usbAdapter) UsbBluetooth(vm) else PhoneBluetooth(vm)
    }
}

@Composable
private fun PhoneBluetooth(vm: MainViewModel) {
    val devices by vm.bt.devices.collectAsStateWithLifecycle()
    val scanning by vm.bt.scanning.collectAsStateWithLifecycle()
    val error by vm.bt.error.collectAsStateWithLifecycle()
    var namedOnly by rememberSaveable { mutableStateOf(false) }
    var enabled by remember { mutableStateOf(vm.bt.isEnabled) }
    val enableLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        enabled = vm.bt.isEnabled
        if (enabled) vm.bt.start()
    }

    // Scanning drains the battery; stop when the user leaves this tab.
    DisposableEffect(Unit) { onDispose { vm.bt.stop() } }

    BtList(devices, namedOnly, summary = null) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Btn(if (scanning) "Stop" else "Scan", { if (scanning) vm.bt.stop() else vm.bt.start() })
            Btn("Clear", { vm.bt.clear() })
            Spacer(Modifier.weight(1f))
            Check("Named only", namedOnly, { namedOnly = it })
        }
        if (scanning) StatusLine("Scanning", Tone.ON, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        if (!vm.bt.isAvailable) {
            StatusLine("This device has no Bluetooth.", Tone.BAD, Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
        } else if (!enabled) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusLine("Bluetooth is off.", Tone.BAD, Modifier.weight(1f))
                Btn("Turn on", { runCatching { enableLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)) } })
            }
        }
        error?.let { StatusLine(it, Tone.BAD, Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) }
    }
}

/** Bluetooth through a USB adapter, driven by sniffowireless-mobile itself: sees every advertising packet, long range too. */
@Composable
private fun UsbBluetooth(vm: MainViewModel) {
    val usb = vm.btUsb
    val adapter by usb.adapter.collectAsStateWithLifecycle()
    val running by usb.running.collectAsStateWithLifecycle()
    val status by usb.status.collectAsStateWithLifecycle()
    val error by usb.error.collectAsStateWithLifecycle()
    val packets by usb.packets.collectAsStateWithLifecycle()
    val devices by usb.devices.collectAsStateWithLifecycle()
    var passive by rememberSaveable { mutableStateOf(false) }
    var namedOnly by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { usb.refresh() }
    DisposableEffect(Unit) { onDispose { vm.stopBtUsb() } }

    BtList(devices, namedOnly, summary = if (packets > 0) "%,d packets".format(packets) else null) {
        Panel(
            title = "Adapter",
            action = {
                when {
                    running -> Btn("Stop", vm::stopBtUsb)
                    adapter != null -> Btn("Scan", { vm.startBtUsb(passive) })
                }
            },
        ) {
            StatusLine(adapter?.let { "${it.label} connected" } ?: "No adapter plugged in", if (adapter != null) Tone.ON else Tone.NONE,
                Modifier)
            Muted(
                when {
                    adapter == null -> "Connect a USB Bluetooth adapter with a USB-C cable or OTG adapter. sniffowireless-mobile drives it itself, without root; the phone's own Bluetooth isn't used."
                    passive -> "Listen only: nothing is transmitted, so names only appear when devices advertise them."
                    else -> "Asks devices for their names (from a random address) and searches for classic devices too."
                },
                Modifier.padding(top = 4.dp),
            )
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Check("Listen only", passive, { passive = it }, enabled = !running)
                Spacer(Modifier.width(16.dp))
                Check("Named only", namedOnly, { namedOnly = it })
                Spacer(Modifier.weight(1f))
                Btn("Clear", usb::clear, enabled = !running)
            }
        }
        if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp), color = Sniffo.c.ble, trackColor = Sniffo.c.panel2)
        status?.let { StatusLine(it, if (running) Tone.ON else Tone.NONE) }
        error?.let { StatusLine(it, Tone.BAD) }
    }
}

@Composable
private fun BtList(devices: Map<String, BtDevice>, namedOnly: Boolean, summary: String?, header: @Composable ColumnScope.() -> Unit) {
    var selected by remember { mutableStateOf<BtDevice?>(null) }
    val shown = devices.values
        .filter { !namedOnly || it.name != null }
        .sortedByDescending { it.rssi ?: -200 }

    LazyColumn {
        item(key = "header") {
            Column {
                header()
                Spacer(Modifier.height(6.dp))
                ListHead(listOfNotNull("${shown.size} device" + (if (shown.size == 1) "" else "s"), summary).joinToString(" · "), "Signal")
            }
        }
        items(shown, key = { it.address }) { d -> BtRow(d) { selected = d } }
    }

    selected?.let { sel ->
        val d = devices[sel.address] ?: sel
        DetailDialog(d.name ?: "Unnamed device", onDismiss = { selected = null }) {
            InfoRow("Address", d.address)
            InfoRow("Vendor", vendorText(d))
            InfoRow("Signal", d.rssi?.let { "$it dBm" })
            InfoRow("TX power", d.txPower?.let { "$it dBm" })
            InfoRow("Transport", listOfNotNull("BLE".takeIf { d.le }, "Classic".takeIf { d.classic }).joinToString(" + "))
            InfoRow("Radio", d.phys.takeIf { it.isNotEmpty() }?.joinToString(" + ") { if (it == "Coded") "Coded (long range)" else it })
            InfoRow("Packets", d.packets.takeIf { it > 0 }?.let { "%,d".format(it) })
            InfoRow("Paired", if (d.bonded) "Yes" else null)
            InfoRow("Connectable", d.connectable?.toString())
            InfoRow("Class", d.deviceClass?.let { "0x%04X (major 0x%04X)".format(it, d.majorClass ?: 0) })
            if (d.manufacturerData.isNotEmpty()) {
                H2("Manufacturer data", Modifier.padding(top = 10.dp, bottom = 2.dp))
                d.manufacturerData.forEach { (id, hex) ->
                    InfoRow(DeviceClassifier.BLE_COMPANIES[id] ?: "0x%04X".format(id), hex.take(48))
                }
            }
            if (d.serviceUuids.isNotEmpty()) {
                H2("Services", Modifier.padding(top = 10.dp, bottom = 2.dp))
                d.serviceUuids.forEach { Text(it, style = Mono) }
            }
            GuessSection(d.guess)
            InfoRow("Last seen", ageText(d.lastSeen))
        }
    }
}

private fun vendorText(d: BtDevice): String = when {
    d.vendorReliable && d.vendor != null -> d.vendor
    d.manufacturerData.isNotEmpty() ->
        d.manufacturerData.keys.joinToString { DeviceClassifier.BLE_COMPANIES[it] ?: "company 0x%04X".format(it) } + " (from BLE data)"
    d.randomAddress || (d.le && !d.classic) -> "Random / private BLE address"
    else -> d.vendor ?: "Unknown"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BtRow(d: BtDevice, onClick: () -> Unit) {
    val p = Sniffo.c
    val classicOnly = d.classic && !d.le
    DeviceRow(
        title = d.name ?: "—",
        strong = d.name != null,
        kindColor = if (classicOnly) p.btClassic else p.ble,
        kind = when {
            d.le && d.classic -> "BLE + classic"
            classicOnly -> "Bluetooth classic"
            else -> "BLE"
        },
        address = d.address,
        identified = listOfNotNull(vendorText(d), d.guess.short().takeIf { d.guess.type != DeviceType.UNKNOWN }).joinToString(" · "),
        onClick = onClick,
        trailing = { RssiText(d.rssi) },
        tags = if ("Coded" in d.phys || d.bonded || d.randomAddress) {
            {
                if ("Coded" in d.phys) Tag("long range")
                if (d.bonded) Tag("paired", Tone.ON)
                if (d.randomAddress) Tag("rand", Tone.WARN)
            }
        } else null,
    )
}
