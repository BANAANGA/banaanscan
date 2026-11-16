package io.github.banaanga.sniffowireless.mobile.core

import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.ArrayDeque
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/** Takes observations: the forwarder when sensor mode is on, nothing otherwise. */
fun interface Sink {
    fun put(obs: Map<String, Any?>)
}

/**
 * The status of each capture interface, as sniffowireless's status.py keeps it and the Sensors page shows it:
 * name ("wifi:mt7921u"), type, state, error, packets, pps and type-specific fields.
 */
class StatusBoard {
    private val entries = LinkedHashMap<String, MutableMap<String, Any?>>()

    @Synchronized
    fun register(name: String, vararg fields: Pair<String, Any?>) {
        entries[name] = linkedMapOf<String, Any?>("name" to name, "state" to "starting", "error" to null, "packets" to 0, "pps" to 0.0,
            "since" to System.currentTimeMillis() / 1000.0).also { it.putAll(fields) }
    }

    @Synchronized
    fun update(name: String, vararg fields: Pair<String, Any?>) {
        entries.getOrPut(name) { linkedMapOf("name" to name) }.putAll(fields)
    }

    @Synchronized
    fun remove(name: String) {
        entries.remove(name)
    }

    @Synchronized
    fun snapshot(): List<Map<String, Any?>> = entries.values.map { LinkedHashMap(it) }
}

/** Recent log lines, sent to the server's Logs page (sniffowireless's RingHandler): {id, ts, level, logger, msg, thread}. */
class LogBuffer(private val capacity: Int = 2000) {
    private val lines = ArrayDeque<Map<String, Any?>>()
    private var next = 1L
    /** Tells this run's lines from a restarted app's, which counts from 1 again. */
    val epoch: String = "%08x".format(Random.nextInt())

    @Synchronized
    fun add(level: String, logger: String, msg: String) {
        if (lines.size >= capacity) lines.removeFirst()
        lines.addLast(linkedMapOf("id" to next++, "ts" to System.currentTimeMillis() / 1000.0, "level" to level, "logger" to logger,
            "msg" to msg.take(2000), "thread" to Thread.currentThread().name.take(40)))
    }

    @Synchronized
    fun since(after: Long, limit: Int = 200): List<Map<String, Any?>> =
        lines.filter { (it["id"] as Long) > after }.takeLast(limit)

    @Synchronized
    fun recent(n: Int): List<Map<String, Any?>> = lines.toList().takeLast(n)
}

/**
 * Wi-Fi channel settings set for this sensor in sniffowireless's web UI (Settings → Wi-Fi channels), as its
 * channels.WifiControl applies them on a remote sensor: a revision that is new and valid replaces the override; an
 * invalid one is refused once. [iface] is the name the adapter is reported under, for per-adapter lists.
 */
class WifiControl(private val iface: String, private val log: (String, String) -> Unit = { _, _ -> }) {
    data class Plan(val channels: List<Int>, val hopInterval: Double?, val regdomain: String?)

    @Volatile var rev = 0
        private set
    @Volatile var override: Plan? = null
        private set
    private var rejected: Int? = null
    var onChange: (() -> Unit)? = null

    /** The channels to hop over: the server's list when it set one, else [own]. */
    fun channels(own: List<Int>): List<Int> = override?.channels?.takeIf { it.isNotEmpty() } ?: own

    fun hopInterval(own: Double): Double = override?.hopInterval ?: own

    fun applyRemote(conf: Any?) {
        if (conf !is Map<*, *>) return
        val r = (conf["rev"] as? Number)?.toInt() ?: return
        if (r == rev || r == rejected) return
        try {
            override = clean(conf)
            rev = r
            log("INFO", "Channel settings from the server applied (revision $r)")
            onChange?.invoke()
        } catch (e: IllegalArgumentException) {
            rejected = r
            log("WARNING", "Ignoring channel settings from the server (revision $r): ${e.message}")
        }
    }

    private fun clean(o: Map<*, *>): Plan? {
        var channels = (o["channels"] as? String)?.takeIf { it.isNotBlank() }?.let { Channels.check(it) }
        val per = o["per_iface"] as? Map<*, *>
        (per?.get(iface) as? String)?.let { spec -> channels = if (spec.isEmpty()) channels else Channels.check(spec) }
        val regdomain = (o["regdomain"] as? String)?.trim()?.uppercase()?.takeIf { it.isNotEmpty() }?.also {
            require(Regex("^[A-Z]{2}$").matches(it)) { "The regulatory domain is a two-letter country code, for example NL" }
        }
        val hop = when (val h = o["hop_interval"]) {
            null, "" -> null
            is Number -> h.toDouble()
            is String -> h.toDoubleOrNull() ?: throw IllegalArgumentException("The hop interval is a number of seconds")
            else -> throw IllegalArgumentException("The hop interval is a number of seconds")
        }?.also { require(it in 0.2..60.0) { "The hop interval must be between 0.2 and 60 seconds" } }
        if (channels == null && hop == null && regdomain == null) return null
        return Plan(channels.orEmpty(), hop, regdomain)
    }
}

/** Zigbee settings from the web UI (Settings → Zigbee), as sniffowireless's zigbee.ZigbeeControl applies them. */
class ZigbeeControl(private val log: (String, String) -> Unit = { _, _ -> }) {
    data class Plan(val channels: List<Int>?, val hopInterval: Double?, val scanInterval: Int?, val sniff: Boolean?)

    @Volatile var rev = 0
        private set
    @Volatile var override: Plan? = null
        private set
    private var rejected: Int? = null
    var onChange: (() -> Unit)? = null

    fun channels(own: List<Int>) = override?.channels ?: own
    fun hopInterval(own: Double) = override?.hopInterval ?: own
    fun scanInterval(own: Int) = override?.scanInterval ?: own
    fun sniff(own: Boolean) = override?.sniff ?: own

    fun applyRemote(conf: Any?) {
        if (conf !is Map<*, *>) return
        val r = (conf["rev"] as? Number)?.toInt() ?: return
        if (r == rev || r == rejected) return
        try {
            override = clean(conf)
            rev = r
            log("INFO", "Zigbee settings from the server applied (revision $r)")
            onChange?.invoke()
        } catch (e: IllegalArgumentException) {
            rejected = r
            log("WARNING", "Ignoring Zigbee settings from the server (revision $r): ${e.message}")
        }
    }

    companion object {
        /** "15,20-25" -> channels; Zigbee's 2.4 GHz channels are 11-26. */
        fun parseChannels(spec: String): List<Int> {
            val out = ArrayList<Int>()
            for (tok in spec.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                val parts = tok.split("-", limit = 2)
                val a = parts[0].trim().toIntOrNull()
                val b = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: a
                require(a != null && b != null) { "'$tok' is not a channel number or range like 20-25" }
                require(a in 11..26 && b in 11..26 && a <= b) { "'$tok': Zigbee channels are 11 to 26" }
                (a..b).filter { it !in out }.forEach { out += it }
            }
            require(out.isNotEmpty()) { "The channel list is empty" }
            return out
        }
    }

    private fun number(v: Any?, what: String, range: ClosedFloatingPointRange<Double>): Double? = when (v) {
        null, "" -> null
        is Number -> v.toDouble()
        is String -> v.toDoubleOrNull() ?: throw IllegalArgumentException("$what is a number of seconds")
        else -> throw IllegalArgumentException("$what is a number of seconds")
    }?.also { require(it in range) { "$what must be between ${range.start} and ${range.endInclusive} seconds" } }

    private fun clean(o: Map<*, *>): Plan? {
        val channels = (o["channels"] as? String)?.takeIf { it.isNotBlank() }?.let { require(it.length <= 200) { "The channel list is too long" }; parseChannels(it) }
        val hop = number(o["hop_interval"], "The time on each channel", 0.2..600.0)
        val scan = number(o["scan_interval"], "The time between network scans", 30.0..86400.0)?.toInt()
        val sniff = (o["sniff"] as? Boolean)
        if (channels == null && hop == null && scan == null && sniff == null) return null
        return Plan(channels, hop, scan, sniff)
    }
}

/** Where to send: the server's address and the token from its Settings → Sensors → Add remote sensor. */
data class Target(val serverUrl: String, val token: String)

/**
 * A remote sensor's uplink (sniffowireless's sensors.Forwarder): every 2 seconds it sends what was observed, the status of
 * each interface, the app's version, load and memory, and new log lines to /api/ingest as gzipped JSON. A batch that
 * doesn't arrive is kept and sent again, with the wait doubling up to a minute. The server's answer can carry channel
 * settings for this sensor.
 */
class Forwarder(
    @Volatile var target: Target?,
    private val status: StatusBoard,
    private val logs: LogBuffer,
    private val version: Map<String, Any?>,
    private val system: () -> Map<String, Any?>? = { null },
    private val maxBuffer: Int = 50_000,
    private val post: (url: String, token: String, body: ByteArray) -> String = ::httpPost,
) : Sink {
    private val buf = ArrayDeque<Map<String, Any?>>()
    @Volatile var dropped = 0L
        private set
    @Volatile var sent = 0L
        private set
    @Volatile var lastError: String? = null
        private set
    @Volatile var lastSuccess: Long? = null
        private set
    @Volatile var lastReply: Map<String, Any?>? = null
        private set
    private var logCursor = 0L
    private var backoff = 2
    var onChannelConfig: ((Any?) -> Unit)? = null
    var onZigbeeConfig: ((Any?) -> Unit)? = null
    var onLoraConfig: ((Any?) -> Unit)? = null
    /** Recording at the server's request (Settings → Frame recording on the server): its progress goes with every upload. */
    var capture: RemoteCapture? = null
    /** Live frames for the server's Frames page: sent with every upload while the server asks for them. */
    var live: LiveTap? = null

    val pending: Int @Synchronized get() = buf.size

    @Synchronized
    override fun put(obs: Map<String, Any?>) {
        if (buf.size >= maxBuffer) {
            buf.removeFirst()
            dropped++
        }
        buf.addLast(obs)
    }

    /** Milliseconds to wait before the next [step]. */
    fun delayMs(): Long = backoff * 1000L

    /** Sends one batch (even an empty one: the status is the sensor's heartbeat). Returns true when it arrived. */
    fun step(): Boolean {
        val t = target ?: return false
        val batch = ArrayList<Map<String, Any?>>()
        synchronized(this) { while (buf.isNotEmpty() && batch.size < 20_000) batch += buf.removeFirst() }
        val lines = logs.since(logCursor)
        val body = gzip(Json.encode(linkedMapOf("sent_at" to System.currentTimeMillis() / 1000.0, "obs" to batch, "status" to status.snapshot(),
            "dropped" to dropped, "version" to version, "system" to system(), "logs" to lines, "log_epoch" to logs.epoch, "capture" to capture?.report()) + liveFrames()).toByteArray())
        return try {
            val reply = post(t.serverUrl.trimEnd('/') + "/api/ingest", t.token, body)
            sent += batch.size
            if (lines.isNotEmpty()) logCursor = lines.last()["id"] as Long
            backoff = 2
            lastError = null
            lastSuccess = System.currentTimeMillis()
            handleReply(reply)
            true
        } catch (e: Exception) {
            synchronized(this) { batch.asReversed().forEach { buf.addFirst(it) } }
            backoff = minOf(60, backoff * 2)
            lastError = e.message ?: e.toString()
            logs.add("WARNING", "sniffowireless.sensors", "Forwarding ${batch.size} observations to ${t.serverUrl} failed: $lastError (retry in ${backoff}s)")
            false
        }
    }

    /** This upload's live frames, while the server wants them or some are still waiting. */
    private fun liveFrames(): Map<String, Any?> {
        val l = live ?: return emptyMap()
        if (!l.active && l.pending == 0) return emptyMap()
        return mapOf("frames" to l.drain(), "frames_dropped" to l.dropped)
    }

    private fun handleReply(reply: String) {
        @Suppress("UNCHECKED_CAST")
        val parsed = runCatching { Json.parse(reply) as? Map<String, Any?> }.getOrNull() ?: return
        lastReply = parsed
        parsed["channel_config"]?.let { onChannelConfig?.invoke(it) }
        parsed["zigbee_config"]?.let { onZigbeeConfig?.invoke(it) }
        parsed["lora_config"]?.let { onLoraConfig?.invoke(it) }
        live?.applyRemote(parsed["live"])
        val commands = (parsed["commands"] as? List<*>).orEmpty().filterIsInstance<Map<*, *>>()
        commands.filter { it["action"] == "capture" }.forEach { cmd ->
            capture?.handle(cmd) ?: logs.add("INFO", "sniffowireless.sensors", "The server asked for a recording, which this phone cannot make")
        }
        if (commands.any { it["action"] == "reset" }) {
            logs.add("INFO", "sniffowireless.sensors", "The server asked for an adapter reset; replug the adapter on the phone instead")
        }
    }

    companion object {
        /** Sends a recording the server asked for to /api/ingest/capture, with the sensor token. */
        fun httpUpload(target: Target, id: String, file: File) {
            val c = URL(target.serverUrl.trimEnd('/') + "/api/ingest/capture?id=$id").openConnection() as HttpURLConnection
            try {
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 300_000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer ${target.token}")
                c.setRequestProperty("Content-Type", "application/gzip")
                c.setRequestProperty("User-Agent", "sniffowireless-mobile")
                c.setFixedLengthStreamingMode(file.length())
                c.outputStream.use { o -> file.inputStream().use { it.copyTo(o) } }
                val code = c.responseCode
                if (code >= 400) {
                    val detail = runCatching { c.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) }.getOrNull()
                    throw IOException("HTTP $code" + (detail?.let { ": $it" } ?: ""))
                }
                c.inputStream.use { it.readBytes() }
            } finally {
                c.disconnect()
            }
        }

        fun gzip(data: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(data) } }.toByteArray()

        /**
         * POST over HttpURLConnection. Reading the whole answer (and not calling disconnect) lets it keep the connection
         * open for the next batch, as sniffowireless's sensors do: one name lookup and TLS handshake, not one per batch.
         */
        fun httpPost(url: String, token: String, body: ByteArray): String {
            val c = URL(url).openConnection() as HttpURLConnection
            run {
                c.requestMethod = "POST"
                c.connectTimeout = 15_000
                c.readTimeout = 20_000
                c.doOutput = true
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("Content-Encoding", "gzip")
                c.setRequestProperty("User-Agent", "sniffowireless-mobile")
                c.setFixedLengthStreamingMode(body.size)
                c.outputStream.use { it.write(body) }
                val code = c.responseCode
                if (code >= 400) {
                    val detail = runCatching { c.errorStream?.readBytes()?.toString(Charsets.UTF_8)?.take(200) }.getOrNull()
                    throw IOException(when (code) {
                        401 -> "the server refused the sensor token (HTTP 401)"
                        403 -> "the token isn't a sensor token (HTTP 403)"
                        else -> "HTTP $code" + (detail?.let { ": $it" } ?: "")
                    })
                }
                return c.inputStream.use { it.readBytes() }.toString(Charsets.UTF_8)
            }
        }
    }
}
