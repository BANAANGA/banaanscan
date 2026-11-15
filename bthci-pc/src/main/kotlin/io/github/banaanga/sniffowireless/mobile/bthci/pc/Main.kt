package io.github.banaanga.sniffowireless.mobile.bthci.pc

import io.github.banaanga.sniffowireless.mobile.bthci.Advert
import io.github.banaanga.sniffowireless.mobile.bthci.Hci
import io.github.banaanga.sniffowireless.mobile.bthci.HciException
import io.github.banaanga.sniffowireless.mobile.bthci.HciIo
import io.github.banaanga.sniffowireless.mobile.bthci.InquiryComplete
import io.github.banaanga.sniffowireless.mobile.bthci.InquiryResponse
import io.github.banaanga.sniffowireless.mobile.bthci.Scanner
import io.github.banaanga.sniffowireless.mobile.bthci.hex
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
import kotlin.system.exitProcess

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
    fun libusb_interrupt_transfer(handle: Pointer, endpoint: Byte, data: ByteArray, length: Int, transferred: IntByReference, timeout: Int): Int
    fun libusb_error_name(code: Int): String

    companion object {
        val lib: LibUsb by lazy { Native.load("usb-1.0", LibUsb::class.java) }
    }
}

private const val TIMEOUT = -7

private class LibUsbHci(private val handle: Pointer, private val trace: Boolean) : HciIo {
    private val usb = LibUsb.lib

    override fun sendCommand(packet: ByteArray, timeoutMs: Int) {
        if (trace) System.err.println("  cmd   ${packet.hex().take(80)}")
        val n = usb.libusb_control_transfer(handle, 0x20, 0, 0, 0, packet, packet.size.toShort(), timeoutMs)
        if (n < 0) throw HciException("Sending a command failed: ${usb.libusb_error_name(n)}")
    }

    override fun readEvents(buffer: ByteArray, timeoutMs: Int): Int {
        val done = IntByReference()
        val rc = usb.libusb_interrupt_transfer(handle, 0x81.toByte(), buffer, buffer.size, done, timeoutMs)
        if (rc == TIMEOUT) return done.value
        if (rc < 0) throw HciException("Reading events failed: ${usb.libusb_error_name(rc)}")
        if (trace) System.err.println("  event ${buffer.copyOf(done.value).hex().take(80)}")
        return done.value
    }
}

private class Seen(val address: String) {
    var name: String? = null
    var rssi: Int? = null
    var packets = 0
    var random = false
    var classic = false
    var cod: Int? = null
    val phys = sortedSetOf<String>()
    val companies = sortedSetOf<Int>()
    val uuids = sortedSetOf<String>()
}

/** Reads a firmware file from [dir], decompressing linux-firmware's .zst copies with the zstd tool. */
private fun firmwareFrom(dir: File, name: String): ByteArray? {
    File(dir, name).takeIf { it.isFile }?.let { return it.readBytes() }
    val zst = File(dir, "$name.zst").takeIf { it.isFile } ?: return null
    val p = ProcessBuilder("zstd", "-dc", zst.path).start()
    return p.inputStream.readBytes().also { p.waitFor() }
}

fun main(args: Array<String>) {
    val opts = args.associate { a -> a.removePrefix("--").split("=", limit = 2).let { it[0] to it.getOrElse(1) { "" } } }
    val vid = opts["vid"]?.toInt(16) ?: 0x2357
    val pid = opts["pid"]?.toInt(16) ?: 0x0604
    val seconds = opts["seconds"]?.toInt() ?: 20
    val passive = "passive" in opts
    val wantClassic = "no-classic" !in opts
    val coded = "no-coded" !in opts
    val trace = "trace" in opts
    val fwDir = File(opts["firmware"] ?: "/usr/lib/firmware/rtl_bt")

    val usb = LibUsb.lib
    val ctx = PointerByReference()
    check(usb.libusb_init(ctx) == 0) { "libusb_init failed" }
    val handle = usb.libusb_open_device_with_vid_pid(ctx.value, vid.toShort(), pid.toShort())
        ?: run { System.err.println("No %04x:%04x adapter, or no permission (see tools/bthci/README.md)".format(vid, pid)); exitProcess(1) }
    if ("reset" in opts) println("USB reset: ${usb.libusb_reset_device(handle)}")
    val hadKernel = usb.libusb_kernel_driver_active(handle, 0) == 1
    if (hadKernel) check(usb.libusb_detach_kernel_driver(handle, 0) == 0) { "Couldn't detach Linux's btusb driver" }
    var rc = 0
    try {
        check(usb.libusb_claim_interface(handle, 0) == 0) { "Couldn't claim interface 0" }
        val hci = Hci(LibUsbHci(handle, trace))
        val scanner = Scanner(hci) { println(it) }
        val t0 = System.currentTimeMillis()
        val info = scanner.open { firmwareFrom(fwDir, it) }
        println("Ready after ${System.currentTimeMillis() - t0} ms: $info")
        scanner.startLe(active = !passive, coded = coded && info.codedPhy)
        val inquiry = wantClassic && !passive && info.classic
        if (inquiry) scanner.startInquiry()
        println("Scanning for $seconds s (${if (passive) "passive" else "active"}${if (inquiry) " + classic inquiry" else ""})")
        val seen = LinkedHashMap<String, Seen>()
        var events = 0
        val end = System.currentTimeMillis() + seconds * 1000L
        while (System.currentTimeMillis() < end) {
            for (h in scanner.poll(200)) {
                events++
                when (h) {
                    is Advert -> seen.getOrPut(h.address) { Seen(h.address) }.apply {
                        packets++
                        rssi = h.rssi ?: rssi
                        random = h.random
                        h.ad.name?.let { name = it }
                        phys += listOf("-", "1M", "2M", "Coded").getOrElse(h.primaryPhy) { "?" } + if (h.legacy) "" else " ext"
                        companies += h.ad.manufacturer.keys
                        uuids += h.ad.uuids
                    }
                    is InquiryResponse -> seen.getOrPut(h.address) { Seen(h.address) }.apply {
                        packets++
                        classic = true
                        rssi = h.rssi ?: rssi
                        cod = h.classOfDevice
                        h.eir?.name?.let { name = it }
                    }
                    InquiryComplete -> if (inquiry) scanner.startInquiry()
                }
            }
        }
        scanner.stop()
        println("\n$events reports from ${seen.size} devices:")
        for (s in seen.values.sortedByDescending { it.rssi ?: -200 }) {
            println("%s %4s dBm %5d  %-24s %s%s%s%s%s".format(
                s.address, s.rssi ?: "?", s.packets, s.name ?: "", if (s.random) "random " else "",
                if (s.classic) "classic cod=%06x ".format(s.cod ?: 0) else "", s.phys.joinToString("/"),
                if (s.companies.isNotEmpty()) " companies=" + s.companies.joinToString(",") { "%04x".format(it) } else "",
                if (s.uuids.isNotEmpty()) " uuids=" + s.uuids.joinToString(",") else ""))
        }
    } catch (e: Exception) {
        System.err.println("Failed: ${e.message}")
        rc = 1
    } finally {
        usb.libusb_release_interface(handle, 0)
        if (hadKernel) usb.libusb_attach_kernel_driver(handle, 0)
        usb.libusb_close(handle)
        usb.libusb_exit(ctx.value)
    }
    exitProcess(rc)
}
