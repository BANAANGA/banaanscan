package io.github.banaanga.sniffowireless.mobile.ath9k

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Register access as ath9k_hw uses it (REG_READ, REG_WRITE, REG_RMW…), over the firmware's WMI commands. */
interface Regs {
    fun read(reg: Int): Int
    /** Up to 8 registers in one go (REG_READ_MULTI). */
    fun readMulti(regs: IntArray): IntArray = IntArray(regs.size) { read(regs[it]) }
    fun write(reg: Int, value: Int)
    /** reg = (reg & ~clr) | set */
    fun rmw(reg: Int, set: Int, clr: Int)
    /** Sends buffered writes now. */
    fun flush()
    fun delayUs(us: Int)

    fun setBit(reg: Int, bits: Int) = rmw(reg, bits, 0)
    fun clrBit(reg: Int, bits: Int) = rmw(reg, 0, bits)
    /** REG_RMW_FIELD: writes [value] into the bits of [mask]. */
    fun rmwField(reg: Int, mask: Int, value: Int) = rmw(reg, (value shl Integer.numberOfTrailingZeros(mask)) and mask, mask)
    fun readField(reg: Int, mask: Int) = (read(reg) and mask) ushr Integer.numberOfTrailingZeros(mask)

    /** ath9k_hw_wait: polls until (reg & mask) == value, every 10 µs up to [timeoutUs]. */
    fun waitFor(reg: Int, mask: Int, value: Int, timeoutUs: Int = 100_000): Boolean {
        val end = System.nanoTime() + timeoutUs * 1000L
        while (true) {
            if (read(reg) and mask == value) return true
            if (System.nanoTime() > end) return false
            delayUs(10)
        }
    }
}

/**
 * [Regs] over WMI. Writes are buffered (as ath9k_htc's write buffer does) and go out in batches of up to 62; they are
 * flushed before any read, read-modify-write or delay, so the chip sees everything in the order and with the pauses the
 * code asks for.
 */
class WmiRegs(private val htc: Htc, private val trace: ((String) -> Unit)? = null) : Regs {
    private val pending = ArrayList<Long>()
    var writes = 0L
        private set
    var reads = 0L
        private set

    override fun read(reg: Int): Int {
        flush()
        val rsp = htc.wmi(Htc.WMI_REG_READ, ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(reg).array(), 1000)
        if (rsp.size < 4) throw Ath9kException("Short answer reading register 0x%04x".format(reg))
        reads++
        val v = be32(rsp, 0)
        trace?.invoke("R %08x = %08x".format(reg, v))
        return v
    }

    override fun readMulti(regs: IntArray): IntArray {
        require(regs.size in 1..8)
        flush()
        val b = ByteBuffer.allocate(4 * regs.size).order(ByteOrder.BIG_ENDIAN)
        regs.forEach { b.putInt(it) }
        val rsp = htc.wmi(Htc.WMI_REG_READ, b.array(), 1000)
        if (rsp.size < 4 * regs.size) throw Ath9kException("Short answer reading %d registers".format(regs.size))
        reads += regs.size
        return IntArray(regs.size) { be32(rsp, 4 * it) }.also { v -> trace?.let { t -> regs.indices.forEach { t("R %08x = %08x".format(regs[it], v[it])) } } }
    }

    override fun write(reg: Int, value: Int) {
        trace?.invoke("W %08x : %08x".format(reg, value))
        pending += (reg.toLong() shl 32) or (value.toLong() and 0xFFFFFFFFL)
        writes++
        if (pending.size >= MAX_WRITES) flush()
    }

    override fun rmw(reg: Int, set: Int, clr: Int) {
        flush()
        trace?.invoke("M %08x set %08x clr %08x".format(reg, set, clr))
        htc.wmi(Htc.WMI_REG_RMW, ByteBuffer.allocate(12).order(ByteOrder.BIG_ENDIAN).putInt(reg).putInt(set).putInt(clr).array(), 1000)
    }

    override fun flush() {
        if (pending.isEmpty()) return
        val b = ByteBuffer.allocate(8 * pending.size).order(ByteOrder.BIG_ENDIAN)
        pending.forEach { b.putInt((it ushr 32).toInt()).putInt(it.toInt()) }
        pending.clear()
        htc.wmi(Htc.WMI_REG_WRITE, b.array(), 1000)
    }

    override fun delayUs(us: Int) {
        flush()
        if (us >= 1000) Thread.sleep(us / 1000L, (us % 1000) * 1000)
        else {
            val end = System.nanoTime() + us * 1000L
            while (System.nanoTime() < end) Thread.yield()  // (Thread.onSpinWait isn't in Android before 14)
        }
    }

    private companion object {
        const val MAX_WRITES = 62
    }
}
