package io.github.banaanga.sniffowireless.mobile.bthci

class HciException(message: String) : Exception(message)

/**
 * The two USB transfers HCI needs (Bluetooth Core spec, vol 4 part B): commands go out as class requests on the
 * control endpoint, events come back on the interrupt endpoint (0x81).
 */
interface HciIo {
    fun sendCommand(packet: ByteArray, timeoutMs: Int)

    /** Reads whatever event bytes are waiting into [buffer]; 0 when nothing arrived within [timeoutMs]. */
    fun readEvents(buffer: ByteArray, timeoutMs: Int): Int
}

class Event(val code: Int, val params: ByteArray) {
    override fun toString() = "event 0x%02x %s".format(code, params.hex())
}

/**
 * Splits the event byte stream back into events. A USB transfer can hold part of an event (they're split into
 * 16-byte packets) or, when an event is a multiple of the packet size, run on into the next one.
 */
internal class EventReassembler {
    private var buf = ByteArray(0)

    fun feed(data: ByteArray, length: Int) {
        buf += data.copyOf(length)
    }

    fun clear() {
        buf = ByteArray(0)
    }

    fun poll(): Event? {
        if (buf.size < 2) return null
        val len = buf[1].u8
        if (buf.size < 2 + len) return null
        val e = Event(buf[0].u8, buf.copyOfRange(2, 2 + len))
        buf = buf.copyOfRange(2 + len, buf.size)
        return e
    }
}

/** Sends commands and waits for their answers; any other event that arrives meanwhile is kept for [nextEvent]. */
class Hci(private val io: HciIo, private val log: (String) -> Unit = {}) {
    private val pending = ArrayDeque<Event>()
    private val reassembler = EventReassembler()
    private val buf = ByteArray(1024)

    /**
     * Sends a command and returns its answer, starting with the status byte: the return parameters of Command
     * Complete, or just the status for commands that answer with Command Status.
     */
    fun command(opcode: Int, params: ByteArray = ByteArray(0), timeoutMs: Int = 2000): ByteArray {
        require(params.size <= 255)
        io.sendCommand(byteArrayOf(opcode.toByte(), (opcode shr 8).toByte(), params.size.toByte()) + params, timeoutMs)
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            val left = deadline - System.currentTimeMillis()
            if (left <= 0) throw HciException("The adapter didn't answer command 0x%04x".format(opcode))
            val e = read(left.toInt()) ?: continue
            val p = e.params
            when {
                e.code == EVT_COMMAND_COMPLETE && p.size >= 3 && p.u16(1) == opcode -> return p.copyOfRange(3, p.size)
                e.code == EVT_COMMAND_STATUS && p.size >= 4 && p.u16(2) == opcode -> return byteArrayOf(p[0])
                else -> pending.addLast(e)
            }
        }
    }

    /** Like [command], but a non-zero status is an error. Returns the answer after the status byte. */
    fun commandOk(opcode: Int, params: ByteArray = ByteArray(0), timeoutMs: Int = 2000): ByteArray {
        val r = command(opcode, params, timeoutMs)
        if (r.isEmpty() || r[0].toInt() != 0) {
            throw HciException("Command 0x%04x failed (status 0x%02x)".format(opcode, r.firstOrNull()?.u8 ?: -1))
        }
        return r.copyOfRange(1, r.size)
    }

    /**
     * Throws away whatever is waiting on the event endpoint. Needed when taking over an adapter that was busy: a
     * half-read event would otherwise swallow the answers that follow it.
     */
    fun flush(quietMs: Int = 100) {
        pending.clear()
        reassembler.clear()
        val end = System.currentTimeMillis() + 2000
        while (System.currentTimeMillis() < end && io.readEvents(buf, quietMs) > 0) Unit
    }

    fun nextEvent(timeoutMs: Int): Event? = pending.removeFirstOrNull() ?: read(timeoutMs)

    private fun read(timeoutMs: Int): Event? {
        reassembler.poll()?.let { return it }
        val n = io.readEvents(buf, timeoutMs.coerceIn(1, 500))
        if (n > 0) reassembler.feed(buf, n)
        return reassembler.poll()
    }

    companion object {
        const val EVT_INQUIRY_COMPLETE = 0x01
        const val EVT_INQUIRY_RESULT = 0x02
        const val EVT_COMMAND_COMPLETE = 0x0E
        const val EVT_COMMAND_STATUS = 0x0F
        const val EVT_INQUIRY_RESULT_RSSI = 0x22
        const val EVT_EXTENDED_INQUIRY_RESULT = 0x2F
        const val EVT_LE_META = 0x3E

        const val INQUIRY = 0x0401
        const val INQUIRY_CANCEL = 0x0402
        const val SET_EVENT_MASK = 0x0C01
        const val RESET = 0x0C03
        const val WRITE_INQUIRY_MODE = 0x0C45
        const val READ_LOCAL_VERSION = 0x1001
        const val READ_LOCAL_FEATURES = 0x1003
        const val READ_BD_ADDR = 0x1009
        const val LE_SET_EVENT_MASK = 0x2001
        const val LE_READ_LOCAL_FEATURES = 0x2003
        const val LE_SET_RANDOM_ADDRESS = 0x2005
        const val LE_SET_SCAN_PARAMETERS = 0x200B
        const val LE_SET_SCAN_ENABLE = 0x200C
        const val LE_SET_EXT_SCAN_PARAMETERS = 0x2041
        const val LE_SET_EXT_SCAN_ENABLE = 0x2042
    }
}

internal val Byte.u8 get() = toInt() and 0xFF
internal fun ByteArray.u16(at: Int) = this[at].u8 or (this[at + 1].u8 shl 8)
internal fun ByteArray.u24(at: Int) = u16(at) or (this[at + 2].u8 shl 16)
internal fun ByteArray.u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
internal fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())
fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

/** HCI sends addresses least significant byte first; this is the usual written form. */
internal fun ByteArray.address(at: Int) = (5 downTo 0).joinToString(":") { "%02X".format(this[at + it]) }
