package io.github.banaanga.sniffowireless.mobile.ui

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
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.banaanga.sniffowireless.mobile.data.DeviceType
import io.github.banaanga.sniffowireless.mobile.data.Guess

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system") }

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** sniffowireless's palette (static/style.css in sniffowireless): near-black panels, blue for Wi-Fi, amber for BLE. */
object Sniffo {
    val bg = Color(0xFF0B0C0E)
    val panel = Color(0xFF111316)
    val panel2 = Color(0xFF16181C)
    val border = Color(0xFF1F2227)
    val border2 = Color(0xFF2A2E35)
    val text = Color(0xFFE6E7E9)
    val muted = Color(0xFF8A8F98)
    val faint = Color(0xFF5B6068)
    val ap = Color(0xFF4CC2FF)
    val wifiClient = Color(0xFF3ECF8E)
    val ble = Color(0xFFF5B84A)
    val btClassic = Color(0xFFEF6F6C)
    val good = Color(0xFF3ECF8E)
    val warn = Color(0xFFF5B84A)
    val bad = Color(0xFFEF6F6C)
}

private val SniffoDark = darkColorScheme(
    primary = Sniffo.ap, onPrimary = Sniffo.bg, primaryContainer = Sniffo.panel2, onPrimaryContainer = Sniffo.ap,
    secondary = Sniffo.wifiClient, onSecondary = Sniffo.bg, secondaryContainer = Sniffo.panel2, onSecondaryContainer = Sniffo.text,
    tertiary = Sniffo.ble, onTertiary = Sniffo.bg, tertiaryContainer = Sniffo.panel2, onTertiaryContainer = Sniffo.ble,
    error = Sniffo.bad, onError = Sniffo.bg, errorContainer = Color(0xFF2A1414), onErrorContainer = Sniffo.bad,
    background = Sniffo.bg, onBackground = Sniffo.text, surface = Sniffo.bg, onSurface = Sniffo.text,
    surfaceVariant = Sniffo.panel2, onSurfaceVariant = Sniffo.muted, outline = Sniffo.border2, outlineVariant = Sniffo.border,
    surfaceContainerLowest = Sniffo.bg, surfaceContainerLow = Sniffo.panel, surfaceContainer = Sniffo.panel,
    surfaceContainerHigh = Sniffo.panel2, surfaceContainerHighest = Sniffo.panel2, inverseSurface = Sniffo.text,
    inverseOnSurface = Sniffo.bg, scrim = Color.Black,
)

private val SniffoLight = lightColorScheme(
    primary = Color(0xFF0A6EA8), onPrimary = Color.White, primaryContainer = Color(0xFFF2F2F2), onPrimaryContainer = Color(0xFF0A6EA8),
    secondary = Color(0xFF1C8A5A), tertiary = Color(0xFF9A6A10), error = Color(0xFFC0392B),
    background = Color.White, onBackground = Color(0xFF111111), surface = Color.White, onSurface = Color(0xFF111111),
    surfaceVariant = Color(0xFFF2F2F2), onSurfaceVariant = Color(0xFF555555), outline = Color(0xFFCCCCCC), outlineVariant = Color(0xFFDDDDDD),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color.White, surfaceContainer = Color(0xFFF7F7F7),
    surfaceContainerHigh = Color(0xFFF2F2F2), surfaceContainerHighest = Color(0xFFF2F2F2),
)

private val SniffoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(12.dp),
)

@Composable
fun SniffoTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (dark) SniffoDark else SniffoLight, shapes = SniffoShapes, content = content)
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
    val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val color = when {
        rssi >= -55 -> if (dark) Color(0xFF81C784) else Color(0xFF2E7D32)
        rssi >= -70 -> if (dark) Color(0xFFDCE775) else Color(0xFF9E9D24)
        rssi >= -80 -> if (dark) Color(0xFFFFB74D) else Color(0xFFEF6C00)
        else -> if (dark) Color(0xFFE57373) else Color(0xFFC62828)
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
