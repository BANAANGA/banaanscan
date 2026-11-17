# sniffowireless-mobile

<p>
  <a href="https://apps.obtainium.imranr.dev/redirect?r=obtainium://add/https://github.com/BANAANGA/sniffowireless-mobile"><img src="https://raw.githubusercontent.com/ImranR98/Obtainium/main/assets/graphics/badge_obtainium.png" height="60" alt="Get it on Obtainium"></a>
</p>

**Part of [sniffowireless](https://github.com/BANAANGA/sniffowireless)** · server, web interface and Raspberry Pi
sensors: [github.com/BANAANGA/sniffowireless](https://github.com/BANAANGA/sniffowireless)

The button opens Obtainium on your phone and adds this app, so it installs and updates from the
[GitHub releases](https://github.com/BANAANGA/sniffowireless-mobile/releases). Without Obtainium, download the APK from
the [latest release](https://github.com/BANAANGA/sniffowireless-mobile/releases/latest).

The Android companion of [sniffowireless](https://github.com/BANAANGA/sniffowireless): it captures Wi-Fi, Bluetooth
and Zigbee the way a sniffowireless sensor does, and can run as a **remote sensor** that sends everything to your
sniffowireless server, where it shows up on the map, in the device history and in the alerts like any Raspberry Pi
sensor.

On its own it is also a scanner (Kotlin + Jetpack Compose): Wi-Fi and Bluetooth, Wi-Fi monitor mode and full
Bluetooth scanning through USB adapters, Zigbee networks and IEEE 802.15.4 traffic through a USB stick, devices on the
local network, MAC vendors, device-type guesses, and a list of every device it has seen. It looks like the
sniffowireless web interface: the same colours, panels, device-type colours and lists.

## Sensor for sniffowireless

1. On the server: **Settings → Sensors → Add remote sensor**, with a name and room. Copy the command it shows.
2. In the app: open the **Sensor** page, then tap **Paste from clipboard**: the server address and token
   are taken from the command. Choose what to send and press **Start**.

The phone then works like one of sniffowireless's remote sensors:

* **Same observations:** Wi-Fi frames, Bluetooth adverts and inquiry answers, and Zigbee frames are turned into
  observations by a Kotlin port of sniffowireless's own parsing code (`sniffo-core/`), with the same capability
  fingerprints, access point details, BLE identification fields and Zigbee radio summaries. The port is tested against
  fixtures that sniffowireless's Python produces (see [tools/sniffowireless](tools/sniffowireless/README.md)).
* **Sources:** the phone's own Wi-Fi scan (access points; with Android 11+ including their information elements) and
  Bluetooth, an MT7921U in monitor mode (access points, clients, probe requests, deauths, channel survey), a USB
  Bluetooth adapter (every advert, long range, classic inquiry), a Zigbee stick (network scans, energy, and
  listening with an EZSP stick that has `mfglib`), and a LoRa board with sniffowireless's lora-sniffer firmware (raw
  frames, which the server recognises as Meshtastic, MeshCore, LoRaWAN or LoRa APRS, as it does for its own boards).
* **Settings from the server:** the channels and hop time set under **Settings → Wi-Fi channels**, and the channels,
  scan interval and listening under **Settings → Zigbee**, and the LoRa profiles and hop time under **Settings → LoRa**,
  reach the phone with its next upload and are applied while it
  runs; the server shows *applied* once the phone reports the new revision.
* **Status, load and logs:** each source appears on the server's Sensors page with its state and hardware, with the
  phone's memory, disk and CPU use, its battery (level, charging, charger type and temperature, which the server
  shows with the sensor and turns amber at 20% and red at 10% while not charging), and its log lines on the Logs page.
* **Frame recording:** on the server, **Settings → Frame recording → Record on** this phone records what its USB
  adapters receive for as long as you choose: MT7921U Wi-Fi frames (of data frames only the headers), USB Bluetooth HCI
  events, Zigbee frames and LoRa frames. The phone sends the file to the server when it is done and deletes it here. It
  then opens in the server's **Frames** page. The phone's own Wi-Fi and Bluetooth can't be recorded, because Android only
  passes on what it already decoded, not the frames. Needs sniffowireless 33.2 or newer.
* **Live frames:** on the server, **Frames → Live → Radios of** this phone shows what its USB adapters receive as it
  arrives. The same radios as frame recording can be shown. The phone sends the frames with its uploads, every 2 seconds and
  at most 3,000 at a time, and stops when the server stops asking. Needs sniffowireless 33.3 or newer.
* **Wi-Fi analysis (sniffowireless 34):** from what the MT7921U receives, the phone also sends what the server's Wi-Fi
  analysis needs:
  * *link quality*: every client's data frames per direction and minute, with retries and the rate and MCS they went at.
    The MT7921U's receive descriptor gives the PHY mode, rate index, streams, width and guard interval.
  * *connection steps*: authentication, association, EAP, the 4-way handshake, deauthentications, 802.11v/k, for the
    troubleshooter and the roam timeline. Only the step, the addresses and the codes are sent, nothing of the keys.
  * *beacon rate and size* of every access point, for the beacon overhead per channel.

  The phone's own Wi-Fi scan has none of these.
* **Keeps running:** a foreground service with a notification keeps capturing and sending with the screen off. Uploads
  go every 2 seconds; when the server can't be reached they are kept (up to 50,000 observations) and sent later.

The phone's own radios see less than a USB adapter: Android filters and merges Bluetooth adverts and only reports
access points from Wi-Fi scans (4 per 2 minutes unless throttling is turned off in Developer options). For a sensor
that runs for days, turn battery optimisation off for the app (the Sensor screen links to the setting).

## Screenshots

<table>
  <tr>
    <td align="center"><img src="docs/screenshots/wifi.png" width="200" alt="Wi-Fi page with an access point list"><br><sub>Wi-Fi</sub></td>
    <td align="center"><img src="docs/screenshots/detail.png" width="200" alt="Access point details with what it was identified as"><br><sub>Details + identification</sub></td>
    <td align="center"><img src="docs/screenshots/network.png" width="200" alt="LAN page with discovered hosts"><br><sub>LAN</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/index.png" width="200" alt="Devices page listing every device seen"><br><sub>Devices</sub></td>
    <td align="center"><img src="docs/screenshots/zigbee.png" width="200" alt="Zigbee page with channel interference from a simulated stick"><br><sub>Zigbee (simulated stick)</sub></td>
    <td align="center"><img src="docs/screenshots/sniffer.png" width="200" alt="802.15.4 sniffer with frames per channel and heard radios from a simulated stick"><br><sub>802.15.4 sniffer (simulated stick)</sub></td>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/sensor.png" width="200" alt="Sensor page for sending to a sniffowireless server"><br><sub>Sensor mode</sub></td>
    <td></td>
    <td></td>
  </tr>
</table>

<sub>Captured on the Android emulator. The emulator's virtual network only has
a gateway, a DNS server and the emulator itself. On a real home network you'll see phones, TVs, printers, smart plugs and so on. The
Zigbee and sniffer results are made up by the simulated dongle in debug builds.</sub>

## Features

| Page | What it does |
|---|---|
| **Wi-Fi** | Lists access points: SSID, BSSID + vendor, signal, band/channel/width, Wi-Fi standard, security, channel usage. Flags hotspots / Wi-Fi Direct printers & TVs. |
| **Bluetooth** | BLE scan + classic discovery merged per address. Decodes BLE manufacturer data (Apple Continuity, Microsoft, Samsung, Garmin…), service UUIDs and the Bluetooth device class. With a USB Bluetooth adapter: every advertising packet, long range (Coded PHY), and a listen-only mode. |
| **Zigbee** | Active scan through a USB coordinator: PAN ID, extended PAN ID, channel, signal, whether the network is open for joining, and which routers answered. Silicon Labs sticks also measure interference per channel, and can passively sniff all 802.15.4 traffic: frames per channel, addresses, frame types and a protocol hint. |
| **LoRa** | Through a Heltec WiFi LoRa 32 V3 with sniffowireless's lora-sniffer firmware: hops over LoRa profiles and recognises Meshtastic, MeshCore, LoRaWAN and LoRa APRS frames the way sniffowireless does. Lists nodes (with names, hardware, positions and telemetry from the public Meshtastic channel) and recent frames. |
| **LAN** | Subnet sweep (TCP connect + ICMP), then per host: 33 common ports, reverse DNS, NetBIOS name/MAC, mDNS/DNS-SD services + TXT (Apple model IDs, Cast models, printers), SSDP/UPnP description, web page title/Server header. |
| **Devices** | Every LAN, Bluetooth, Wi-Fi and Zigbee device seen, with first/last seen and sighting count, kept on the phone. Search, filter by type, hide randomized MACs, export CSV, and update the vendor database. |
| **Sensor** | Sends what the phone and its USB adapters hear to a sniffowireless server (see [Sensor for sniffowireless](#sensor-for-sniffowireless)). |

The pages are in a row under the title, like the sniffowireless web interface on a narrow screen. The app starts in
dark mode; the button in the top bar cycles Dark → Light → Follow system, and the choice is remembered.

### USB adapters

sniffowireless-mobile can drive two kinds of USB adapter itself, without root, through a USB-C cable or OTG adapter. Choose
**USB adapter** at the top of the Wi-Fi or Bluetooth page.

* **Wi-Fi monitor mode** with a MediaTek MT7921U adapter (USB ID `0e8d:7961`): listens on 2.4, 5 and 6 GHz
  and lists networks and the devices talking in them, including probe requests. Nothing is transmitted. A capture
  for Wireshark is saved too. See [tools/mt7921u](tools/mt7921u/README.md). Compatible adapters:
  * BrosTrend AXE3000 Linux WiFi, model AX9L (tested)
  * Alfa AWUS036AXML
  * Any other adapter with the MT7921U / MT7921AUN chip that shows up as `0e8d:7961`
* **Bluetooth** with a USB Bluetooth adapter, tested with the TP-Link UB500 Plus (Realtek RTL8761BU, `2357:0604`).
  Compared with the phone's own Bluetooth it reports every advertising packet without Android's filtering, hears
  long-range (Coded PHY) devices, knows whether an address is public or random, and has a **Listen only** mode that
  transmits nothing. Without it, it asks devices for their names from a random address and also searches for
  classic devices. It doesn't follow other devices' connections: that needs sniffer hardware such as an nRF52840.
  See [tools/bthci](tools/bthci/README.md).

The adapters' firmware is bundled with the app under the chip makers' licences: MediaTek's (`assets/mt7921u/`) and
Realtek's (`assets/rtl_bt/`), both from linux-firmware.

### Device-type guessing

`classify/DeviceClassifier.kt` gives each signal points towards a type (router, phone, printer,
camera, smart home…). The highest score wins, and the gap to the runner-up sets the confidence.
Devices with too little evidence (for example a single open port) stay **Unknown** rather than getting
a shaky label. Tap any device to see the reasons behind its guess.

### Zigbee

Phones have no Zigbee radio, so this page needs a coordinator stick connected through a USB-OTG adapter.
sniffowireless-mobile works out the firmware by asking the stick, since the USB chip doesn't reveal it.

| Firmware | Example sticks | Networks | Channel interference | Sniffer |
|---|---|---|---|---|
| TI Z-Stack | Sonoff ZBDongle-P, CC2652, CC2531 | Yes | No | No |
| Silicon Labs EZSP | Sonoff ZBDongle-E, SkyConnect, ZBT-1 | Yes | Yes | If the firmware includes `mfglib` |
| deCONZ | ConBee II | Not supported | Not supported | Not supported |

* **Read-only:** it never writes the stick's stored settings, so a scan shouldn't affect the Zigbee
  network a stick runs. This is untested on real hardware, so try a spare stick first if you have one. Unplug it from your hub first, because only one program can use it at a time,
  and that network is offline until you plug it back. Silicon Labs sticks do get a firmware restart, which
  keeps their stored data.
* **Z-Stack limits:** other tools widen a stick's channel list by rewriting it. sniffowireless-mobile doesn't, so if a
  stick is set to specific channels the app warns you that networks elsewhere may be missed. Measuring
  interference needs a running network, so it's skipped.
* **What a scan can't see:** only routers and coordinators answer. Battery sensors stay asleep, and devices
  inside other networks stay hidden. Seeing those would need sniffer firmware on the stick.

### LoRa

LoRa needs a **Heltec WiFi LoRa 32 V3** (ESP32-S3 + SX1262, 863–928 MHz) running sniffowireless's
[lora-sniffer firmware](https://github.com/BANAANGA/sniffowireless/tree/main/firmware/lora-sniffer). Flash it from the
sniffowireless web interface (**Settings → LoRa flasher**, in Chrome or Edge on a computer), then plug the board into
the phone. The firmware has no transmit code: the board only listens.

* **Profiles:** a LoRa radio only hears a network while it is set to that network's frequency, bandwidth, spreading
  factor and sync word, so the app hops between profiles, as sniffowireless does. The list uses sniffowireless's
  `LORA_PROFILES` syntax: built-in names (`meshtastic-eu868`, `meshcore-eu868`, `lorawan-eu868`, `lora-aprs-433`…),
  `meshtastic:<region>[:<preset>]`, `lorawan:<region>[:<sf or range>]`, or your own `name=freq/bw/sf/cr/sync`. Empty
  means the region's defaults (EU868: Meshtastic LongFast, MeshCore and the three LoRaWAN join channels at SF7).
* **What you see:** each frame's protocol and how sure the classifier is, who sent it and to whom, hops, signal and
  SNR. For Meshtastic, the public default channel (whose key Meshtastic publishes) is read like sniffowireless reads
  it: node names, hardware, roles, positions, telemetry and text messages. Private channels and direct messages stay
  unreadable. Turn **Read the public Meshtastic channel** off to see only the headers.
* **Sensor mode:** turn on **LoRa board** on the Sensor page. The phone sends the raw frames (`lora_frame`) and the
  board's state; the server recognises and stores them as it does for its own boards, and the profiles set for this
  sensor under **Settings → LoRa** replace the phone's own list. A firmware update from the server isn't supported on the
  phone: flash from the web interface instead.
* **Same USB chip as some Zigbee sticks:** the Heltec V3 uses a CP2102, like the ZBDongle-P. The app asks each such
  device whether it is a lora-sniffer (it answers `PING`) and then leaves it out of the Zigbee page.

The profile parser, the classifier and the Meshtastic decoder are ports of sniffowireless's `lora_protocols.py` and
`meshtastic.py`, tested against what sniffowireless's Python produces for 550 frames and 326 packets.

### 802.15.4 sniffer

The **Sniffer** mode on the Zigbee page hears other 802.15.4 systems too, not just Zigbee. It uses the firmware's
manufacturing test library (`mfglib`) in receive-only mode, so no reflashing is needed if the firmware includes it;
if not, the app says so. It never transmits, and the stick restarts when you press Stop.

* **What you get:** frames per channel, PAN IDs, short and long addresses, frame types, signal strength, whether
  frames are encrypted at the radio layer, and a possible vendor for long addresses.
* **What you don't get:** encrypted contents stay hidden, and frames with radio errors are dropped.
* **Protocol labels are guesses:** "Looks like Zigbee" and "Looks like Thread" come from unencrypted fields.
  Anything else stays "Unknown protocol" or "Encrypted, protocol unknown". There's no label for specific products
  such as Aperio locks, because no verified signature for them is built in.
* **Choosing a channel:** "All channels" spends 2 seconds on each, so a full round takes about 32 seconds and short
  transmissions are easily missed. Staying on one channel works better for battery devices.

Only use it on systems you own or are authorised to test.

### Vendor database

On first launch the app downloads Wireshark's `manuf` file (IEEE OUI registries, ~58,000 prefixes) into
private storage. **Devices → Vendor database → Update** refreshes it. To work fully offline, put that file at
`app/src/main/assets/manuf` before building.

## Android limitations (worth knowing)

* **MAC addresses of other LAN devices:** Android 10+ blocks `/proc/net/arp` and `ip neigh` for apps.
  sniffowireless-mobile still tries both, but on modern phones MACs usually come only from **NetBIOS**
  (Windows/Samba/NAS) responses. For other devices, the type is guessed from mDNS, UPnP, ports and names.
* **Wi-Fi scan throttling:** apps get 4 scans per 2 minutes (can be disabled in Developer options →
  "Wi-Fi scan throttling"). Location services must be **on** to get any results.
* **BLE random addresses:** most phones/earbuds advertise with rotating private addresses, which have no
  vendor. The app uses BLE manufacturer data instead.
* Your own MAC is hidden (`02:00:00:00:00:00`) on Android 6+.

## Requirements

* Android 8.0 (API 26) or newer; targets Android 15 (API 35)
* Permissions: location and nearby devices (needed by Android for Wi-Fi and Bluetooth scans), and notifications for
  sensor mode. Nothing is sent anywhere except the vendor-database download and, in sensor mode, the observations to
  the sniffowireless server you set.
* For Zigbee: a supported USB stick, plus a phone with USB-OTG host support. Android asks for permission
  the first time you scan with a stick.
* For the USB adapter modes: an MT7921U Wi-Fi adapter or a USB Bluetooth adapter, and USB-OTG host support.

## Install with Obtainium

[Obtainium](https://github.com/ImranR98/Obtainium) installs sniffowireless-mobile from this repository's GitHub Releases and
keeps it up to date. In Obtainium, tap **Add app** and enter:

```
https://github.com/BANAANGA/sniffowireless-mobile
```

Or open this link on the phone: `obtainium://add/https://github.com/BANAANGA/sniffowireless-mobile`

The app used to be called BanaanScan (`io.github.banaanga.banaanscan`). sniffowireless-mobile has a new app ID, so
it installs as a new app: add it to Obtainium again and uninstall BanaanScan.

Every release is signed with the same key (made when the app was called BanaanScan, which is the name in its certificate). Its SHA-256 certificate fingerprint is
`67:E6:1D:67:71:BC:03:2C:6D:D9:BD:FD:1A:B4:34:41:61:8A:22:4F:EB:DF:30:6D:94:AA:68:FA:7E:4A:75:5A`; Android won't
install an update signed with anything else.

### Publishing a release

Push a version tag. The release workflow runs the tests, builds and signs the APK, and attaches it to a GitHub
Release:

```bash
git tag v1.0.1 && git push origin v1.0.1
```

The tag sets the version: `v1.2.3` becomes version name `1.2.3` and version code `10203`, so every new tag counts as
an update. The signing key comes from the repository secrets `RELEASE_KEYSTORE_BASE64`, `RELEASE_KEYSTORE_PASSWORD`,
`RELEASE_KEY_ALIAS` and `RELEASE_KEY_PASSWORD`; keep a private backup of the key, because losing it means installed
copies can't be updated.

## Build and install

You need JDK 17 and the Android SDK (platform 35). The Gradle wrapper is included. To run the unit tests:

```bash
./gradlew testDebugUnitTest
```

Emulators have no USB host, so debug builds can list a simulated Zigbee dongle (`ezsp` or `znp`):

```bash
adb shell am start -n io.github.banaanga.sniffowireless.mobile.debug/io.github.banaanga.sniffowireless.mobile.MainActivity --es simulate_zigbee ezsp
```

**Android Studio:** open the project folder and press Run.

**Command line:**

```bash
./gradlew assembleDebug
```

The APK ends up in `app/build/outputs/apk/debug/app-debug.apk`. To install it on a phone with USB
debugging enabled:

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## Testing status

Tested on the Android 15 emulator (Pixel 6 profile): all pages, LAN scan, device list persistence,
rotation and the theme toggle. On a Galaxy S10 (Android 12): the app itself, Wi-Fi monitor mode through an MT7921U
and Bluetooth through a TP-Link UB500 Plus (see the [USB adapters](#usb-adapters) section).

**Sensor mode:** the observation port matches sniffowireless on its fixtures and on 14,880 real frames from MT7921U
captures (2 differ: a malformed element scapy misreads). Uploads were checked against a sniffowireless server
running from source: all observations accepted, devices identified, and the sensor and its interfaces shown. On the
Galaxy S10 the phone's own Wi-Fi and Bluetooth were sent to that server. Sensor mode with the USB adapters and a
Zigbee stick attached to the phone has not been run yet (the phone's one port was taken by the cable).

**Zigbee has not been run on a real dongle.** Its protocol code is checked by unit tests against byte
sequences computed from the [zigpy-znp](https://github.com/zigpy/zigpy-znp) and
[bellows](https://github.com/zigpy/bellows) reference implementations, and the full flow runs on the
emulator against simulated dongles. USB access, permissions and real firmware behaviour are still unproven, and
whether a given stick's firmware includes `mfglib` is only known once you try it.

**LoRa has not been run on a real board yet.** The parsing matches sniffowireless's own Python on its fixtures, the
serial driver is tested against a fake board that answers like the firmware's `main.cpp`, frames sent through it reached
a sniffowireless server running from source and were recognised there (Meshtastic, MeshCore, LoRaWAN, LoRa APRS), and
the LoRa page runs on the emulator against a simulated board in debug builds (`--ez simulate_lora true`).

## Project layout

```
app/src/main/java/io/github/banaanga/sniffowireless/mobile/
├── MainActivity.kt / MainViewModel.kt   app shell, permissions, theme, state
├── scan/       WifiScanner, BluetoothScanner, LanScanner, MdnsScanner, SsdpScanner, NetBios, Probes
├── zigbee/     USB scanner, dongle detection, Z-Stack and EZSP drivers
├── wifimon/    Wi-Fi monitor mode through an MT7921U
├── btusb/      Bluetooth through a USB adapter
├── lora/       LoRa through a board with the lora-sniffer firmware
├── sensor/     sensor mode: the shared Hub, SensorService (foreground service), settings
├── classify/   DeviceClassifier (type-guessing rules)
├── data/       models, OuiDatabase (MAC vendors), DeviceIndex (saved devices)
└── ui/         one screen per page + shared sniffowireless-style components (Common.kt)
mt7921u/        MT7921U driver (plain Kotlin), mt7921u-pc/ its PC prototype
bthci/          Bluetooth HCI over USB + Realtek firmware loader (plain Kotlin), bthci-pc/ its PC prototype
sniffo-core/    sniffowireless's observation formats, LoRa classifier and Meshtastic decoder ported to Kotlin, the
                lora-sniffer driver, and the upload to its server (plain Kotlin)
tools/sniffowireless/  generates sniffo-core's test fixtures from a sniffowireless checkout
```

Only scan networks you own or have permission to scan.
