# Bluetooth over a USB adapter: PC prototype

sniffowireless-mobile talks to USB Bluetooth adapters directly in HCI, the standard controller protocol, so on Android it needs no
root and leaves the phone's own Bluetooth alone. Developed with a TP-Link UB500 Plus (Realtek RTL8761BU, `2357:0604`).

- `bthci/` is the library, plain Kotlin shared with the app: HCI commands and events, BLE scanning (legacy and
  Bluetooth 5 extended, including Coded PHY), classic inquiry, advertising data, and the Realtek firmware loader.
  Written from the Bluetooth Core specification (see `bthci/NOTICE`).
- `bthci-pc/` runs it on a PC through libusb.

Realtek RTL8761BU chips start from a ROM and need `rtl8761bu_fw.bin` + `rtl8761bu_config.bin` uploaded each time
they're powered. The app bundles both (`app/src/main/assets/rtl_bt/`, Realtek's licence next to them). Other adapters
that need no firmware should work as they are; other Realtek chips are tried on their ROM, which may not scan.

## Status

| Stage | State |
|---|---|
| Reset, controller info, features | Works on the UB500 Plus |
| BLE scan (active, extended, 1M + Coded PHY) | Works: 1,306 reports from 41 devices in 20 s on the PC |
| Classic inquiry | Works (found a classic device on the phone; devices only answer while their Bluetooth settings are open) |
| Firmware upload | Works: 30,210 bytes in about 0.5 s on the phone, the same image Linux uploads. On the PC Linux loads it first, so there it's skipped |
| Android (no root) | Works: Bluetooth tab → USB adapter. Tested on a Galaxy S10 (Android 12): 51 devices in 40 s, 16 named, 1 classic |

## One-time setup: let your user open the adapter

```bash
sudo cp tools/bthci/70-ub500.rules /etc/udev/rules.d/
sudo udevadm control --reload
sudo udevadm trigger --attr-match=idVendor=2357
```

## Run

```bash
./gradlew :bthci-pc:installDist
bthci-pc/build/install/bthci-pc/bin/bthci-pc                  # 20 s active scan, BLE + classic
bthci-pc/build/install/bthci-pc/bin/bthci-pc --passive        # listen only: transmits nothing
bthci-pc/build/install/bthci-pc/bin/bthci-pc --seconds=60 --no-coded --no-classic --trace
bthci-pc/build/install/bthci-pc/bin/bthci-pc --vid=0a12 --pid=0001   # another adapter
```

It takes the adapter from Linux's btusb driver while it runs and gives it back afterwards. Right after the takeover
the adapter sometimes misses the first command; the scanner retries it.

## On the phone

The scan log is kept in the app's files:

```bash
adb shell run-as io.github.banaanga.sniffowireless.mobile.debug cat files/bthci/log.txt
```
