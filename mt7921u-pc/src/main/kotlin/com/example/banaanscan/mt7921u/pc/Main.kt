package com.example.banaanscan.mt7921u.pc

import com.example.banaanscan.mt7921u.Mt7921Exception
import com.example.banaanscan.mt7921u.Mt7921u
import com.example.banaanscan.mt7921u.PatchFile
import com.example.banaanscan.mt7921u.RamFile
import com.example.banaanscan.mt7921u.UsbIo
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.io.File
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
    fun libusb_error_name(code: Int): String

    companion object {
        val lib: LibUsb by lazy { Native.load("usb-1.0", LibUsb::class.java) }
    }
}

private const val TIMEOUT = -7

private class LibUsbIo(private val handle: Pointer, private val trace: Boolean) : UsbIo {
    private val usb = LibUsb.lib

    override fun controlIn(requestType: Int, request: Int, value: Int, index: Int, length: Int, timeoutMs: Int): ByteArray? {
        val buf = ByteArray(length)
        val n = usb.libusb_control_transfer(handle, requestType.toByte(), request.toByte(), value.toShort(), index.toShort(), buf, length.toShort(), timeoutMs)
        if (trace) System.err.println("  ctl in  %02x %02x %04x %04x -> %s".format(requestType, request, value, index, if (n >= 0) buf.copyOf(n).hex() else usb.libusb_error_name(n)))
        return if (n == length) buf else null
    }

    override fun controlOut(requestType: Int, request: Int, value: Int, index: Int, data: ByteArray, timeoutMs: Int): Int {
        val n = usb.libusb_control_transfer(handle, requestType.toByte(), request.toByte(), value.toShort(), index.toShort(), data.takeIf { it.isNotEmpty() }, data.size.toShort(), timeoutMs)
        if (trace) System.err.println("  ctl out %02x %02x %04x %04x %s -> %d".format(requestType, request, value, index, data.hex(), n))
        if (n < 0) throw Mt7921Exception("Control transfer failed: ${usb.libusb_error_name(n)}")
        return n
    }

    override fun bulkOut(endpoint: Int, data: ByteArray, timeoutMs: Int): Int {
        val done = IntByReference()
        val rc = usb.libusb_bulk_transfer(handle, endpoint.toByte(), data, data.size, done, timeoutMs)
        if (trace && endpoint != Mt7921u.EP_OUT_AC_BE) System.err.println("  bulk out %02x %s -> %d".format(endpoint, data.hex(), rc))
        if (rc < 0) throw Mt7921Exception("Bulk transfer to 0x%02x failed: %s".format(endpoint, usb.libusb_error_name(rc)))
        return done.value
    }

    override fun bulkIn(endpoint: Int, buffer: ByteArray, timeoutMs: Int): Int {
        val done = IntByReference()
        val rc = usb.libusb_bulk_transfer(handle, endpoint.toByte(), buffer, buffer.size, done, timeoutMs)
        if (rc == TIMEOUT) return 0
        if (rc < 0) throw Mt7921Exception("Bulk transfer from 0x%02x failed: %s".format(endpoint, usb.libusb_error_name(rc)))
        if (trace) System.err.println("  bulk in  %02x %s".format(endpoint, buffer.copyOf(done.value).hex()))
        return done.value
    }
}

private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }.let { if (it.length > 160) it.take(160) + "…" else it }

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

private const val FIRMWARE_DIR = "/lib/firmware/mediatek"

fun main(args: Array<String>) {
    val dryRun = "--dry-run" in args
    val trace = "--trace" in args
    val patchPath = args.firstOrNull { it.startsWith("--patch=") }?.substringAfter('=') ?: "$FIRMWARE_DIR/WIFI_MT7961_patch_mcu_1_2_hdr.bin.zst"
    val ramPath = args.firstOrNull { it.startsWith("--ram=") }?.substringAfter('=') ?: "$FIRMWARE_DIR/WIFI_RAM_CODE_MT7961_1.bin.zst"

    val patch = PatchFile(readFirmware(patchPath))
    val ram = RamFile(readFirmware(ramPath))
    println("ROM patch: build ${patch.buildDate}, hw/sw 0x%08x, %d section(s): %s".format(patch.hwSwVersion, patch.regions.size,
        patch.regions.joinToString { "%d bytes at 0x%08x mode 0x%08x".format(it.data.size, it.address, it.mode) }))
    println("Firmware: ${ram.version} ${ram.buildDate}, %d region(s): %s, start %s".format(ram.regions.size,
        ram.regions.joinToString { "%d bytes at 0x%08x".format(it.data.size, it.address) }, ram.startAddress?.let { "0x%08x".format(it) } ?: "default"))
    if (dryRun) return

    val usb = LibUsb.lib
    val ctx = PointerByReference()
    check(usb.libusb_init(ctx) == 0) { "libusb_init failed" }
    val handle = usb.libusb_open_device_with_vid_pid(ctx.value, Mt7921u.VENDOR_ID.toShort(), Mt7921u.PRODUCT_ID.toShort())
    if (handle == null) {
        System.err.println("Couldn't open the MT7921U (0e8d:7961). Is it plugged in, and can this user access it? See tools/mt7921u/README.md.")
        usb.libusb_exit(ctx.value)
        exitProcess(2)
    }
    val hadKernelDriver = usb.libusb_kernel_driver_active(handle, 0) == 1
    var exit = 0
    try {
        if (hadKernelDriver) {
            val rc = usb.libusb_detach_kernel_driver(handle, 0)
            check(rc == 0) { "Couldn't detach the kernel driver: ${usb.libusb_error_name(rc)}" }
            println("Detached the Linux mt7921u driver")
        }
        val rc = usb.libusb_claim_interface(handle, 0)
        check(rc == 0) { "Couldn't claim the interface: ${usb.libusb_error_name(rc)}" }
        // Linux resets the device before starting it, so the chip begins from a known state.
        usb.libusb_reset_device(handle).let { if (it != 0) println("USB reset: ${usb.libusb_error_name(it)}") }

        val started = System.currentTimeMillis()
        val result = Mt7921u(LibUsbIo(handle, trace)) { println("  $it") }.bringUp(patch, ram)
        println("Chip 0x%04x rev 0x%02x is running firmware %s after %d ms".format(result.chipId, result.revision, result.firmware, System.currentTimeMillis() - started))
        println("The chip reports: MAC address %s, 6 GHz %s, %d capability record(s)".format(
            result.capabilities.macAddress ?: "unknown", result.capabilities.has6Ghz?.let { if (it) "supported" else "not supported" } ?: "unknown",
            result.capabilities.elements))
    } catch (e: Exception) {
        System.err.println("FAILED: ${e.message}")
        exit = 1
    } finally {
        usb.libusb_release_interface(handle, 0)
        if (hadKernelDriver) {
            val rc = usb.libusb_attach_kernel_driver(handle, 0)
            println(if (rc == 0) "Handed the adapter back to the Linux driver" else "Couldn't reattach the Linux driver (${usb.libusb_error_name(rc)}); replug the adapter")
        }
        usb.libusb_close(handle)
        usb.libusb_exit(ctx.value)
    }
    exitProcess(exit)
}
