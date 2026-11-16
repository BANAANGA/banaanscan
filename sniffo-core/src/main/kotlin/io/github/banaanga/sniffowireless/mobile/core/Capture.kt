package io.github.banaanga.sniffowireless.mobile.core

import java.io.File
import java.io.OutputStreamWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.GZIPOutputStream

/**
 * Records what the USB radios deliver, in the format of sniffowireless's capture.py: a gzip file of JSON lines, one per frame,
 * {"ts", "radio", "src", "raw" (hex), channel / rssi / lqi / tuned}. The server opens such a file in its Frames page.
 *
 *   wifi    the 802.11 frame behind a small radiotap header (channel, signal); of data frames only the headers, never their contents
 *   bt      the HCI event, with the leading 0x04 packet type
 *   zigbee  the 802.15.4 frame without its checksum, with channel, signal and LQI
 *   lora    the line the lora-sniffer board printed for the frame
 *
 * The phone's own Wi-Fi and Bluetooth are not recorded: Android hands over what it parsed, not the frames.
 */
class Recorder(private val clock: () -> Long = System::currentTimeMillis) {
    @Volatile var active = false
        private set
    @Volatile var file: File? = null
        private set
    @Volatile var frames = 0
        private set
    @Volatile var until = 0L
        private set
    private var radios: Set<String> = emptySet()
    private var out: OutputStreamWriter? = null
    private var size = 0L

    /** Starts a recording of [radios] for [seconds] into [dir]; throws IllegalStateException while one runs. */
    @Synchronized
    fun start(dir: File, seconds: Int, radios: Collection<String>): File {
        check(!active) { "A recording is already running" }
        dir.mkdirs()
        val now = clock()
        val f = File(dir, "capture-%tY%<tm%<td-%<tH%<tM%<tS.jsonl.gz".format(now))
        out = OutputStreamWriter(GZIPOutputStream(f.outputStream().buffered()), Charsets.US_ASCII)
        file = f
        this.radios = radios.filter { it in RADIOS }.toSet()
        until = now + seconds.coerceIn(1, MAX_SECONDS) * 1000L
        frames = 0
        size = 0
        active = true
        return f
    }

    /** Called by the sources for every frame; does nothing (and costs nothing) unless a recording of that radio runs. */
    fun record(radio: String, src: String, raw: ByteArray, meta: Map<String, Any?> = emptyMap()) {
        if (!active || radio !in radios) return
        val bytes = if (radio == "wifi") trimWifi(raw) else raw
        val line = Json.encode(linkedMapOf<String, Any?>("ts" to clock() / 1000.0, "radio" to radio, "src" to src, "raw" to hex(bytes)) + meta)
        var done: Boolean
        synchronized(this) {
            val o = out ?: return
            o.write(line)
            o.write("\n")
            frames++
            size += line.length + 1
            done = size >= MAX_BYTES || clock() >= until
        }
        if (done) stop()
    }

    /** Ends the recording (also when its time is up: see [tick]); returns the file. */
    @Synchronized
    fun stop(): File? {
        if (!active) return null
        active = false
        runCatching { out?.close() }
        out = null
        return file
    }

    /** Stops the recording once its time is up, also when no frame arrives to notice. */
    fun tick() {
        if (active && clock() >= until) stop()
    }

    companion object {
        val RADIOS = setOf("wifi", "bt", "zigbee", "lora")
        const val MAX_SECONDS = 3600
        const val MAX_BYTES = 64L * 1024 * 1024

        fun hex(b: ByteArray): String {
            val sb = StringBuilder(b.size * 2)
            for (x in b) {
                val v = x.toInt() and 0xFF
                sb.append(HEX[v shr 4]).append(HEX[v and 15])
            }
            return sb.toString()
        }

        private const val HEX = "0123456789abcdef"

        /**
         * A radiotap header for an 802.11 frame from the MT7921U: flags, channel (frequency and band) and the antenna signal when
         * known. The same 16 bytes the pcap files of the Wi-Fi page get.
         */
        fun radiotap(freqMhz: Int, rssi: Int?): ByteArray {
            val rt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            rt.put(0).put(0).putShort(16).putInt((1 shl 1) or (1 shl 3) or (if (rssi != null) 1 shl 5 else 0))
            rt.put(0).put(0).putShort(freqMhz.toShort()).putShort((if (freqMhz < 3000) 0x0080 else 0x0100).toShort())
            rt.put((rssi ?: 0).toByte())
            return rt.array()
        }

        /** An HCI event as the controller sent it: packet type 0x04, event code, parameter length, parameters. */
        fun hciEvent(code: Int, params: ByteArray): ByteArray =
            byteArrayOf(0x04, code.toByte(), params.size.coerceAtMost(255).toByte()) + params

        /** The line the lora-sniffer firmware prints for a frame (the time field is not kept: the recording has its own). */
        fun loraLine(rx: LoraRx): String =
            "RX,0,${rx.freq},${rx.bw},${rx.sf},0x%02x,${rx.rssi},${rx.snr},${rx.ferr},${rx.hex.length / 2},${rx.hex}".format(rx.sync)

        /**
         * A data frame cut down to its radiotap and 802.11 headers (capture.trim_wifi): the server only reads addresses from it,
         * and its contents are somebody's traffic. Management and control frames are kept whole.
         */
        fun trimWifi(raw: ByteArray): ByteArray {
            if (raw.size < 4) return raw
            val rt = (raw[2].toInt() and 0xFF) or ((raw[3].toInt() and 0xFF) shl 8)
            if (raw.size > rt + 1 && ((raw[rt].toInt() shr 2) and 3) == 2) {
                val fc0 = raw[rt].toInt() and 0xFF
                val fc1 = raw[rt + 1].toInt() and 0xFF
                var header = 24 + if (fc1 and 3 == 3) 6 else 0
                if (fc0 and 0x80 != 0) header += 2 + if (fc1 and 0x80 != 0) 4 else 0
                return raw.copyOf(minOf(raw.size, rt + header))
            }
            return raw
        }
    }
}

/**
 * Recording at the server's request (Settings → Frame recording → Record on this phone), as sniffowireless's
 * capture.record_for_server does on a Raspberry Pi sensor. The request arrives with the answer to an upload; the phone
 * records its USB radios, reports how it goes with every upload ([report]) and then sends the file with its sensor token
 * ([upload]), and removes it.
 */
class RemoteCapture(
    private val recorder: Recorder,
    private val dir: File,
    private val upload: (file: File, id: String) -> Unit,
    private val log: (level: String, msg: String) -> Unit = { _, _ -> },
    private val pause: (Long) -> Unit = Thread::sleep,
) {
    @Volatile private var job: Map<String, Any?>? = null

    /** What goes into the next upload: {"id", "state", "frames", "error"}, or null when nothing was asked. */
    fun report(): Map<String, Any?>? = job?.let { j -> if (j["state"] == "recording") j + ("frames" to recorder.frames) else j }

    /** A "capture" command from the server: {"id", "seconds", "radios"}. Starts it in a thread of its own. */
    fun handle(cmd: Map<*, *>) {
        val id = cmd["id"] as? String ?: return
        if (!Regex("[0-9a-f]{16}").matches(id) || job?.get("id") == id) return
        val seconds = (cmd["seconds"] as? Number)?.toInt()?.coerceIn(1, Recorder.MAX_SECONDS) ?: 60
        val radios = (cmd["radios"] as? List<*>)?.filterIsInstance<String>()?.filter { it in Recorder.RADIOS }?.takeIf { it.isNotEmpty() }
            ?: Recorder.RADIOS.toList()
        Thread({ run(id, seconds, radios) }, "capture-for-server").apply { isDaemon = true }.start()
    }

    private fun run(id: String, seconds: Int, radios: List<String>) {
        job = mapOf("id" to id, "state" to "recording", "frames" to 0)
        try {
            val file = recorder.start(dir, seconds, radios)
            log("INFO", "Recording ${radios.joinToString(", ")} for ${seconds}s for the server")
            while (recorder.active && recorder.file == file) {
                recorder.tick()
                pause(200)
            }
            val frames = recorder.frames
            job = mapOf("id" to id, "state" to "uploading", "frames" to frames)
            var attempt = 0
            while (true) {
                try {
                    upload(file, id)
                    break
                } catch (e: Exception) {
                    if (++attempt >= 4) throw e
                    log("WARNING", "Sending the recording to the server failed (${e.message}); trying again")
                    pause(10_000L * attempt)
                }
            }
            file.delete()
            log("INFO", "Sent a recording of $frames frames to the server")
            job = null
        } catch (e: Exception) {
            log("WARNING", "The recording the server asked for failed: ${e.message}")
            job = mapOf("id" to id, "state" to "failed", "frames" to recorder.frames, "error" to (e.message ?: e.toString()).take(200))
        }
    }
}
