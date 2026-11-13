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
| Load the ROM patch and firmware, ask the chip about itself | Written; matches Linux's bytes in tests |
| Monitor mode, channel switching, receiving frames | Not started |

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
```

It takes the adapter from the Linux driver while it runs and gives it back afterwards; if that fails, replug the
adapter. It uses the firmware in `/lib/firmware/mediatek`. It never transmits.
