package com.example.banaanscan.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsInputAntenna
import com.example.banaanscan.zigbee.ChannelActivity
import com.example.banaanscan.zigbee.SniffedDevice

private fun frames(n: Int) = "$n frame" + if (n == 1) "" else "s"

@Composable
fun SnifferControls(channel: Int?, enabled: Boolean, onChannel: (Int?) -> Unit) {
    Column(Modifier.padding(horizontal = 12.dp)) {
        Text(
            "Listens to all IEEE 802.15.4 traffic and never transmits. Encrypted contents stay hidden: you see " +
                "addresses, frame types and timing. Only use it on systems you own or are authorised to test.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            FilterChip(selected = channel == null, enabled = enabled, onClick = { onChannel(null) }, label = { Text("All channels") })
            for (c in 11..26) {
                FilterChip(selected = channel == c, enabled = enabled, onClick = { onChannel(c) }, label = { Text("$c") })
            }
        }
        Text(
            "Battery devices only transmit briefly, so staying on one channel catches them better than hopping.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SnifferActivity(channels: List<ChannelActivity>, current: Int?, total: Int, undecoded: Int) {
    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
        SectionTitle("Frames per channel")
        Text(
            frames(total) + " heard" + if (undecoded > 0) " · $undecoded couldn't be decoded" else "",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val busiest = channels.maxOfOrNull { it.frames }?.coerceAtLeast(1) ?: 1
        channels.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.channel.toString(),
                    Modifier.width(28.dp),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (c.channel == current) FontWeight.Bold else FontWeight.Normal,
                )
                LinearProgressIndicator(
                    progress = { c.frames.toFloat() / busiest },
                    modifier = Modifier.weight(1f),
                    drawStopIndicator = {},
                )
                Text("${c.frames}", Modifier.padding(start = 8.dp).width(48.dp), style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun SniffedDeviceRow(d: SniffedDevice, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(Icons.Filled.SettingsInputAntenna, contentDescription = null) },
        headlineContent = { Text(d.address.text) },
        supportingContent = {
            Column {
                Text(
                    listOfNotNull(d.pan?.let { "PAN 0x%04X".format(it) }, d.possibleVendor?.let { "$it?" }).joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                )
                FlowRow {
                    Tag(d.hint.label)
                    Tag("ch " + d.channels.sorted().joinToString(","))
                    Tag(frames(d.framesSent + d.framesReceived))
                }
            }
        },
        trailingContent = { RssiText(d.rssi) },
    )
}

@Composable
fun SniffedDeviceDetail(d: SniffedDevice, onDismiss: () -> Unit) {
    DetailDialog(d.address.text, onDismiss) {
        InfoRow("PAN", d.pan?.let { "0x%04X".format(it) } ?: "Not in the frame")
        InfoRow("Maybe made by", d.possibleVendor)
        InfoRow("Channels", d.channels.sorted().joinToString(", "))
        InfoRow("Sent", frames(d.framesSent))
        InfoRow("Received", frames(d.framesReceived))
        InfoRow("Encrypted", "${d.encryptedFrames} of ${frames(d.framesSent)} sent")
        InfoRow("Frame types", d.types.joinToString(", ") { it.label })
        InfoRow("Signal", d.rssi?.let { "$it dBm" } ?: "Never heard transmitting")
        InfoRow("LQI", d.lqi?.let { "$it / 255" })
        InfoRow("First seen", ageText(d.firstSeen))
        InfoRow("Last seen", ageText(d.lastSeen))
        SectionTitle("Protocol")
        Text(d.hint.label, fontWeight = FontWeight.SemiBold)
        Text(
            "A best guess from the unencrypted parts of the frames, not proof. Different systems can look alike.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
