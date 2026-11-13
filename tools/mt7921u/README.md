# MT7921U userspace driver: PC prototype

BanaanScan's own driver for MediaTek MT7921U Wi-Fi adapters (USB ID `0e8d:7961`, e.g. Alfa AWUS036AXML). It drives the
chip directly over USB, so on Android it won't need root. It's developed on a Linux PC first, checked against the Linux
`mt7921u` driver.

- `mt7921u/` is the driver, plain Kotlin shared with the app. Ported from Linux's mt76 driver (BSD-3-Clause-Clear,
  see `mt7921u/NOTICE`).
- `mt7921u-pc/` runs it on a PC through libusb.
- `capture-reference.sh` records the Linux driver's USB traffic as the reference (needs root).

## Status

| Stage | State |
|---|---|
| Load the ROM patch and firmware, ask the chip about itself | Works on a real adapter (about 120 ms) |
| Monitor mode, channel switching, receiving frames | Works on 2.4, 5 and 6 GHz; heard every network Linux heard |
| Signal strength | Within about 2 dB of Linux on 2.4 and 5 GHz, about 5 dB lower on 6 GHz (not yet explained) |
| Android (no root) | Works: Wi-Fi tab → USB adapter. Tested on a Galaxy S10 (Android 12) on 2.4, 5 and 6 GHz |

## One-time setup: let your user open the adapter

```bash
sudo cp tools/mt7921u/70-mt7921u.rules /etc/udev/rules.d/
sudo udevadm control --reload
sudo udevadm trigger --attr-match=idVendor=0e8d
```

## Run

```bash
./gradlew :mt7921u-pc:installDist
mt7921u-pc/build/install/mt7921u-pc/bin/mt7921u-pc            # start the chip, then hand it back to Linux
mt7921u-pc/build/install/mt7921u-pc/bin/mt7921u-pc --trace    # also print every USB transfer
mt7921u-pc/build/install/mt7921u-pc/bin/mt7921u-pc --dry-run  # only check the firmware files
# listen on 2.4 GHz ch 6, 5 GHz ch 36 and 6 GHz ch 37 for 15 s each, saving a capture for Wireshark
mt7921u-pc/build/install/mt7921u-pc/bin/mt7921u-pc --monitor --channels=2437,5180,6135 --seconds=15 --pcap=mt7921u-trace/ours.pcap
```

It takes the adapter from the Linux driver while it runs and gives it back afterwards; if that fails, replug the
adapter. It uses the firmware in `/lib/firmware/mediatek`. It never transmits. Captures contain nearby devices'
traffic, so keep them in the git-ignored `mt7921u-trace/` folder.

## On an Android phone

The app's Wi-Fi tab has a **USB adapter** mode that runs this driver through Android's USB host API, without root.
The firmware is bundled with the app (`app/src/main/assets/mt7921u/`, from linux-firmware under MediaTek's licence,
which allows redistribution for MediaTek devices as long as the app isn't GPL or LGPL). To try a different firmware
version, push it from this computer; a pushed copy takes precedence over the bundled one:

```bash
FIRMWARE_DIR=/path/to/firmware tools/mt7921u/push-firmware.sh
```

To update the bundled copy, replace the two `.bin` files in `app/src/main/assets/mt7921u/` and the version lines in
the `WHENCE` file there.

Connect the adapter to the phone (USB-C cable or OTG adapter; a powered hub if the phone can't supply enough
power), open **Wi-Fi → USB adapter**, pick a band and tap **Listen**. Each session writes a log and a capture to the
app's private storage. To read them after reconnecting the phone:

```bash
adb shell run-as io.github.banaanga.banaanscan cat files/mt7921u/log.txt
adb exec-out run-as io.github.banaanga.banaanscan cat files/mt7921u/capture.pcap > mt7921u-trace/phone.pcap
```
