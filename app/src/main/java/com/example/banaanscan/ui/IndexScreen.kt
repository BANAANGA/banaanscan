package com.example.banaanscan.ui

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.banaanscan.MainViewModel
import com.example.banaanscan.data.DeviceIndex

@Composable
fun IndexScreen(vm: MainViewModel) {
    val context = LocalContext.current
    val entries by vm.index.entries.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf<DeviceIndex.Kind?>(null) }
    var confirmClear by remember { mutableStateOf(false) }

    val shown = entries.filter { e ->
        (kind == null || e.kind == kind) &&
            (query.isBlank() || listOfNotNull(e.name, e.address, e.ip, e.vendor, e.type.label, e.network)
                .any { it.contains(query, ignoreCase = true) })
    }

    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("Search name, MAC, IP, vendor, type") },
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = {
                val send = Intent(Intent.ACTION_SEND).apply {
                    type = "text/csv"
                    putExtra(Intent.EXTRA_SUBJECT, "BanaanScan device index")
                    putExtra(Intent.EXTRA_TEXT, vm.index.toCsv())
                }
                context.startActivity(Intent.createChooser(send, "Export index"))
            }) { Icon(Icons.Filled.Share, contentDescription = "Export CSV") }
            IconButton(onClick = { confirmClear = true }) { Icon(Icons.Filled.Delete, contentDescription = "Clear index") }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = kind == null, onClick = { kind = null }, label = { Text("All ${entries.size}") })
            DeviceIndex.Kind.entries.forEach { k ->
                FilterChip(selected = kind == k, onClick = { kind = k },
                    label = { Text("${k.label} ${entries.count { it.kind == k }}") })
            }
        }
        HorizontalDivider()
        LazyColumn {
            items(shown, key = { it.key }) { e ->
                ListItem(
                    leadingContent = { Icon(e.type.icon(), contentDescription = e.type.label) },
                    headlineContent = { Text(e.name.ifBlank { e.address ?: e.ip ?: "?" }) },
                    supportingContent = {
                        Column {
                            Text(listOfNotNull(e.address, e.ip, e.vendor).joinToString(" · "),
                                style = MaterialTheme.typography.bodySmall)
                            FlowRow {
                                Tag(e.kind.label)
                                Tag(e.type.label)
                                e.network?.takeIf { e.kind == DeviceIndex.Kind.LAN }?.let { Tag(it) }
                                Tag("seen ${e.sightings}×")
                            }
                            e.details?.let { Text(it, style = MaterialTheme.typography.labelSmall) }
                        }
                    },
                    trailingContent = { Text(ageText(e.lastSeen), style = MaterialTheme.typography.labelSmall) },
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear device index?") },
            text = { Text("This removes all ${entries.size} remembered devices.") },
            confirmButton = { TextButton(onClick = { vm.index.clear(); confirmClear = false }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
}
