package io.github.banaanga.sniffowireless.mobile.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import io.github.banaanga.sniffowireless.mobile.MainViewModel
import io.github.banaanga.sniffowireless.mobile.core.Walk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Walk survey with this phone as the sensor (server release 34.2): the floor plan, the points of the open survey coloured by the
 * strongest of your access points, a tap where you stand adds one. Heatmaps and comparisons are on the server's Walk survey page.
 */
@Composable
fun WalkScreen(vm: MainViewModel, onBack: () -> Unit) {
    val hub = vm.hub
    val client = remember { Walk.Client({ hub.settings.target }) }
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf<Walk.State?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf<String?>(null) }
    var picked by remember { mutableStateOf<Long?>(null) }
    var plan by remember { mutableStateOf<ImageBitmap?>(null) }
    var planKey by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var width by remember { mutableStateOf("20") }

    fun act(what: suspend () -> String?) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                note = withContext(Dispatchers.IO) { what() }
                error = null
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            } finally {
                busy = false
                reload++
            }
        }
    }

    LaunchedEffect(picked, reload) {
        while (true) {
            try {
                val s = withContext(Dispatchers.IO) { client.state(picked) }
                state = s
                error = null
                val key = "${s.floor.image}:${s.floor.updated}:${s.floor.width}"
                if (s.floor.drawable && key != planKey) {
                    planKey = key
                    plan = withContext(Dispatchers.IO) {
                        runCatching { client.floorplan().let { b -> BitmapFactory.decodeByteArray(b, 0, b.size)?.asImageBitmap() } }.getOrNull()
                    }
                }
            } catch (e: Exception) {
                error = e.message ?: e.toString()
            }
            delay(2000)
        }
    }

    val p = Sniffo.c
    Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
        PageHead("Walk survey", "This phone is the sensor: stand still, then tap where you are.") {
            Btn("Back", onBack)
        }
        if (hub.settings.target == null) {
            Hint("Fill in the server and token on the Sensor tab first.")
            return@Column
        }
        error?.let { StatusLine(it, Tone.BAD) }
        note?.let { StatusLine(it, Tone.ON) }
        val s = state ?: run { Hint("Loading…"); return@Column }
        val v = s.survey
        if (!hub.sensorRunning.value) StatusLine("The sensor is off: start it, or the survey gets no readings from this phone.", Tone.WARN)

        if (v != null) {
            Panel(title = v.name + if (v.open) "" else " · finished") {
                Box(Modifier.fillMaxWidth().aspectRatio((1 / s.floor.ratio).toFloat()).border(1.dp, p.border).background(p.panel2)) {
                    plan?.takeIf { s.floor.drawable }?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds) }
                    Canvas(
                        Modifier.fillMaxSize().pointerInput(v.id, v.open) {
                            if (v.open) detectTapGestures { at ->
                                val x = at.x / size.width.toDouble()
                                val y = at.y / size.height.toDouble()
                                act { "Point added: ${client.point(v.id, x, y)} access points" }
                            }
                        },
                    ) {
                        val r = 7.dp.toPx()
                        v.analysis?.suggest?.let { g ->
                            val c = Offset((g.x * size.width).toFloat(), (g.y * size.height).toFloat())
                            drawCircle(Color(0x332F7DE1), radius = r * 2.6f, center = c)
                            drawCircle(Color(0xFF2F7DE1), radius = r * 2.6f, center = c,
                                style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))))
                            drawLine(Color(0xFF2F7DE1), c.copy(x = c.x - r), c.copy(x = c.x + r), 2.dp.toPx())
                            drawLine(Color(0xFF2F7DE1), c.copy(y = c.y - r), c.copy(y = c.y + r), 2.dp.toPx())
                        }
                        for (pt in v.points) {
                            val c = Offset((pt.x * size.width).toFloat(), (pt.y * size.height).toFloat())
                            drawCircle(Color(Walk.colour(pt.best)), radius = r, center = c)
                            drawCircle(Color(0xAA000000), radius = r, center = c, style = Stroke(1.dp.toPx()))
                        }
                    }
                }
                if (!s.floor.drawable) Muted(if (s.floor.image == null) "No floor plan on the server yet (Map → Edit layout): the points go on a blank floor." else "The floor plan is an SVG, which the app doesn't draw: the points go on a blank floor of its shape.", Modifier.padding(top = 6.dp))
                Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Walk.STEPS.forEach { (t, c) -> Kind(Color(c), "≥$t") }
                    Kind(Color(Walk.TOO_WEAK), "weaker")
                }
                if (v.open) {
                    s.now?.let { n ->
                        Text("Heard now: ${n.aps} access points" + (n.bestMine?.let { " · yours at best $it dBm" } ?: "") +
                            ". Stand still for ${s.window} s, then tap where you are.", style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                    }
                    Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Btn("Undo last point", { v.points.lastOrNull()?.let { last -> act { client.undo(v.id, last.id); "Point taken back" } } }, enabled = v.points.isNotEmpty() && !busy)
                        Btn("Finish", { act { client.stop(v.id); "Survey finished" } }, enabled = !busy)
                    }
                } else {
                    Btn("Go on with this survey", { act { client.resume(v.id); picked = null; "Survey open again" } }, Modifier.padding(top = 8.dp), enabled = !busy)
                }
                v.analysis?.let { a ->
                    if (a.points > 0) {
                        Text("${a.pointsGood * 100 / a.points}% of the points" + (a.areaGood?.let { " and $it% of the area" } ?: "") +
                            " at ${s.good} dBm or better · ${a.dead} dead spot${if (a.dead == 1) "" else "s"} (below ${s.weak} dBm)",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp))
                    }
                    a.suggest?.let { g ->
                        Text("An extra access point at the dashed + " + (g.room?.let { "(near the $it) " } ?: "") +
                            "would bring ${g.fixes}% of the weak area to ${s.good} dBm: coverage ${a.areaGood}% → ${g.areaAfter}%.",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                    }
                }
                Muted("Floor ${v.widthM.toString().removeSuffix(".0")} m wide · heatmaps and comparisons on the server's Walk survey page", Modifier.padding(top = 6.dp))
            }
        }

        if (v == null || !v.open) {
            Panel(title = "New survey") {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    FieldLabel("Name")
                    Field(name, { name = it }, "Survey ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date())}")
                    FieldLabel("Floor width (metres)")
                    Field(width, { width = it.filter { ch -> ch.isDigit() || ch == '.' } }, "20", Modifier.width(120.dp), keyboard = KeyboardOptions(keyboardType = KeyboardType.Decimal))
                    Btn("Start", {
                        val w = width.toDoubleOrNull()?.coerceIn(3.0, 500.0) ?: 20.0
                        act { val id = client.start(name, w); picked = null; "Survey $id started" }
                    }, enabled = !busy)
                }
            }
        }

        if (s.surveys.isNotEmpty()) {
            SectionTitle("This phone's surveys")
            s.surveys.forEach { x ->
                Row(
                    Modifier.fillMaxWidth().clickable { picked = x.id; reload++ }.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(x.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = if (x.id == v?.id) p.text else p.muted)
                    if (x.id == s.open) Tag("open", Tone.ON)
                    Text("${x.points} points · ${DateFormat.getDateInstance(DateFormat.SHORT).format(Date((x.created * 1000).toLong()))}", style = Mono, color = p.faint)
                }
            }
        }
    }
}
