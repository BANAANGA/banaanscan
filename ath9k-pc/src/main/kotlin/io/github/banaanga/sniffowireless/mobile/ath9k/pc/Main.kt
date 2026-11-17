package io.github.banaanga.sniffowireless.mobile.ath9k.pc

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import io.github.banaanga.sniffowireless.mobile.ath9k.Ath9kException
import io.github.banaanga.sniffowireless.mobile.ath9k.Ath9kHtc
import io.github.banaanga.sniffowireless.mobile.ath9k.Channel
import io.github.banaanga.sniffowireless.mobile.ath9k.UsbIo
import java.io.DataOutputStream
import java.io.File
import java.io.PrintWriter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.system.exitProcess

/** The handful of libusb-1.0 calls the prototype needs. */
@Suppress("FunctionName")
private interface LibUsb : Library {
    fun libusb_init(ctx: PointerByReference?): Int
    fun libusb_exit(ctx: Pointer?)
    fun libusb_open_device_with_vid_pid(ctx: Pointer?, vendor: Short, product: Short): Pointer?
    fun libusb_close(handle: Pointer)
    fun libusb_kernel_driver_active(handle: Pointer, iface: Int): Int
    fun libusb_detach_kernel_driver(handle: Pointer, iface: Int): Int
    fun libusb_attach_kernel_driver(handle: Pointer, iface: Int): Int
    fun libusb_claim_interface(handle: Pointer, iface: Int): Int
    fun libusb_release_interface(handle: Pointer, iface: Int): Int
    fun libusb_reset_device(handle: Pointer): Int
    fun libusb_control_transfer(handle: Pointer, type: Byte, request: Byte, value: Short, index: Short, data: ByteArray?, length: Short, timeout: Int): Int
    fun libusb_bulk_transfer(handle: Pointer, endpoint: Byte, data: ByteArray, length: Int, transferred: IntByReference, timeout: Int): Int
    fun libusb_interrupt_transfer(handle: Pointer, endpoint: Byte, data: ByteArray, length: Int, transferred: IntByReference, timeout: Int): Int
    fun libusb_error_name(code: Int): String

    companion object {
        val lib: LibUsb by lazy { Native.load("usb-1.0", LibUsb::class.java) }
    }
}

private const val TIMEOUT = -7

/** libusb behind [UsbIo]. Endpoints 3 and 4 are interrupt endpoints on the AR9271; libusb wants the matching call. */
private class LibUsbIo(private val handle: Pointer) : UsbIo {
    private val usb = LibUsb.lib

    override fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        val buf = ByteArray(length)
        val n = usb.libusb_control_transfer(handle, requestType.toByte(), request.toByte(), value.toShort(), index.toShort(), buf, length.toShort(), timeoutMs)
        return if (n == length) buf else null
    }

    override fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = usb.libusb_control_transfer(handle, requestType.toByte(), request.toByte(), value.toShort(), index.toShort(), data.takeIf { it.isNotEmpty() }, data.size.toShort(), timeoutMs)
        if (n < 0) throw Ath9kException("Control transfer failed: ${usb.libusb_error_name(n)}")
        return n
    }

    private fun transfer(endpoint: Int, data: ByteArray, length: Int, timeoutMs: Int): Int {
        val done = IntByReference()
        val rc = if (endpoint and 0x7f in 3..4) usb.libusb_interrupt_transfer(handle, endpoint.toByte(), data, length, done, timeoutMs)
        else usb.libusb_bulk_transfer(handle, endpoint.toByte(), data, length, done, timeoutMs)
        if (rc == TIMEOUT) return 0
        if (rc < 0) throw Ath9kException("Transfer on 0x%02x failed: %s".format(endpoint, usb.libusb_error_name(rc)))
        return done.value
    }

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int) = transfer(endpoint, data, data.size, timeoutMs)
    override fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int) = transfer(endpoint, buffer, buffer.size, timeoutMs)
}

/** Reads a firmware file, decompressing the .zst copies Linux distributions ship. */
private fun readFirmware(path: String): ByteArray {
    val file = File(path)
    require(file.isFile) { "Firmware file not found: $path" }
    if (!path.endsWith(".zst")) return file.readBytes()
    val p = ProcessBuilder("zstd", "-dc", path).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val bytes = p.inputStream.readBytes()
    if (p.waitFor() != 0) throw IllegalStateException("zstd couldn't decompress $path")
    return bytes
}

/** pcap with a small radiotap header (channel, signal), as the app writes it. */
private class Pcap(file: File) : AutoCloseable {
    private val out = DataOutputStream(file.outputStream().buffered())

    init {
        out.write(ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0xa1b2c3d4.toInt()).putShort(2).putShort(4).putInt(0).putInt(0).putInt(65535).putInt(127).array())
    }

    fun write(freq: Int, rssi: Int?, frame: ByteArray) {
        val rt = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        rt.put(0).put(0).putShort(16).putInt((1 shl 3) or (if (rssi != null) 1 shl 5 else 0))
        rt.putShort(freq.toShort()).putShort(0x0080).put((rssi ?: 0).toByte()).put(0).put(0).put(0)
        val now = System.currentTimeMillis()
        out.write(ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
            .putInt((now / 1000).toInt()).putInt(((now % 1000) * 1000).toInt()).putInt(16 + frame.size).putInt(16 + frame.size).array())
        out.write(rt.array())
        out.write(frame)
    }

    override fun close() = out.close()
}

/**
 * Usage: ath9k-pc [--channels=2412,2437,2462] [--seconds=12] [--pcap=ar9271.pcap] [--regs=regs.log] [--firmware=PATH]
 *        [--fast] (fast channel changes) [--replay=DIR] (diff against a Linux trace, see tools/ath9k)
 * Takes the adapter from Linux's ath9k_htc driver for the run and hands it back afterwards.
 */
fun main(args: Array<String>) {
    fun opt(name: String) = args.firstOrNull { it.startsWith("--$name=") }?.substringAfter('=')
    opt("replay")?.let { replay(File(it)); return }
    val channels = (opt("channels") ?: "2412,2437,2462").split(",").map { Channel(it.trim().toInt()) }
    val seconds = opt("seconds")?.toInt() ?: 12
    val pcapPath = opt("pcap") ?: "ar9271.pcap"
    val regsPath = opt("regs")
    val fw = readFirmware(opt("firmware") ?: "/lib/firmware/ath9k_htc/htc_9271-1.4.0.fw.zst")
    val usb = LibUsb.lib
    val ctx = PointerByReference()
    check(usb.libusb_init(ctx) == 0)
    val handle = usb.libusb_open_device_with_vid_pid(ctx.value, 0x0cf3.toShort(), 0x9271.toShort())
        ?: run { System.err.println("No AR9271 (0cf3:9271) found, or no permission (install tools/ath9k/70-ar9271.rules)"); exitProcess(1) }
    var detached = false
    val regLog = regsPath?.let { PrintWriter(File(it).bufferedWriter(), true) }
    try {
        if (usb.libusb_kernel_driver_active(handle, 0) == 1) {
            // Linux reboots the chip into its boot ROM when its driver lets go (ath9k_hif_usb_disconnect).
            check(usb.libusb_detach_kernel_driver(handle, 0) == 0) { "Couldn't detach the kernel driver" }
            detached = true
            Thread.sleep(1000)
        }
        if ("--reset" in args) println("USB reset: " + usb.libusb_error_name(usb.libusb_reset_device(handle)))
        check(usb.libusb_claim_interface(handle, 0) == 0) { "Couldn't claim the interface" }
        val dev = Ath9kHtc(LibUsbIo(handle), { println(it) }, regLog?.let { w -> { line: String -> w.println(line) } })
        val t0 = System.currentTimeMillis()
        dev.bringUp(fw)
        println("Brought up in %d ms: %d register writes, %d reads".format(System.currentTimeMillis() - t0, dev.regs.writes, dev.regs.reads))
        regLog?.println("# monitor")
        dev.startMonitor(channels.first())
        Pcap(File(pcapPath)).use { pcap ->
            for ((i, ch) in channels.withIndex()) {
                if (i > 0) {
                    regLog?.println("# channel ${ch.freqMhz}")
                    val t = System.currentTimeMillis()
                    dev.setChannel(ch, fast = "--fast" in args)
                    println("Tuned to %d MHz in %d ms (nf polls %d)".format(ch.freqMhz, System.currentTimeMillis() - t, dev.hw.lastNfPolls))
                }
                var frames = 0
                var bad = 0
                val ssids = HashSet<String>()
                val rssis = ArrayList<Int>()
                val end = System.currentTimeMillis() + seconds * 1000L
                while (System.currentTimeMillis() < end) {
                    val f = dev.receive(200) ?: continue
                    if (f.fcsError) { bad++; continue }
                    frames++
                    f.rssi?.let { rssis += it }
                    pcap.write(f.freqMhz, f.rssi, f.data)
                    // beacons: SSID element right after the fixed fields
                    if (f.data.size > 38 && f.data[0].toInt() and 0xFC == 0x80 && f.data[36].toInt() == 0) {
                        val n = f.data[37].toInt() and 0xFF
                        if (38 + n <= f.data.size) ssids += String(f.data, 38, n, Charsets.UTF_8).ifEmpty { "<hidden>" }
                    }
                }
                println("%d MHz: %d frames, %d with bad FCS, %d networks: %s".format(ch.freqMhz, frames, bad, ssids.size, ssids.take(12).joinToString()))
                rssis.sort()
                if (rssis.isNotEmpty()) println("  signal: min %d, median %d, max %d dBm".format(rssis.first(), rssis[rssis.size / 2], rssis.last()))
            }
        }
        dev.close()
    } catch (e: Exception) {
        System.err.println("Failed: ${e.message}")
        e.printStackTrace()
    } finally {
        regLog?.close()
        usb.libusb_release_interface(handle, 0)
        if (detached) usb.libusb_attach_kernel_driver(handle, 0)
        usb.libusb_close(handle)
        usb.libusb_exit(ctx.value)
    }
}
