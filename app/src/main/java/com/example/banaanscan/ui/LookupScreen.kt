package com.example.banaanscan.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.data.OuiDatabase

@Composable
fun LookupScreen(vm: MainViewModel) {
    val ouiStatus by vm.ouiStatus.collectAsStateWithLifecycle()
    val busy by vm.ouiBusy.collectAsStateWithLifecycle()
    // Re-read the index so lookups also show what we already know about a MAC.
    val entries by vm.index.entries.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    Column(Modifier.padding(12.dp).verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("MAC address or prefix") },
            placeholder = { Text("e.g. 3C:22:FB:12:34:56") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        val hex = OuiDatabase.normalize(input)
        if (hex.length >= 6) {
            val vendor = OuiDatabase.lookup(hex)
            val known = entries.filter { e -> e.address?.let { OuiDatabase.normalize(it).startsWith(hex) } == true }
            Card(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                Column(Modifier.padding(12.dp)) {
                    Text(vendor ?: "No vendor registered for this prefix", fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium)
                    InfoRow("Normalized", OuiDatabase.format(hex))
                    InfoRow("OUI", OuiDatabase.format(hex.take(6)))
                    InfoRow("Admin", if (OuiDatabase.isLocallyAdministered(hex)) "Locally administered – randomized/private or virtual"
                        else "Universally administered (vendor-assigned)")
                    InfoRow("Cast", if (OuiDatabase.isMulticast(hex)) "Multicast / group address" else "Unicast")
                    if (vendor == null && OuiDatabase.isLocallyAdministered(hex)) {
                        Text("Randomized MACs don't belong to a vendor, so they can't be looked up.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                    if (known.isNotEmpty()) {
                        SectionTitle("Seen before")
                        known.take(10).forEach { e ->
                            Text("${e.name.ifBlank { e.address }} · ${e.kind.label} · ${e.type.label} · ${ageText(e.lastSeen)}",
                                style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        } else if (input.isNotBlank()) {
            Text("Enter at least 6 hex digits.", Modifier.padding(top = 8.dp), style = MaterialTheme.typography.bodySmall)
        }

        Card(Modifier.fillMaxWidth().padding(top = 24.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text("Vendor database", fontWeight = FontWeight.Bold)
                Text(ouiStatus, style = MaterialTheme.typography.bodySmall)
                Text("Source: ${OuiDatabase.SOURCE_URL} (IEEE MA-L / MA-M / MA-S registries, via Wireshark)",
                    style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(top = 4.dp))
                Button(onClick = vm::updateOui, enabled = !busy, modifier = Modifier.padding(top = 8.dp)) {
                    Text(if (busy) "Downloading…" else "Update now")
                }
            }
        }
    }
}
