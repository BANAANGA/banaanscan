# Keeping sniffo-core in step with sniffowireless

`sniffo-core/` ports the parts of [sniffowireless](https://github.com/BANAANGA/sniffowireless) that turn captures into
observations: `wifi.parse_frame` with `identify.wifi_fingerprint` and `wifiattrs.ap_attributes`, `bluetooth.parse_event`
with `parse_ad`, `ble_fingerprint` and the sensor's observation fields, and `zigbee_radio.FrameBatch`. A sniffowireless
server reads what the app sends as if one of its own sensors had sent it.

`make_fixtures.py` runs sniffowireless's Python code on synthetic inputs and writes its answers to
`sniffo-core/src/test/resources/fixtures/`; `FixtureTest` checks that the Kotlin gives the same. It also refreshes the
Bluetooth company list in `sniffo-core/src/main/resources/`. After sniffowireless changes:

```bash
python3 -m venv /tmp/sniffo-venv && /tmp/sniffo-venv/bin/pip install -r ../sniffowireless/requirements.txt
/tmp/sniffo-venv/bin/python -I tools/sniffowireless/make_fixtures.py ../sniffowireless
./gradlew :sniffo-core:test
```

A failing test names the case and the field that differs; port the change, or regenerate if the fixture is what moved.

The inputs are well-formed on purpose. For malformed information elements scapy's results depend on its per-element
parsers (a DS element longer than one byte is re-read as further elements, a short RSN element hides the ones after
it); the port reads elements by their length instead.

## Two optional tests

Both are skipped unless their environment variables are set, and neither puts anything in the repository.

**Real captures** (`RealCaptureTest`): export a pcap with sniffowireless's observations for each frame, then compare.
Captures hold real network names, so keep the export outside the repository:

```python
# export.py, run with the venv above: python -I export.py out.json capture.pcap ...
import json, sys
sys.path.insert(0, "../sniffowireless")
from scapy.all import rdpcap
from scapy.layers.dot11 import Dot11, Dot11FCS
from sniffowireless import wifi, wifiattrs
out = []
for path in sys.argv[2:]:
    fp_gate, gates, frames = wifi.FingerprintGate(), wifi.AttrGates(), []
    for pkt in rdpcap(path):
        if not pkt.haslayer(Dot11):
            continue
        raw = bytes(pkt.getlayer(Dot11))
        raw = raw[:-4] if pkt.haslayer(Dot11FCS) else raw
        rssi, freq = wifi._radio(pkt)
        q = wifiattrs.radio_quality(pkt)
        frames.append({"frame": raw.hex(), "rssi": rssi, "freq": freq, "ts": float(pkt.time), "phy_rate": q.get("phy_rate"),
                       "noise": q.get("noise"), "obs": wifi.parse_frame(pkt, fp_gate, None, gates, True)})
    out.append({"file": path, "frames": frames})
json.dump(out, open(sys.argv[1], "w"), default=lambda v: v.hex() if isinstance(v, bytes) else sorted(v))
```

```bash
SNIFFO_REAL_CAPTURE=/tmp/out.json ./gradlew :sniffo-core:test --tests '*RealCaptureTest*'
```

**A live server** (`LiveServerTest`): sends the fixture observations through the forwarder to a sniffowireless server.
Add a remote sensor on the server (Settings → Sensors) and use its token:

```bash
SNIFFO_SERVER=http://127.0.0.1:8080 SNIFFO_SENSOR_TOKEN=sniffowireless_... ./gradlew :sniffo-core:test --tests '*LiveServerTest*'
```
