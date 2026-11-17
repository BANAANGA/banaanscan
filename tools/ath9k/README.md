# AR9271 userspace driver

sniffowireless-mobile's own driver for Atheros AR9271 Wi-Fi adapters (USB `0cf3:9271` and the other ids Linux's
ath9k_htc lists), so the phone can use one in monitor mode without root. The AR9271 runs Qualcomm's open-source
firmware (`htc_9271-1.4.0.fw`, bundled with the app under its licence), but unlike the MT7921U's firmware it leaves
the radio to the host: the driver sets the chip up register by register over USB, as Linux's `ath9k_hw` does.

- `ath9k/` is the driver, plain Kotlin shared with the app, ported from Linux's ath9k and ath9k_htc (ISC licence, see
  `ath9k/NOTICE`): firmware download, HTC and WMI (`Htc.kt`, `Regs.kt`), the chip's init, reset and calibration for
  2.4 GHz receiving (`Ar9271.kt`), monitor mode and the receive stream (`Ath9kHtc.kt`). Transmit power, transmit queues,
  the key cache, ANI and the LED are left out: it never transmits.
- `Initvals.kt` and `Regs_gen.kt` are generated from Linux's sources by `make_initvals.py` and `make_regs.py`.
- `ath9k-pc/` runs the driver on a Linux PC through libusb.

## Checking it against Linux

1. `capture-reference.sh` (sudo) records Linux's driver with usbmon while it loads the firmware and listens on
   channels 1, 6, 11 and 13, into `ath9k-trace/` (not committed: it holds nearby devices' traffic).
2. `decode_trace.py` turns the USB capture into a log of WMI commands and register operations.
3. `ath9k-pc --replay=ath9k-trace` replays Linux's register reads into the port, and `diff_regs.py` compares the
   register traffic. The init and a channel reset match apart from the parts left out on purpose.
4. With `70-ar9271.rules` installed, `ath9k-pc --channels=2412,2437,2462 --regs=regs.log` runs on the adapter.

Notes from testing:
- A channel change is a full reset, as Linux does it in monitor mode (about 220 ms over USB). Linux's fast channel
  change (`--fast`, about 80 ms) left the radio deaf for about one dwell in five, so it is off.
- The running firmware reports `bcdDevice` 0xffff. The driver reads that before starting: firmware left running by a
  session that didn't end cleanly is rebooted into the boot ROM first. Sending the firmware download to running
  firmware, or anything on the command endpoint to the boot ROM, leaves the adapter needing a replug.
