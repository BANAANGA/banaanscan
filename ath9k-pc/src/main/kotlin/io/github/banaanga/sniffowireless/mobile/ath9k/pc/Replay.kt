package io.github.banaanga.sniffowireless.mobile.ath9k.pc

import io.github.banaanga.sniffowireless.mobile.ath9k.Ar9271
import io.github.banaanga.sniffowireless.mobile.ath9k.Channel
import io.github.banaanga.sniffowireless.mobile.ath9k.Regs
import java.io.File
import java.io.PrintWriter

/**
 * Register access against a recording of Linux's driver (tools/ath9k/decode_trace.py --regs): reads are answered with
 * the values Linux read from the same register at that point, and every operation is logged in the same format, so the
 * port's register traffic can be diffed against Linux's without the adapter.
 */
private class ReplayRegs(private val out: PrintWriter) : Regs {
    private var reads: List<Pair<Int, Int>> = emptyList()
    private var pos = 0
    private val last = HashMap<Int, Int>()

    fun load(file: File) {
        reads = file.readLines().filter { it.startsWith("R ") }.map { l ->
            val p = l.split(" ")
            p[1].toLong(16).toInt() to p[3].toLong(16).toInt()
        }
        pos = 0
    }

    override fun read(reg: Int): Int {
        out.println("R %08x".format(reg))
        for (i in pos until reads.size) {
            if (reads[i].first == reg) {
                pos = i + 1
                last[reg] = reads[i].second
                return reads[i].second
            }
        }
        return last[reg] ?: reads.firstOrNull { it.first == reg }?.second ?: 0
    }

    override fun write(reg: Int, value: Int) = out.println("W %08x %08x".format(reg, value))
    override fun rmw(reg: Int, set: Int, clr: Int) = out.println("M %08x %08x %08x".format(reg, set, clr))
    override fun flush() {}
    override fun delayUs(us: Int) {}
}

/** ath9k-pc --replay=DIR: runs init against DIR/linux_init.log and a reset to 2437 MHz against DIR/linux_ch6.log. */
fun replay(dir: File) {
    PrintWriter(File(dir, "ours_init.log").bufferedWriter()).use { initOut ->
        val r = ReplayRegs(initOut)
        r.load(File(dir, "linux_init.log"))
        val hw = Ar9271(r) { println(it) }
        hw.init()
        hw.disable()
        initOut.flush()
        PrintWriter(File(dir, "ours_ch6.log").bufferedWriter()).use { chOut ->
            val r2 = ReplayRegs(chOut)
            r2.load(File(dir, "linux_ch6.log"))
            val field = Ar9271::class.java.getDeclaredField("r").apply { isAccessible = true }
            field.set(hw, r2)
            hw.reset(Channel(2437))
            chOut.println("C START_RECV")
            hw.startReceive()
        }
    }
    println("Wrote ours_init.log and ours_ch6.log in $dir")
}
