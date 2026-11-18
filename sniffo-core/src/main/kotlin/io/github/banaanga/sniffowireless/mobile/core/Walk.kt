package io.github.banaanga.sniffowireless.mobile.core

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Walk survey from the phone (server release 34.2, walk.py): the phone that is the sensor shows the floor plan, and a tap where
 * you stand turns what it heard in the last seconds into a point of the survey. It uses the sensor token, which reaches only the
 * surveys of this sensor (`/api/ingest/walk`); the heatmaps, layers and comparisons are on the server's web page.
 */
object Walk {
    data class Point(val id: Long, val x: Double, val y: Double, val aps: Int, val best: Int?)
    data class Suggest(val x: Double, val y: Double, val room: String?, val fixes: Int, val areaAfter: Int)
    data class Analysis(val points: Int, val pointsGood: Int, val areaGood: Int?, val dead: Int, val suggest: Suggest?)
    data class Survey(val id: Long, val name: String, val widthM: Double, val open: Boolean, val points: List<Point>, val analysis: Analysis?)
    data class Summary(val id: Long, val name: String, val created: Double, val points: Int, val closed: Boolean)
    data class Now(val aps: Int, val bestMine: Int?)
    data class Floor(val width: Int?, val height: Int?, val image: String?, val updated: Double?) {
        /** height / width of the floor plan; the server's default shape when it has none. */
        val ratio: Double get() = if (width != null && height != null && width > 0 && height > 0) height.toDouble() / width else 0.64
        /** Android draws PNG, JPEG and WebP; an SVG floor plan is not drawn in the app. */
        val drawable: Boolean get() = image in setOf("png", "jpg", "webp")
    }
    data class State(val surveys: List<Summary>, val open: Long?, val window: Int, val good: Int, val weak: Int, val floor: Floor,
                     val survey: Survey?, val now: Now?)

    /** The colours of the web page's steps: strong, good for calls and video, fair, weak, too weak; grey: none of mine heard. */
    val STEPS = listOf(-60 to 0xFF1FA463.toInt(), -67 to 0xFF7BC043.toInt(), -75 to 0xFFF4C20D.toInt(), -82 to 0xFFF08A24.toInt())
    const val TOO_WEAK = 0xFFD93025.toInt()
    const val NONE = 0xFF888888.toInt()

    fun colour(best: Int?): Int = if (best == null) NONE else STEPS.firstOrNull { best >= it.first }?.second ?: TOO_WEAK

    private fun num(v: Any?): Double? = (v as? Number)?.toDouble()
    private fun int(v: Any?): Int? = (v as? Number)?.toInt()
    private fun long(v: Any?): Long? = (v as? Number)?.toLong()
    private fun map(v: Any?): Map<*, *>? = v as? Map<*, *>
    private fun list(v: Any?): List<*> = v as? List<*> ?: emptyList<Any?>()

    fun parse(text: String): State {
        val d = map(Json.parse(text)) ?: throw IOException("not a survey reply")
        val f = map(d["floorplan"]) ?: emptyMap<String, Any?>()
        val s = map(d["survey"])
        val survey = s?.let {
            val a = map(it["analysis"])
            val sg = map(a?.get("suggest"))
            Survey(
                long(it["id"]) ?: 0, it["name"] as? String ?: "", num(it["width_m"]) ?: 20.0, it["open"] == true,
                list(it["points"]).mapNotNull { p -> map(p)?.let { m -> Point(long(m["id"]) ?: 0, num(m["x"]) ?: 0.0, num(m["y"]) ?: 0.0, int(m["aps"]) ?: 0, int(m["best"])) } },
                a?.let { x ->
                    Analysis(int(x["points"]) ?: 0, int(x["points_good"]) ?: 0, int(x["area_good"]), list(x["dead"]).size,
                        sg?.let { g -> Suggest(num(g["x"]) ?: 0.0, num(g["y"]) ?: 0.0, g["room"] as? String, int(g["fixes"]) ?: 0, int(g["area_after"]) ?: 0) })
                },
            )
        }
        val now = map(d["now"])?.let { Now(int(it["aps"]) ?: 0, int(it["best_mine"])) }
        return State(
            list(d["surveys"]).mapNotNull { x -> map(x)?.let { Summary(long(it["id"]) ?: 0, it["name"] as? String ?: "", num(it["created"]) ?: 0.0, int(it["points"]) ?: 0, it["closed"] != null) } },
            long(d["open"]), int(d["window"]) ?: 10, int(d["good"]) ?: -67, int(d["weak"]) ?: -75,
            Floor(int(f["width"]), int(f["height"]), f["image"] as? String, num(f["updated"])), survey, now,
        )
    }

    /** One HTTP call: (status, body). The default goes over the network; tests put in their own. */
    fun interface Http {
        fun call(method: String, url: String, token: String, body: String?): Pair<Int, ByteArray>
    }

    class Client(private val target: () -> Target?, private val http: Http = Http(::httpCall)) {
        private fun req(method: String, path: String, body: Map<String, Any?>? = null): ByteArray {
            val t = target() ?: throw IOException("No server set: fill it in on the Sensor tab")
            val (code, bytes) = http.call(method, t.serverUrl.trimEnd('/') + path, t.token, body?.let { Json.encode(it) })
            if (code >= 400) {
                val detail = runCatching { (Json.parse(bytes.toString(Charsets.UTF_8)) as? Map<*, *>)?.get("detail") as? String }.getOrNull()
                throw IOException(detail ?: when (code) {
                    401 -> "the server refused the sensor token (HTTP 401)"
                    404 -> "the server has no walk survey for this sensor (HTTP 404); it needs release 34.2 or later"
                    else -> "HTTP $code"
                })
            }
            return bytes
        }

        fun state(survey: Long? = null): State = parse(req("GET", "/api/ingest/walk" + (survey?.let { "?survey=$it" } ?: "")).toString(Charsets.UTF_8))
        fun floorplan(): ByteArray = req("GET", "/api/ingest/walk/floorplan")
        fun start(name: String, widthM: Double): Long =
            long((Json.parse(req("POST", "/api/ingest/walk", linkedMapOf("name" to name.ifBlank { null }, "width_m" to widthM)).toString(Charsets.UTF_8)) as Map<*, *>)["id"]) ?: 0
        /** -> how many access points the point holds */
        fun point(survey: Long, x: Double, y: Double): Int =
            int((Json.parse(req("POST", "/api/ingest/walk/$survey/point", linkedMapOf("x" to x.coerceIn(0.0, 1.0), "y" to y.coerceIn(0.0, 1.0))).toString(Charsets.UTF_8)) as Map<*, *>)["aps"]) ?: 0
        fun undo(survey: Long, point: Long) { req("DELETE", "/api/ingest/walk/$survey/point/$point") }
        fun stop(survey: Long) { req("POST", "/api/ingest/walk/$survey/stop") }
        fun resume(survey: Long) { req("POST", "/api/ingest/walk/$survey/resume") }
    }

    fun httpCall(method: String, url: String, token: String, body: String?): Pair<Int, ByteArray> {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.requestMethod = method
            c.connectTimeout = 15_000
            c.readTimeout = 20_000
            c.setRequestProperty("Authorization", "Bearer $token")
            c.setRequestProperty("User-Agent", "sniffowireless-mobile")
            if (body != null) {
                val b = body.toByteArray()
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/json")
                c.setFixedLengthStreamingMode(b.size)
                c.outputStream.use { it.write(b) }
            }
            val code = c.responseCode
            val bytes = (if (code >= 400) c.errorStream else c.inputStream)?.use { it.readBytes() } ?: ByteArray(0)
            return code to bytes
        } finally {
            c.disconnect()
        }
    }
}
