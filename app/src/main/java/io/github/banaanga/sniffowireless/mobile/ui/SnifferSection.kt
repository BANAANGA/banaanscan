package io.github.banaanga.sniffowireless.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.banaanga.sniffowireless.mobile.zigbee.ChannelActivity
import io.github.banaanga.sniffowireless.mobile.zigbee.SniffedDevice

private fun frames(n: Int) = "$n frame" + if (n == 1) "" else "s"

@Composable
fun SnifferControls(channel: Int?, enabled: Boolean, onChannel: (Int?) -> Unit) {
    Column(Modifier.padding(horizontal = 16.dp)) {
        Muted(
            "Listens to all IEEE 802.15.4 traffic and never transmits. Encrypted contents stay hidden: you see " +
                "addresses, frame types and timing. Only use it on systems you own or are authorised to test.",
        )
        Seg(listOf<Pair<Int?, String>>(null to "All") + (11..26).map { it to "$it" }, channel, onChannel,
            Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), enabled = enabled)
        Muted("Battery devices only transmit briefly, so staying on one channel catches them better than hopping.")
    }
}

@Composable
fun SnifferActivity(channels: List<ChannelActivity>, current: Int?, total: Int, undecoded: Int) {
    Panel(Modifier.padding(top = 6.dp), title = "Frames per channel") {
        Muted(frames(total) + " heard" + if (undecoded > 0) " · $undecoded couldn't be decoded" else "", Modifier.padding(bottom = 6.dp))
        val busiest = channels.maxOfOrNull { it.frames }?.coerceAtLeast(1) ?: 1
        channels.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.channel.toString(), Modifier.width(28.dp), style = Mono,
                    color = if (c.channel == current) Sniffo.c.text else Sniffo.c.muted,
                    fontWeight = if (c.channel == current) FontWeight.Bold else FontWeight.Normal)
                Bar(c.frames.toFloat() / busiest, Sniffo.c.zigbee, Modifier.weight(1f))
                Text("${c.frames}", Modifier.padding(start = 8.dp).width(48.dp), style = Mono, color = Sniffo.c.muted)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SniffedDeviceRow(d: SniffedDevice, onClick: () -> Unit) {
    DeviceRow(
        title = d.hint.label,
        kindColor = Sniffo.c.zigbee,
        kind = "802.15.4",
        address = d.address.text,
        identified = listOfNotNull(d.pan?.let { "PAN 0x%04X".format(it) }, d.possibleVendor?.let { "$it?" }).joinToString(" · "),
        onClick = onClick,
        trailing = { RssiText(d.rssi) },
        tags = {
            Tag("ch " + d.channels.sorted().joinToString(","))
            Tag(frames(d.framesSent + d.framesReceived))
        },
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
        DetailTitle("Protocol")
        Text(d.hint.label, fontWeight = FontWeight.SemiBold)
        Muted("A best guess from the unencrypted parts of the frames, not proof. Different systems can look alike.")
    }
}
