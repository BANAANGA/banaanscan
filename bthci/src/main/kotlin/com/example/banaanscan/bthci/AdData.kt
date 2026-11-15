package com.example.banaanscan.bthci

/**
 * Advertising data and extended inquiry response data: both are a list of length, type, value (Core Supplement part A).
 * UUIDs come out as on the phone side: 16-bit ones as four hex digits, others as full UUID strings.
 */
class AdData(
    val name: String?,
    val completeName: Boolean,
    val flags: Int?,
    val uuids: List<String>,
    val serviceData: Map<String, ByteArray>,
    val manufacturer: Map<Int, ByteArray>,
    val txPower: Int?,
    val appearance: Int?,
) {
    companion object {
        val EMPTY = AdData(null, false, null, emptyList(), emptyMap(), emptyMap(), null, null)

        fun parse(d: ByteArray): AdData {
            var name: String? = null
            var complete = false
            var flags: Int? = null
            val uuids = ArrayList<String>()
            val serviceData = LinkedHashMap<String, ByteArray>()
            val manufacturer = LinkedHashMap<Int, ByteArray>()
            var tx: Int? = null
            var appearance: Int? = null
            var i = 0
            while (i < d.size) {
                val len = d[i].u8
                if (len == 0) break // padding; EIR data is zero-filled to 240 bytes
                if (i + 1 + len > d.size) break
                val type = d[i + 1].u8
                val v = d.copyOfRange(i + 2, i + 1 + len)
                when (type) {
                    0x01 -> if (v.isNotEmpty()) flags = v[0].u8
                    0x02, 0x03 -> for (j in 0 until v.size / 2) uuids += uuid16(v.u16(2 * j))
                    0x04, 0x05 -> for (j in 0 until v.size / 4) uuids += uuid32(v.u32(4 * j))
                    0x06, 0x07 -> for (j in 0 until v.size / 16) uuids += uuid128(v, 16 * j)
                    0x08 -> if (!complete) name = text(v)
                    0x09 -> { name = text(v); complete = true }
                    0x0A -> if (v.isNotEmpty()) tx = v[0].toInt()
                    0x16 -> if (v.size >= 2) serviceData[uuid16(v.u16(0))] = v.copyOfRange(2, v.size)
                    0x19 -> if (v.size >= 2) appearance = v.u16(0)
                    0x20 -> if (v.size >= 4) serviceData[uuid32(v.u32(0))] = v.copyOfRange(4, v.size)
                    0x21 -> if (v.size >= 16) serviceData[uuid128(v, 0)] = v.copyOfRange(16, v.size)
                    0xFF -> if (v.size >= 2) manufacturer[v.u16(0)] = v.copyOfRange(2, v.size)
                }
                i += 1 + len
            }
            return AdData(name, complete, flags, uuids.distinct(), serviceData, manufacturer, tx, appearance)
        }

        private fun text(v: ByteArray) = String(v, Charsets.UTF_8).trimEnd('\u0000').takeIf { it.isNotBlank() }

        private const val BASE = "-0000-1000-8000-00805f9b34fb"
        fun uuid16(v: Int) = "%04x".format(v)
        fun uuid32(v: Int) = if (v ushr 16 == 0) uuid16(v) else "%08x".format(v) + BASE

        fun uuid128(b: ByteArray, at: Int): String {
            val h = (15 downTo 0).joinToString("") { "%02x".format(b[at + it]) }
            val s = "${h.substring(0, 8)}-${h.substring(8, 12)}-${h.substring(12, 16)}-${h.substring(16, 20)}-${h.substring(20)}"
            return if (s.startsWith("0000") && s.endsWith(BASE)) s.substring(4, 8) else s
        }
    }
}
