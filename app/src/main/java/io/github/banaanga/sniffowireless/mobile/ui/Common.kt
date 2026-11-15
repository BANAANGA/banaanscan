package io.github.banaanga.sniffowireless.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.FlowRowScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Check
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.github.banaanga.sniffowireless.mobile.data.DeviceType
import io.github.banaanga.sniffowireless.mobile.data.Guess

enum class ThemeMode(val label: String) { DARK("Dark"), LIGHT("Light"), SYSTEM("Follow system") }

@Composable
fun ThemeMode.isDark(): Boolean = when (this) {
    ThemeMode.DARK -> true
    ThemeMode.LIGHT -> false
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
}

/** sniffowireless's colours (static/style.css there). The light set follows the same roles. */
data class Palette(
    val bg: Color, val panel: Color, val panel2: Color, val border: Color, val border2: Color,
    val text: Color, val muted: Color, val faint: Color,
    val ap: Color, val wifiClient: Color, val ble: Color, val btClassic: Color, val lan: Color, val zigbee: Color,
    val good: Color, val warn: Color, val bad: Color,
)

val DarkPalette = Palette(
    bg = Color(0xFF0B0C0E), panel = Color(0xFF111316), panel2 = Color(0xFF16181C), border = Color(0xFF1F2227), border2 = Color(0xFF2A2E35),
    text = Color(0xFFE6E7E9), muted = Color(0xFF8A8F98), faint = Color(0xFF5B6068),
    ap = Color(0xFF4CC2FF), wifiClient = Color(0xFF3ECF8E), ble = Color(0xFFF5B84A), btClassic = Color(0xFFEF6F6C),
    lan = Color(0xFFA8B3C2), zigbee = Color(0xFFFF9F6B),
    good = Color(0xFF3ECF8E), warn = Color(0xFFF5B84A), bad = Color(0xFFEF6F6C),
)

val LightPalette = Palette(
    bg = Color(0xFFF6F7F9), panel = Color(0xFFFFFFFF), panel2 = Color(0xFFEFF1F4), border = Color(0xFFE2E4E8), border2 = Color(0xFFD0D3D9),
    text = Color(0xFF16181C), muted = Color(0xFF5B6068), faint = Color(0xFF8A8F98),
    ap = Color(0xFF0A7BC2), wifiClient = Color(0xFF1C9A63), ble = Color(0xFFB7791F), btClassic = Color(0xFFC94B48),
    lan = Color(0xFF66707E), zigbee = Color(0xFFD9692F),
    good = Color(0xFF1C9A63), warn = Color(0xFFB7791F), bad = Color(0xFFC94B48),
)

private val LocalPalette = staticCompositionLocalOf { DarkPalette }

object Sniffo {
    /** The current theme's colours. */
    val c: Palette
        @Composable @ReadOnlyComposable get() = LocalPalette.current
}

private fun scheme(p: Palette, dark: Boolean) = (if (dark) darkColorScheme() else lightColorScheme()).copy(
    // sniffowireless is monochrome: controls are drawn in the text colour, colour is kept for device types and states.
    primary = p.text, onPrimary = p.bg, primaryContainer = p.panel2, onPrimaryContainer = p.text,
    secondary = p.muted, onSecondary = p.bg, secondaryContainer = p.panel2, onSecondaryContainer = p.text,
    tertiary = p.ap, onTertiary = p.bg, tertiaryContainer = p.panel2, onTertiaryContainer = p.ap,
    error = p.bad, onError = p.bg, errorContainer = p.panel, onErrorContainer = p.bad,
    background = p.bg, onBackground = p.text, surface = p.bg, onSurface = p.text,
    surfaceVariant = p.panel2, onSurfaceVariant = p.muted, outline = p.border2, outlineVariant = p.border,
    surfaceContainerLowest = p.bg, surfaceContainerLow = p.panel, surfaceContainer = p.panel,
    surfaceContainerHigh = p.panel, surfaceContainerHighest = p.panel2, inverseSurface = p.text,
    inverseOnSurface = p.bg, scrim = Color.Black, surfaceTint = Color.Transparent,
)

private val SniffoShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp), small = RoundedCornerShape(6.dp), medium = RoundedCornerShape(6.dp),
    large = RoundedCornerShape(8.dp), extraLarge = RoundedCornerShape(10.dp),
)

/** sniffowireless's type scale: 13 px body text, 18 px page titles, small uppercase section titles. */
private val SniffoType = Typography().run {
    copy(
        titleLarge = titleLarge.copy(fontSize = 18.sp, lineHeight = 24.sp, fontWeight = FontWeight.SemiBold, letterSpacing = (-0.01).em),
        titleMedium = titleMedium.copy(fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold),
        titleSmall = titleSmall.copy(fontSize = 11.sp, lineHeight = 16.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.06.em),
        bodyLarge = bodyLarge.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodyMedium = bodyMedium.copy(fontSize = 13.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
        bodySmall = bodySmall.copy(fontSize = 12.sp, lineHeight = 17.sp, letterSpacing = 0.sp),
        labelLarge = labelLarge.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelMedium = labelMedium.copy(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.sp),
        labelSmall = labelSmall.copy(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.sp),
    )
}

@Composable
fun SniffoTheme(dark: Boolean, content: @Composable () -> Unit) {
    val p = if (dark) DarkPalette else LightPalette
    CompositionLocalProvider(LocalPalette provides p) {
        MaterialTheme(colorScheme = scheme(p, dark), shapes = SniffoShapes, typography = SniffoType, content = content)
    }
}

private val Radius = RoundedCornerShape(6.dp)
val Mono: TextStyle @Composable get() = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)

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

// ---------------------------------------------------------------- building blocks, after sniffowireless's CSS classes

/** The page title and the line under it (`.page-head`). */
@Composable
fun PageHead(title: String, sub: String?, actions: @Composable RowScope.() -> Unit = {}) {
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 14.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = Sniffo.c.muted, modifier = Modifier.padding(top = 2.dp)) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = actions)
    }
}

/** A small uppercase title (`h2`). */
@Composable
fun H2(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), modifier, style = MaterialTheme.typography.titleSmall, color = Sniffo.c.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
fun SectionTitle(text: String) {
    H2(text, Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 6.dp))
}

/** A bordered panel with an optional header (`.panel`). */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    title: String? = null,
    action: @Composable (RowScope.() -> Unit)? = null,
    padded: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Sniffo.c
    Column(
        modifier.padding(horizontal = 12.dp, vertical = 6.dp).fillMaxWidth()
            .border(1.dp, p.border, Radius).clip(Radius).background(p.panel),
    ) {
        if (title != null || action != null) {
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                if (title != null) H2(title, Modifier.weight(1f)) else Spacer(Modifier.weight(1f))
                action?.let { Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, content = it) }
            }
            HorizontalDivider(color = p.border)
        }
        Column(if (padded) Modifier.padding(14.dp) else Modifier, content = content)
    }
}

/** A button (`button.btn`): panel-coloured with a thin border. */
@Composable
fun Btn(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null) {
    val p = Sniffo.c
    Row(
        modifier.alpha(if (enabled) 1f else 0.4f).clip(Radius).background(p.panel).border(1.dp, p.border2, Radius)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        icon?.let { Icon(it, null, Modifier.padding(end = 6.dp).size(16.dp), tint = p.text) }
        Text(text, style = MaterialTheme.typography.labelLarge, color = p.text, maxLines = 1)
    }
}

/** A segmented control (`.seg`). */
@Composable
fun <T> Seg(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = Sniffo.c
    Row(modifier.height(IntrinsicSize.Min).alpha(if (enabled) 1f else 0.5f).border(1.dp, p.border, Radius).clip(Radius)) {
        options.forEachIndexed { i, (value, label) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(p.border))
            val on = value == selected
            Text(
                label,
                Modifier.background(if (on) p.panel2 else Color.Transparent)
                    .clickable(enabled = enabled, role = Role.RadioButton) { onSelect(value) }
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                style = MaterialTheme.typography.labelMedium, color = if (on) p.text else p.muted, maxLines = 1,
            )
        }
    }
}

/** A checkbox with its label (`label.check`). */
@Composable
fun Check(label: String, checked: Boolean, onChange: (Boolean) -> Unit, enabled: Boolean = true) {
    val p = Sniffo.c
    val box = RoundedCornerShape(3.dp)
    Row(
        Modifier.alpha(if (enabled) 1f else 0.5f).clip(Radius).toggleable(checked, enabled, Role.Checkbox, onChange)
            .padding(horizontal = 2.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(14.dp).clip(box).background(if (checked) p.text else Color.Transparent).border(1.dp, if (checked) p.text else p.border2, box),
            contentAlignment = Alignment.Center,
        ) { if (checked) Icon(Icons.Filled.Check, null, Modifier.size(12.dp), tint = p.bg) }
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodyMedium, color = p.muted)
    }
}

/** A text field (`input`). */
@Composable
fun Field(
    value: String, onChange: (String) -> Unit, placeholder: String, modifier: Modifier = Modifier, enabled: Boolean = true,
    error: Boolean = false, mono: Boolean = false,
    visual: androidx.compose.ui.text.input.VisualTransformation = androidx.compose.ui.text.input.VisualTransformation.None,
    keyboard: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
) {
    val p = Sniffo.c
    val style = (if (mono) Mono else MaterialTheme.typography.bodyMedium).copy(color = if (enabled) p.text else p.muted)
    BasicTextField(
        value, onChange, modifier.fillMaxWidth(), enabled = enabled, singleLine = true, textStyle = style, cursorBrush = SolidColor(p.text),
        visualTransformation = visual, keyboardOptions = keyboard,
        decorationBox = { inner ->
            Box(
                Modifier.clip(Radius).background(p.panel).border(1.dp, if (error) p.bad else p.border, Radius).padding(horizontal = 10.dp, vertical = 8.dp),
            ) {
                if (value.isEmpty()) Text(placeholder, style = style.copy(color = p.faint), maxLines = 1)
                inner()
            }
        },
    )
}

enum class Tone { NONE, ON, WARN, BAD }

/** A small outlined label (`.tag`, `.tag.on`, `.tag.warn`, `.tag.bad`). */
@Composable
fun Tag(text: String, tone: Tone = Tone.NONE) {
    val p = Sniffo.c
    val color = when (tone) { Tone.NONE -> p.muted; Tone.ON -> p.good; Tone.WARN -> p.warn; Tone.BAD -> p.bad }
    val line = if (tone == Tone.NONE) p.border2 else color.copy(alpha = 0.35f)
    Text(
        text,
        Modifier.padding(end = 4.dp, top = 3.dp).border(1.dp, line, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
        style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1,
    )
}

/** A device type with its colour swatch (`.kind`). */
@Composable
fun Kind(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(color))
        Text(label, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.labelMedium, color = Sniffo.c.muted, maxLines = 1)
    }
}

@Composable
fun Dot(color: Color, size: Int = 6) {
    Box(Modifier.size(size.dp).clip(CircleShape).background(color))
}

/** A status line with a coloured dot (`.live`). */
@Composable
fun StatusLine(text: String, tone: Tone = Tone.NONE, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
    val p = Sniffo.c
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Dot(when (tone) { Tone.NONE -> p.faint; Tone.ON -> p.good; Tone.WARN -> p.warn; Tone.BAD -> p.bad })
        Text(text, Modifier.padding(start = 6.dp), style = MaterialTheme.typography.bodySmall, color = if (tone == Tone.BAD) p.bad else p.muted)
    }
}

/** A muted explanatory line. */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp)) {
    Text(text, modifier, style = MaterialTheme.typography.bodySmall, color = Sniffo.c.muted)
}

/** The header of a list, like a table head (`th`): a count on the left and the column on the right. */
@Composable
fun ListHead(left: String, right: String? = null) {
    val p = Sniffo.c
    Column {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(left.uppercase(), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = p.faint)
            right?.let { Text(it.uppercase(), style = MaterialTheme.typography.titleSmall, color = p.faint) }
        }
        HorizontalDivider(color = p.border)
    }
}

/**
 * One device in a list, laid out like a row of sniffowireless's device table: name and signal, then the type with
 * its colour and the address, then what it was identified as.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeviceRow(
    title: String,
    kindColor: Color,
    kind: String,
    address: String?,
    identified: String? = null,
    onClick: (() -> Unit)? = null,
    strong: Boolean = true,
    live: Boolean = false,
    note: String? = null,
    trailing: @Composable (() -> Unit)? = null,
    tags: @Composable (FlowRowScope.() -> Unit)? = null,
) {
    val p = Sniffo.c
    Column(Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                title, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (strong) FontWeight.SemiBold else FontWeight.Normal, color = if (strong) p.text else p.muted,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            trailing?.let { Box(Modifier.padding(start = 8.dp)) { it() } }
        }
        if (tags != null) FlowRow(content = tags)
        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Kind(kindColor, kind)
            if (address != null) {
                Spacer(Modifier.width(12.dp))
                if (live) { Dot(p.good); Spacer(Modifier.width(6.dp)) }
                Text(address, style = Mono, color = p.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        identified?.takeIf { it.isNotBlank() }?.let {
            Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodySmall, color = p.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        note?.let { Text(it, Modifier.padding(top = 2.dp), style = MaterialTheme.typography.labelSmall, color = p.faint) }
    }
    HorizontalDivider(color = p.border)
}

/** A bar for per-channel numbers, in a device-type colour. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    val p = Sniffo.c
    Box(modifier.height(6.dp).clip(RoundedCornerShape(3.dp)).background(p.panel2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().clip(RoundedCornerShape(3.dp)).background(color))
    }
}

@Composable
fun PermissionBanner(onGrant: () -> Unit) {
    val p = Sniffo.c
    Panel {
        Text("Permissions needed", fontWeight = FontWeight.SemiBold, color = p.warn)
        Text(
            "Android requires location and nearby-devices permissions to list Wi-Fi networks " +
                "and Bluetooth devices. Nothing leaves your phone unless you turn on sensor mode.",
            style = MaterialTheme.typography.bodySmall, color = p.muted, modifier = Modifier.padding(top = 2.dp),
        )
        Btn("Grant", onGrant, Modifier.padding(top = 10.dp))
    }
}

@Composable
fun RssiText(rssi: Int?) {
    if (rssi == null) return
    val p = Sniffo.c
    val color = when {
        rssi >= -60 -> p.good
        rssi >= -75 -> p.warn
        else -> p.bad
    }
    Text("$rssi dBm", style = Mono, color = color)
}

@Composable
fun DetailDialog(title: String, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val p = Sniffo.c
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { Btn("Close", onDismiss) },
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        containerColor = p.panel,
        titleContentColor = p.text,
        textContentColor = p.text,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.border(1.dp, p.border, RoundedCornerShape(8.dp)),
        text = {
            SelectionContainer {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(3.dp), content = content)
            }
        },
    )
}

private val ADDRESS_LIKE = Regex("^[0-9A-Fa-fx:./\\- ]+$")

@Composable
fun InfoRow(label: String, value: String?) {
    if (value.isNullOrBlank()) return
    val p = Sniffo.c
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = p.muted, modifier = Modifier.width(100.dp))
        Text(value, style = if (ADDRESS_LIKE.matches(value) && value.any(Char::isDigit)) Mono else MaterialTheme.typography.bodySmall, color = p.text)
    }
}

@Composable
fun GuessSection(guess: Guess) {
    H2("Identified as", Modifier.padding(top = 10.dp, bottom = 2.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(guess.type.icon(), contentDescription = null, modifier = Modifier.padding(end = 6.dp).size(18.dp), tint = Sniffo.c.text)
        Text("${guess.type.label} · ${guess.confidence}% confidence", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
    }
    guess.reasons.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall, color = Sniffo.c.muted) }
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

/** A section title inside a detail dialog. */
@Composable
fun DetailTitle(text: String) {
    H2(text, Modifier.padding(top = 10.dp, bottom = 2.dp))
}
