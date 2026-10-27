package com.example.banaanscan.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Offline MAC vendor lookup backed by Wireshark's "manuf" file, which covers the IEEE
 * MA-L (/24), MA-M (/28) and MA-S (/36) registries. The file is downloaded on first
 * launch; drop a copy at assets/manuf to ship it inside the APK instead.
 */
object OuiDatabase {
    const val SOURCE_URL = "https://www.wireshark.org/download/automated/data/manuf"
    private const val FILE_NAME = "manuf.txt"
    private const val ASSET_NAME = "manuf"

    // prefix length in bits -> (uppercase hex prefix -> vendor)
    @Volatile
    private var tables: Map<Int, Map<String, String>> = emptyMap()

    val size: Int get() = tables.values.sumOf { it.size }

    suspend fun init(context: Context): Int = withContext(Dispatchers.IO) {
        if (size > 0) return@withContext size
        val file = File(context.filesDir, FILE_NAME)
        val parsed = file.takeIf { it.exists() }
            ?.let { f -> runCatching { f.bufferedReader().use(::parse) }.getOrNull() }
            ?: runCatching { context.assets.open(ASSET_NAME).bufferedReader().use(::parse) }.getOrNull()
        if (parsed != null) tables = parsed
        size
    }

    suspend fun download(context: Context): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL(SOURCE_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                setRequestProperty("User-Agent", "BanaanScan/0.1 (Android)")
            }
            try {
                check(conn.responseCode == 200) { "HTTP ${conn.responseCode}" }
                val tmp = File(context.filesDir, "$FILE_NAME.tmp")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                val parsed = tmp.bufferedReader().use(::parse)
                val count = parsed.values.sumOf { it.size }
                check(count > 1000) { "Downloaded file looks wrong ($count entries)" }
                tmp.renameTo(File(context.filesDir, FILE_NAME))
                tables = parsed
                count
            } finally {
                conn.disconnect()
            }
        }
    }

    private fun parse(reader: BufferedReader): Map<Int, Map<String, String>> {
        val out = HashMap<Int, HashMap<String, String>>()
        reader.forEachLine { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachLine
            val parts = line.split('\t').map { it.trim() }.filter { it.isNotEmpty() }
            if (parts.size < 2) return@forEachLine
            val prefixAndMask = parts[0].split('/')
            val hex = normalize(prefixAndMask[0])
            val bits = prefixAndMask.getOrNull(1)?.toIntOrNull() ?: (hex.length * 4)
            if (bits % 4 != 0 || bits == 0 || hex.length * 4 < bits) return@forEachLine
            val vendor = (parts.getOrNull(2) ?: parts[1]).removePrefix("#").trim()
            out.getOrPut(bits) { HashMap() }[hex.take(bits / 4)] = vendor
        }
        return out
    }

    fun normalize(mac: String): String =
        mac.uppercase().filter { it in '0'..'9' || it in 'A'..'F' }

    fun format(mac: String): String = normalize(mac).chunked(2).joinToString(":")

    fun lookup(mac: String?): String? {
        if (mac == null) return null
        val hex = normalize(mac)
        if (hex.length < 6) return null
        val snapshot = tables
        for (bits in snapshot.keys.sortedDescending()) {
            if (hex.length * 4 < bits) continue
            snapshot[bits]?.get(hex.take(bits / 4))?.let { return it }
        }
        return null
    }

    /** Locally administered bit: set on randomized/private Wi-Fi MACs and many virtual interfaces. */
    fun isLocallyAdministered(mac: String): Boolean = firstOctet(mac)?.let { (it and 0x02) != 0 } ?: false

    fun isMulticast(mac: String): Boolean = firstOctet(mac)?.let { (it and 0x01) != 0 } ?: false

    private fun firstOctet(mac: String): Int? = normalize(mac).take(2).takeIf { it.length == 2 }?.toInt(16)
}
