package com.example.banaanscan.ui

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DeviceUnknown
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Tablet
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.banaanscan.data.DeviceType
import com.example.banaanscan.data.Guess

@Composable
fun BanaanScanTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 -> if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

fun DeviceType.icon(): ImageVector = when (this) {
    DeviceType.ROUTER -> Icons.Filled.Router
    DeviceType.PHONE -> Icons.Filled.Smartphone
    DeviceType.TABLET -> Icons.Filled.Tablet
    DeviceType.COMPUTER -> Icons.Filled.Computer
    DeviceType.SERVER -> Icons.Filled.Storage
    DeviceType.PRINTER -> Icons.Filled.Print
    DeviceType.TV -> Icons.Filled.Tv
    DeviceType.STREAMER -> Icons.Filled.Cast
    DeviceType.SPEAKER -> Icons.Filled.Speaker
    DeviceType.HEADPHONES -> Icons.Filled.Headphones
    DeviceType.CAMERA -> Icons.Filled.Videocam
    DeviceType.SMART_HOME -> Icons.Filled.Lightbulb
    DeviceType.GAME_CONSOLE -> Icons.Filled.SportsEsports
    DeviceType.WEARABLE -> Icons.Filled.Watch
    DeviceType.TRACKER -> Icons.Filled.LocationOn
    DeviceType.INPUT -> Icons.Filled.Keyboard
    DeviceType.UNKNOWN -> Icons.Filled.DeviceUnknown
}

@Composable
fun PermissionBanner(onGrant: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text("Permissions needed", fontWeight = FontWeight.Bold)
            Text(
                "Android requires location and nearby-devices permissions to list Wi-Fi networks " +
                    "and Bluetooth devices. Nothing leaves your phone.",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onGrant, modifier = Modifier.padding(top = 8.dp)) { Text("Grant") }
        }
    }
}

@Composable
fun RssiText(rssi: Int?) {
    if (rssi == null) return
    val color = when {
        rssi >= -55 -> Color(0xFF2E7D32)
        rssi >= -70 -> Color(0xFF9E9D24)
        rssi >= -80 -> Color(0xFFEF6C00)
        else -> Color(0xFFC62828)
    }
    Text("$rssi dBm", color = color, style = MaterialTheme.typography.labelMedium)
}

@Composable
fun Tag(text: String) {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(end = 4.dp, top = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

@Composable
fun DetailDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        title = { Text(title) },
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp), content = content)
            }
        },
    )
}

@Composable
fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(96.dp))
        Text(value, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
}

@Composable
fun GuessSection(guess: Guess) {
    SectionTitle("Device type guess")
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.material3.Icon(guess.type.icon(), contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text("${guess.type.label} · ${guess.confidence}% confidence", fontWeight = FontWeight.SemiBold)
    }
    guess.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
}

fun ageText(ts: Long): String {
    val s = (System.currentTimeMillis() - ts) / 1000
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

fun Guess.short(): String = if (type == DeviceType.UNKNOWN) type.label else "${type.label} $confidence%"
