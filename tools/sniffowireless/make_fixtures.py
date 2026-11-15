#!/usr/bin/env python3
"""Generate sniffo-core's test fixtures (and the Bluetooth company list) from a sniffowireless checkout.

sniffo-core ports parts of sniffowireless to Kotlin: how Wi-Fi frames, Bluetooth HCI events and Zigbee frames become
observations, and how LoRa frames are recognised. These fixtures are sniffowireless's own answers for a set of synthetic inputs, so the Kotlin tests fail
when the port drifts. Run it again after updating sniffowireless:

    python3 -m venv /tmp/sniffo-venv && /tmp/sniffo-venv/bin/pip install -r ../sniffowireless/requirements.txt
    /tmp/sniffo-venv/bin/python tools/sniffowireless/make_fixtures.py ../sniffowireless

All inputs are made up here: no real network names or addresses. They are well-formed on purpose: for malformed
elements scapy's results depend on its per-element parsers (a too-long DS element is re-read as further elements, a
short RSN element hides everything after it), which the port doesn't copy; it reads elements by their length.
"""
import json
import os
import struct
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(os.path.dirname(HERE))
OUT = os.path.join(ROOT, "sniffo-core/src/test/resources/fixtures")
RES = os.path.join(ROOT, "sniffo-core/src/main/resources")

src = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else os.path.join(ROOT, "../sniffowireless"))
sys.path.insert(0, src)

from scapy.layers.dot11 import (Dot11, Dot11AssoReq, Dot11AssoResp, Dot11Auth, Dot11Beacon, Dot11Deauth,  # noqa: E402
                                Dot11Disas, Dot11Elt, Dot11ProbeReq, Dot11ProbeResp, Dot11QoS, Dot11ReassoReq, RadioTap)

from sniffowireless import bluetooth, config, lora_protocols, meshtastic, oui, wifi, zigbee_radio  # noqa: E402
from sniffowireless.lora import parse_rx  # noqa: E402

TS = 1_700_000_000.0


def clean(v):
    """JSON-safe: bytes as hex, sets and tuples as lists."""
    if isinstance(v, dict):
        return {str(k): clean(x) for k, x in v.items()}
    if isinstance(v, (list, tuple)):
        return [clean(x) for x in v]
    if isinstance(v, set):
        return sorted(clean(x) for x in v)
    if isinstance(v, (bytes, bytearray)):
        return bytes(v).hex()
    return v


def write(name, data):
    with open(os.path.join(OUT, name), "w", encoding="utf-8") as f:
        json.dump(clean(data), f, indent=1, sort_keys=True)
        f.write("\n")


def git_rev():
    try:
        return subprocess.run(["git", "-C", src, "rev-parse", "--short", "HEAD"], capture_output=True, text=True).stdout.strip()
    except OSError:
        return "?"


# ------------------------------------------------------------------------------------------------------------- Wi-Fi
AP = "02:11:22:33:44:55"     # locally administered: never a real vendor's address
AP2 = "02:11:22:33:44:66"
STA = "06:aa:bb:cc:dd:ee"   # a randomised client
STA2 = "00:16:3e:12:34:56"  # a "public" client (the Xen OUI, used in examples)
BC = "ff:ff:ff:ff:ff:ff"


def E(eid, info):
    return Dot11Elt(ID=eid, info=bytes(info))


def le16(v):
    return struct.pack("<H", v)


SSID = lambda s: E(0, s.encode())  # noqa: E731
RATES = E(1, bytes([0x82, 0x84, 0x8b, 0x96, 0x0c, 0x12, 0x18, 0x24]))
RATES_G = E(1, bytes([0x8c, 0x12, 0x98, 0x24, 0xb0, 0x48, 0x60, 0x6c]))
XRATES = E(50, bytes([0x30, 0x48, 0x60, 0x6c]))
HT_CAP = E(45, le16(0x19ef) + bytes([0x17]) + bytes([0xff, 0xff, 0, 0]) + bytes(12) + bytes(2) + bytes(4) + bytes(1))
HT_OP = lambda primary: E(61, bytes([primary, 0x05]) + bytes(20))  # noqa: E731
VHT_CAP = E(191, struct.pack("<I", 0x339179b6 | (1 << 2)) + le16(0xfffa) + le16(0) + le16(0xfffa) + le16(0))
HE_CAP = E(255, bytes([35]) + bytes(6) + bytes([0x1c]) + bytes(10) + le16(0xfffa) + le16(0xfffa))
HE_OP = lambda color: E(255, bytes([36]) + struct.pack("<I", (color << 24) | 0x3ff0) + bytes([0, 0]))  # noqa: E731
EHT_CAP = E(255, bytes([108, 0, 0, 0x02]) + bytes(6))
MULTI_LINK = E(255, bytes([107, 0x00, 0x00]) + bytes(8))
EXTCAP = E(127, bytes([0x04, 0x00, 0x48, 0x80, 0x01, 0x40, 0x40, 0x40, 0xc0, 0x01, 0x10]))
RM = E(70, bytes([0x72, 0x00, 0x00, 0x00, 0x00]))
MDE = E(54, bytes([0x12, 0x34, 0x01]))
COUNTRY = E(7, b"NLI" + bytes([1, 13, 20]))
BSS_LOAD = E(11, le16(7) + bytes([64]) + le16(0))
DS = lambda ch: E(3, bytes([ch]))  # noqa: E731
CSA = E(37, bytes([1, 100, 5]))
ECSA = E(60, bytes([1, 128, 52, 9]))
SUPP_CH = E(36, bytes([1, 13, 36, 4, 149, 5]))
POWER_CAP = E(33, bytes([0xfb, 0x14]))
OPCLASS = E(59, bytes([81, 81, 115, 116, 128]))
MESH_ID = E(114, b"meshy")
INTERWORKING = E(107, bytes([0x10]))
MBSSID = E(71, bytes([3]))
RNR = E(201, le16(0x0d00) + bytes([131, 37]) + bytes([0x00]) + bytes.fromhex("021122334477") + bytes(6))


def suite(t, oui=b"\x00\x0f\xac"):
    return oui + bytes([t])


def rsn(pairwise=(4,), akm=(2,), caps=0x000c, group=4):
    body = le16(1) + suite(group) + le16(len(pairwise)) + b"".join(suite(p) for p in pairwise)
    body += le16(len(akm)) + b"".join(suite(a) for a in akm) + le16(caps)
    return E(48, body)


WPA1 = E(221, bytes.fromhex("0050f201") + le16(1) + bytes.fromhex("0050f202") + le16(1) + bytes.fromhex("0050f202")
         + le16(1) + bytes.fromhex("0050f202"))


def wps_ie(configured=True, locked=False, names=True):
    tlv = lambda t, v: struct.pack(">HH", t, len(v)) + v  # noqa: E731
    body = tlv(0x104a, b"\x10") + tlv(0x1044, bytes([2 if configured else 1]))
    if locked:
        body += tlv(0x1057, b"\x01")
    if names:
        body += tlv(0x1021, b"ExampleCorp") + tlv(0x1023, b"Model X") + tlv(0x1024, b"MX-100") + tlv(0x1011, b"Living room box")
        body += tlv(0x1054, bytes.fromhex("0006 0050f204 0001"))
    return E(221, bytes.fromhex("0050f204") + body)


def p2p_ie():
    name = struct.pack(">HH", 0x1011, 7) + b"Printer"
    dev = bytes.fromhex("06aabbccddee") + struct.pack(">H", 0x0188) + struct.pack(">H", 3) + bytes.fromhex("0050f204") \
        + struct.pack(">H", 1) + bytes([0]) + name
    attr = bytes([13]) + le16(len(dev)) + dev
    return E(221, bytes.fromhex("506f9a09") + bytes([2]) + le16(2) + bytes([0x25, 0x00]) + attr)


APPLE_IE = E(221, bytes.fromhex("0017f2" "0a" "000103"))
MBO = E(221, bytes.fromhex("506f9a16" "0a0100"))
UBNT = E(221, bytes.fromhex("00156d" "01") + b"Hallway AP")
ARUBA = E(221, bytes.fromhex("000b86" "01" "03" "00") + b"ap-2nd-floor")
BRCM = E(221, bytes.fromhex("001018" "0201" "000c0000"))
WME = E(221, bytes.fromhex("0050f2" "02" "0001" "80" "00" "03a40000" "27a40000" "42435e00" "62322f00"))


def rt(freq, rssi):
    p = RadioTap(present="Flags+Channel+dBm_AntSignal", Flags=0, ChannelFrequency=freq,
                 ChannelFlags=0x0080 if freq < 3000 else 0x0100, dBm_AntSignal=rssi)
    return p


def beacon(bssid, elts, cap=0x0431, tsf=123_456_789_000, interval=100, subtype=8, dst=BC, seq=100):
    layer = Dot11Beacon if subtype == 8 else Dot11ProbeResp
    return Dot11(type=0, subtype=subtype, addr1=dst, addr2=bssid, addr3=bssid, SC=seq << 4) / \
        layer(timestamp=tsf, beacon_interval=interval, cap=cap) / elts


def mgmt(subtype, a1, a2, a3, body, seq=200, fc=0):
    return Dot11(type=0, subtype=subtype, addr1=a1, addr2=a2, addr3=a3, SC=seq << 4, FCfield=fc) / body


def data(a1, a2, a3, to_ds, from_ds, qos=False, protected=True, seq=300, payload=b"\xaa" * 20):
    fc = (1 if to_ds else 0) | (2 if from_ds else 0) | (0x40 if protected else 0)
    d = Dot11(type=2, subtype=8 if qos else 0, addr1=a1, addr2=a2, addr3=a3, FCfield=fc, SC=seq << 4)
    if qos:
        d = d / Dot11QoS(TID=0)
    from scapy.packet import Raw
    return d / Raw(payload)


def wifi_cases():
    full = (SSID("Example Home") / RATES_G / DS(36) / COUNTRY / BSS_LOAD / rsn(akm=(2,), caps=0x008c) / HT_CAP / HT_OP(36)
            / VHT_CAP / RM / MDE / EXTCAP / HE_CAP / HE_OP(21) / wps_ie() / APPLE_IE / BRCM / WME / RNR / UBNT)
    cases = [
        ("beacon full WPA2", rt(5180, -47), beacon(AP, full), None),
        ("beacon WPA3 only", rt(5745, -60), beacon(AP2, SSID("Example WPA3") / RATES_G / HT_OP(149) / rsn(akm=(8,), caps=0x00cc)
                                                       / HE_CAP / EHT_CAP / MULTI_LINK / HE_OP(5)), None),
        ("beacon transition", rt(2437, -55), beacon(AP, SSID("Example Mix") / RATES / DS(6) / XRATES
                                                     / rsn(akm=(2, 8), caps=0x0080) / HT_CAP / HT_OP(6) / EXTCAP), None),
        ("beacon SAE-EXT and 802.1X", rt(2412, -71), beacon(AP, SSID("Example Ent") / RATES / DS(1)
                                                            / rsn(pairwise=(4, 9), akm=(24, 1), caps=0x00cc, group=9)), None),
        ("beacon hidden open", rt(2462, -80), beacon(AP2, SSID("") / RATES / DS(11), cap=0x0421), None),
        ("beacon zero-filled ssid", rt(2462, -80), beacon(AP2, E(0, bytes(9)) / RATES / DS(11), cap=0x0421), None),
        # scapy reads the capability field big-endian: 0x1104 is the bytes 11 04, so the privacy bit (0x0010) is set.
        ("beacon WEP", rt(2412, -66), beacon(AP, SSID("Old") / RATES / DS(1), cap=0x1104), None),
        ("beacon WPA1", rt(2412, -66), beacon(AP, SSID("Older") / RATES / DS(1) / WPA1, cap=0x0431), None),
        ("beacon WPA1 + WPA2", rt(2412, -66), beacon(AP, SSID("Mixed") / RATES / DS(1) / rsn() / WPA1, cap=0x0431), None),
        ("beacon CSA on DFS", rt(5260, -58), beacon(AP, SSID("DFS") / RATES_G / CSA / HT_OP(52) / rsn()), None),
        ("beacon extended CSA", rt(5260, -58), beacon(AP, SSID("DFS2") / RATES_G / ECSA / HT_OP(52) / rsn()), None),
        ("beacon 6 GHz", rt(6135, -62), beacon(AP2, SSID("Six") / RATES_G / rsn(akm=(8,), caps=0x00c0) / HE_CAP / HE_OP(63)
                                                / E(255, bytes([59, 0, 0])), cap=0x1011), None),
        ("beacon 6 GHz labelled 5 GHz", rt(5185, -62), beacon(AP2, SSID("Six") / RATES_G / rsn(akm=(8,), caps=0x00c0)), 6135),
        ("beacon no radiotap frequency", RadioTap(present="dBm_AntSignal", dBm_AntSignal=-50),
         beacon(AP, SSID("NoFreq") / RATES / DS(6)), 2437),
        ("beacon mesh, interworking, mbssid, aruba", rt(5500, -70), beacon(AP, SSID("Corp") / RATES_G / HT_OP(100) / rsn(akm=(5,))
                                                                          / MESH_ID / INTERWORKING / MBSSID / ARUBA
                                                                          / wps_ie(configured=False, locked=True, names=False)), None),
        ("beacon no tsf uptime", rt(2437, -50), beacon(AP, SSID("New") / RATES / DS(6), tsf=0), None),
        ("beacon unicode ssid", rt(2437, -50), beacon(AP, SSID("Café ☕") / RATES / DS(6)), None),
        ("beacon latin-1 ssid", rt(2437, -50), beacon(AP, E(0, b"Caf\xe9") / RATES / DS(6)), None),
        ("probe response", rt(5180, -52), beacon(AP, SSID("Example Home") / RATES_G / HT_OP(36) / rsn(), subtype=5, dst=STA), None),
        ("probe request wildcard", rt(2437, -40), mgmt(4, BC, STA, BC, Dot11ProbeReq() / SSID("") / RATES / XRATES / HT_CAP
                                                        / EXTCAP / E(127, bytes([0, 0, 8, 0, 0, 0, 0, 0x40])) / MBO / APPLE_IE), None),
        ("probe request named", rt(2437, -41), mgmt(4, BC, STA, BC, Dot11ProbeReq() / SSID("Example Home") / RATES_G
                                                     / HT_CAP / VHT_CAP / HE_CAP / RM / SUPP_CH / POWER_CAP / OPCLASS / wps_ie()
                                                     / p2p_ie()), None),
        ("probe request public", rt(5180, -44), mgmt(4, BC, STA2, BC, Dot11ProbeReq() / SSID("") / RATES_G / HT_CAP / EXTCAP),
         None),
        ("association request", rt(5180, -43), mgmt(0, AP, STA, AP, Dot11AssoReq(cap=0x1431, listen_interval=10)
                                                     / SSID("Example Home") / RATES_G / rsn() / HT_CAP / VHT_CAP / HE_CAP / EXTCAP
                                                     / RM / SUPP_CH / POWER_CAP), None),
        ("reassociation request", rt(5180, -43), mgmt(2, AP2, STA, AP2, Dot11ReassoReq(cap=0x1431, listen_interval=10,
                                                                                       current_AP=AP) / SSID("Example Home")
                                                       / RATES_G / rsn() / HT_CAP / MDE), None),
        ("association response ok", rt(5180, -45), mgmt(1, STA, AP, AP, Dot11AssoResp(cap=0x0431, status=0, AID=0xc001)
                                                        / RATES_G), None),
        ("association response refused", rt(5180, -45), mgmt(1, STA, AP, AP, Dot11AssoResp(cap=0x0431, status=17, AID=0)
                                                             / RATES_G), None),
        ("reassociation response", rt(5180, -45), mgmt(3, STA, AP2, AP2, Dot11AssoResp(cap=0x0431, status=0, AID=0xc002)), None),
        ("auth from AP", rt(5180, -45), mgmt(11, STA, AP, AP, Dot11Auth(algo=0, seqnum=2, status=0)), None),
        ("auth from client", rt(5180, -45), mgmt(11, AP, STA, AP, Dot11Auth(algo=0, seqnum=1, status=0)), None),
        ("auth SAE from client", rt(5180, -45), mgmt(11, AP, STA, AP, Dot11Auth(algo=3, seqnum=1, status=0)
                                                     / Dot11Elt(ID=1, info=b"\x02\x04")), None),
        ("deauth from AP to client", rt(5180, -45), mgmt(12, STA, AP, AP, Dot11Deauth(reason=7)), None),
        ("deauth broadcast", rt(5180, -45), mgmt(12, BC, AP, AP, Dot11Deauth(reason=3)), None),
        ("disassoc from client", rt(5180, -45), mgmt(10, AP, STA, AP, Dot11Disas(reason=8)), None),
        ("deauth protected (11w)", rt(5180, -45), mgmt(12, STA, AP, AP, Dot11Deauth(reason=7), fc=0x40), None),
        ("data to AP", rt(5180, -48), data(AP, STA, AP2, True, False), None),
        ("qos data from AP", rt(5180, -49), data(STA, AP, AP, False, True, qos=True), None),
        ("data multicast from AP", rt(5180, -49), data("01:00:5e:00:00:fb", AP, STA2, False, True), None),
        ("data WDS", rt(5180, -49), data(AP2, AP, STA, True, True), None),
        ("null data to AP", rt(5180, -49), Dot11(type=2, subtype=4, addr1=AP, addr2=STA2, addr3=AP, FCfield=0x11, SC=5 << 4), None),
        ("ack", rt(5180, -49), Dot11(type=1, subtype=13, addr1=STA), None),
        ("rts", rt(5180, -49), Dot11(type=1, subtype=11, addr1=AP, addr2=STA), None),
        ("action frame", rt(5180, -49), mgmt(13, AP, STA, AP, b"\x05\x00"), None),
        ("beacon from multicast address", rt(2437, -50), beacon("03:11:22:33:44:55", SSID("Bad") / RATES / DS(6)), None),
    ]
    out = []
    for name, radio, frame, tuned in cases:
        pkt = radio / frame
        pkt = RadioTap(bytes(pkt))  # as captured: parsed back from the bytes
        pkt.time = TS
        obs = wifi.parse_frame(pkt, wifi.FingerprintGate(), tuned, wifi.AttrGates(), True)
        rssi, freq = wifi._radio(pkt)
        out.append({"name": name, "frame": bytes(frame), "rssi": rssi, "freq": freq, "tuned": tuned, "obs": obs})
    return out


# --------------------------------------------------------------------------------------------------------- Bluetooth
class Sink:
    def __init__(self):
        self.items = []

    def put(self, o):
        self.items.append(o)


def bt_sensor():
    s = object.__new__(bluetooth.BluetoothSensor)
    s.name, s.dev, s.store = "bt:test", "hci0", Sink()
    s.packets, s.last_adv = 0, 0
    s.fp_seen, s.intervals, s.interval_sent = {}, bluetooth.AdvInterval(), {}
    s.classic, s.named, s.handles = {}, set(), {}
    s.inquiring, s.name_pending = False, None
    s.ext_scan, s.mode_switches = True, 0
    s.cfg = None
    return s


def adv_report(evt_type, atype, addr, data, rssi):
    a = bytes.fromhex(addr.replace(":", ""))[::-1]
    p = bytes([0x02, 1, evt_type, atype]) + a + bytes([len(data)]) + data + bytes([rssi & 0xFF])
    return bytes([0x04, 0x3E, len(p)]) + p


def ext_report(evt_type, atype, addr, data, rssi, prim=1, sec=0, sid=0, tx=127):
    a = bytes.fromhex(addr.replace(":", ""))[::-1]
    p = bytes([0x0D, 1]) + le16(evt_type) + bytes([atype]) + a + bytes([prim, sec, sid, tx & 0xFF, rssi & 0xFF]) + le16(0) \
        + bytes([0]) + bytes(6) + bytes([len(data)]) + data
    return bytes([0x04, 0x3E, len(p)]) + p


def ad(*fields):
    return b"".join(bytes([len(v) + 1, t]) + v for t, v in fields)


def bt_cases():
    apple_np = bytes.fromhex("4c00" "0719" "01" "0e20" "2b" "99" "8f" "0100" "00" + "00" * 16)  # AirPods Pro (0x200e)
    findmy = bytes.fromhex("4c00" "121900" + "00" * 22 + "0100")
    airtag = bytes.fromhex("4c00" "1219" "10" + "00" * 24)
    nearby = bytes.fromhex("4c00" "1005" "0318" "a4c1e0")
    ibeacon = bytes.fromhex("4c00" "0215" "fda50693a4e24fb1afcfc6eb07647825" "2711" "4cb9" "c5")
    ms_cdp = bytes.fromhex("0600" "0109" "2002" + "00" * 24)
    swift = bytes.fromhex("0600" "030080" "4d6f757365")
    samsung = bytes.fromhex("7500" "4204" "0180" "60" "aa")
    mi = le16(0xfe95) + bytes.fromhex("3020" "5b05" "01" "aabbccddeeff" "0d")
    fast_pair = le16(0xfe2c) + bytes.fromhex("f52494")
    matter = le16(0xfff6) + bytes.fromhex("00" "0f3f" "f1ff" "0180" "00")
    pba = le16(0x1856) + bytes([0x06, 0x00])
    baa = le16(0x1852) + bytes.fromhex("332211")
    events = [
        ("legacy ADV_IND random static", adv_report(0, 1, "c3:11:22:33:44:55", ad((1, b"\x06"), (9, b"Thermo 1"), (10, b"\xf4"),
                                                                                    (3, le16(0x181a) + le16(0x180f)), (0x19, le16(0x0300))), -61)),
        ("legacy scan response", adv_report(4, 1, "c3:11:22:33:44:55", ad((9, b"Thermo 1")), -62)),
        ("legacy nonconnectable resolvable apple", adv_report(3, 1, "5a:11:22:33:44:55", ad((1, b"\x1a"), (0xFF, apple_np)), -70)),
        ("legacy findmy", adv_report(3, 1, "4c:11:22:33:44:56", ad((0xFF, findmy)), -75)),
        ("legacy airtag", adv_report(3, 1, "d1:11:22:33:44:57", ad((0xFF, airtag)), -80)),
        ("legacy nearby", adv_report(0, 1, "6e:11:22:33:44:58", ad((1, b"\x1a"), (10, b"\x0c"), (0xFF, nearby)), -55)),
        ("legacy ibeacon public", adv_report(3, 0, "00:16:3e:11:22:33", ad((1, b"\x06"), (0xFF, ibeacon)), -64)),
        ("legacy microsoft cdp", adv_report(3, 1, "2b:11:22:33:44:59", ad((0xFF, ms_cdp)), -77)),
        ("legacy swift pair", adv_report(0, 1, "c4:11:22:33:44:5a", ad((1, b"\x06"), (0xFF, swift), (9, b"Mouse")), -50)),
        ("legacy samsung public", adv_report(0, 0, "00:16:3e:44:55:66", ad((0xFF, samsung)), -66)),
        ("legacy xiaomi mibeacon", adv_report(0, 0, "a4:c1:38:11:22:33", ad((1, b"\x06"), (0x16, mi), (9, b"LYWSD03MMC")), -58)),
        ("legacy fast pair", adv_report(0, 1, "4f:11:22:33:44:5b", ad((1, b"\x06"), (0x16, fast_pair), (10, b"\xf6")), -48)),
        ("legacy matter", adv_report(0, 1, "c5:11:22:33:44:5c", ad((1, b"\x06"), (0x16, matter)), -57)),
        ("legacy uuids 32 and 128", adv_report(0, 1, "c6:11:22:33:44:5d",
                                               ad((5, bytes.fromhex("0d180000") + bytes.fromhex("78563412")),
                                                  (7, bytes.fromhex("fb349b5f80000080001000000f180000")
                                                   + bytes.fromhex("9ecadc240ee5a9e093f3a3b50100406e")),
                                                  (0x12, le16(6) + le16(12))), -59)),
        ("legacy dual mode flags", adv_report(0, 0, "00:16:3e:77:88:99", ad((1, b"\x1a"), (8, b"Short"), (9, b"Full name")), -63)),
        ("legacy shortened name only", adv_report(0, 1, "c7:11:22:33:44:5e", ad((8, b"Shrt\x00")), -63)),
        ("legacy esl and encrypted", adv_report(3, 1, "c8:11:22:33:44:5f", ad((0x34, b"\x01\x02"), (0x31, b"\x00" * 8)), -88)),
        ("legacy rssi unknown", adv_report(3, 1, "c9:11:22:33:44:60", ad((1, b"\x04")), 127)),
        ("legacy truncated ad", adv_report(3, 1, "ca:11:22:33:44:61", b"\x05\x09ab", -70)),
        ("extended coded", ext_report(0x0001, 1, "cb:11:22:33:44:62", ad((1, b"\x06"), (9, b"Far sensor")), -94, prim=3)),
        ("extended 2M secondary", ext_report(0x0000, 1, "cc:11:22:33:44:63", ad((0xFF, samsung)), -71, prim=1, sec=2, tx=0xf8)),
        ("extended legacy pdu", ext_report(0x0013, 0, "00:16:3e:aa:bb:cc", ad((1, b"\x06"), (9, b"Legacy in ext")), -52)),
        ("extended scan response", ext_report(0x0009 | 0x08, 1, "cd:11:22:33:44:64", ad((9, b"Resp")), -60)),
        ("extended auracast", ext_report(0x0000, 1, "ce:11:22:33:44:65", ad((0x16, pba), (0x16, baa), (0x30, b"Pub TV")), -67, sec=2)),
        ("inquiry result rssi", bytes([0x04, 0x22, 15, 1]) + bytes.fromhex("332211163e00")[::-1][::-1] + bytes([1, 0])
         + bytes.fromhex("0c025a") + le16(0x1234) + bytes([0xc8])),
        ("inquiry result", bytes([0x04, 0x02, 15, 1]) + bytes.fromhex("665544163e00") + bytes([1, 0, 0]) + bytes.fromhex("040420")
         + le16(0x1234)),
        ("extended inquiry result", bytes([0x04, 0x2F, 255, 1]) + bytes.fromhex("998877163e00") + bytes([1, 0])
         + bytes.fromhex("14010c") + le16(0x4321) + bytes([0xd0])
         + (ad((9, b"Laptop"), (3, le16(0x110a) + le16(0x110c)), (10, b"\x04")) + bytes(240))[:240]),
        ("inquiry complete", bytes([0x04, 0x01, 1, 0])),
        ("command complete", bytes([0x04, 0x0E, 4, 1, 0x0c, 0x20, 0])),
    ]
    out = []
    for name, ev in events:
        s = bt_sensor()
        for e in bluetooth.parse_event(ev):
            s._handle(e, TS)
        out.append({"name": name, "event": ev, "obs": s.store.items})
    # Advertising interval: the same device ten times, 100 ms apart, on one sensor.
    s = bt_sensor()
    ev = adv_report(3, 1, "d2:11:22:33:44:66", ad((1, b"\x04"), (0xFF, samsung)), -60)
    times = [TS + i * 0.1 for i in range(10)]
    for t in times:
        for e in bluetooth.parse_event(ev):
            s._handle(e, t)
    interval = {"event": ev, "times": times, "obs": s.store.items}
    return out, interval


def ad_cases():
    """parse_ad on its own, for the edge cases."""
    blobs = [b"", b"\x00", b"\x02\x01\x06\x00\x05\x09abcd", b"\x03\x03\x0f", b"\x11\x07" + bytes(15),
             b"\x02\xff\x4c", b"\x03\xff\x4c\x00", b"\x05\x12\x05\x00\x06\x00", b"\x05\x12\x06\x00\xff\xff",
             b"\x02\x0a\x80", b"\x04\x19\x41\x03\x00", b"\x03\x19\xff\xff",
             b"\x1b\xff\x4c\x00\x02\x15" + bytes(16) + b"\x00\x01\x00\x02\xc5" + b"\x00" * 2]
    return [{"data": b, "ad": bluetooth.parse_ad(b)} for b in blobs]


# ------------------------------------------------------------------------------------------------------------ Zigbee
def zb_frame(fc, seq, body):
    return le16(fc) + bytes([seq]) + body


def zigbee_cases():
    pan = le16(0x1a62)
    beacon_zb = zb_frame(0x8000, 1, pan + le16(0x0000) + bytes.fromhex("ff8f" "00" "00") + bytes.fromhex("00" "22" "8c")
                         + bytes.fromhex("1122334455667788") + bytes.fromhex("ffffff" "00"))
    thread_beacon = zb_frame(0xc000, 2, pan + bytes.fromhex("8877665544332211") + bytes.fromhex("ff0f" "00" "00")
                             + bytes([3, 0x21]) + b"OpenThreadDemo".ljust(16, b"\x00") + bytes.fromhex("dead00beef00cafe"))
    nwk = bytes.fromhex("0812") + le16(0x0000) + le16(0x4f2a) + bytes([30, 77]) + bytes([0x28, 1, 2, 3, 4])
    nwk_sec = bytes.fromhex("0812") + le16(0xfffc) + le16(0x4f2a) + bytes([30, 78]) + bytes([0x28]) + le16(5) + le16(0) \
        + bytes.fromhex("8877665544332200") + bytes([0]) + bytes(4)
    nwk_relay = bytes.fromhex("0802") + le16(0x0000) + le16(0x7777) + bytes([29, 79]) + bytes(6)
    data_zb = zb_frame(0x8841, 3, pan + le16(0x0000) + le16(0x4f2a) + nwk)
    data_zb_sec = zb_frame(0x8841, 4, pan + le16(0xffff) + le16(0x4f2a) + nwk_sec)
    data_relay = zb_frame(0x8841, 5, pan + le16(0x0000) + le16(0x1111) + nwk_relay)
    poll = zb_frame(0x8863, 6, pan + le16(0x0000) + le16(0x2b2b) + bytes([4]))
    assoc = zb_frame(0xc823, 7, pan + le16(0x0000) + le16(0xffff) + bytes.fromhex("1122334455667700") + bytes([1, 0x8e]))
    ack = zb_frame(0x0002, 7, b"")
    thread_data = zb_frame(0xcc69, 8, pan + bytes.fromhex("0102030405060708") + bytes.fromhex("1112131415161718")
                           + bytes([0x0d]) + struct.pack("<I", 99) + bytes([1]) + bytes(12))
    thread_poll = zb_frame(0x886b, 9, pan + le16(0x0400) + le16(0x0401) + bytes([0x0d]) + struct.pack("<I", 9) + bytes([1]) + bytes(5))
    mle = zb_frame(0xcc41, 10, pan + bytes.fromhex("ffff") + bytes.fromhex("1112131415161718") + bytes.fromhex("7f3b01f04d4c4d4c") + bytes(10))
    gp = zb_frame(0x0801, 11, le16(0xffff) + le16(0xffff) + bytes([0x8c, 0x30]) + struct.pack("<I", 0x01234567)
                  + struct.pack("<I", 5) + bytes([0x22]))
    gp_nwk = zb_frame(0x0801, 12, le16(0xffff) + le16(0xffff) + bytes([0x0c]) + struct.pack("<I", 0x0badcafe) + bytes([0x10]))
    short = b"\x01"
    bad_mode = zb_frame(0x0441, 1, b"\x00" * 6)
    frames = [(11, beacon_zb), (15, thread_beacon), (11, data_zb), (11, data_zb_sec), (11, data_relay), (11, poll),
              (11, assoc), (11, ack), (15, thread_data), (15, thread_poll), (15, mle), (11, gp), (11, gp_nwk), (20, short),
              (20, bad_mode)]
    captured = [{"channel": ch, "data": d, "rssi": -40 - i, "lqi": 200 - i} for i, (ch, d) in enumerate(frames)]
    batch = zigbee_radio.FrameBatch()
    for c in captured:
        batch.add(c)
    drained = batch.drain(TS)
    macs = []
    for c in captured:
        m = zigbee_radio.parse_mac(c["data"])
        macs.append(None if m is None else {"type": m.type, "secured": m.secured, "sequence": m.sequence, "dst_pan": m.dst_pan,
                                             "dst": m.dst, "src_pan": m.src_pan, "src": m.src, "payload": m.payload,
                                             "sec_level": m.sec_level, "key_mode": m.key_mode, "hint": m.hint})
    return {"captured": captured, "macs": macs, "drained": drained}


def channel_cases():
    specs = ["", "default", "all", "5", "6", "1,5,9", "1,6,11", "36-48", "6g:37", "6g:5,6g:37-69", "2g:6", "5g:36",
             "6ghz", "6all", "2.4", "6e", "6g:psc", "5common", "1,1,6", " 1 , 6 ", "14", "100-112", "2.4ghz,6ghz"]
    out = []
    for s in specs:
        out.append({"spec": s, "freqs": config.parse_channels(s)})
    labels = {str(f): config.chan_label(f) for f in (2412, 2484, 5180, 5935, 6135, 7115, 3000)}
    return {"specs": out, "labels": labels}


# ------------------------------------------------------------------ LoRa (lora_protocols.py, meshtastic.py, lora.parse_rx)
def profile_dict(p):
    return {"name": p.name, "label": p.label, "freq": p.freq, "bw": p.bw, "sf": p.sf, "cr": p.cr, "sync": p.sync,
            "preamble": p.preamble, "hint": p.hint, "describe": p.describe()}


def mt_frame(to=0xFFFFFFFF, frm=0x1A2B3C4D, pid=0xDEADBEEF, hop_limit=3, hop_start=3, want_ack=False, via_mqtt=False, chan=0x08,
             next_hop=0, relay=0x4D, body=bytes(range(20))):
    flags = hop_limit | (8 if want_ack else 0) | (0x10 if via_mqtt else 0) | (hop_start << 5)
    return struct.pack("<IIIBBBB", to, frm, pid, flags, chan, next_hop, relay) + body


def mc_advert(name=b"Garage Repeater", ntype=2, location=True, route=1, hops=0, hsize=1, extra=0):
    flags = ntype | (0x10 if location else 0) | (0x80 if name else 0) | extra
    body = bytes(range(32)) + struct.pack("<I", 1_700_000_000) + bytes(64) + bytes([flags]) + (bytes(8) if location else b"") \
        + (bytes(2) if extra & 0x20 else b"") + (bytes(2) if extra & 0x40 else b"") + name
    return bytes([(4 << 2) | route, hops | ((hsize - 1) << 6)]) + bytes(range(hops * hsize)) + body


def lw_uplink(dev_addr=0x26011BDA, fcnt=1234, fport=1, payload=b"\x01\x02\x03", mtype=2, fopts=b"", adr=True, ack=False):
    fctrl = (0x80 if adr else 0) | (0x20 if ack else 0) | len(fopts)
    return bytes([mtype << 5]) + struct.pack("<I", dev_addr) + bytes([fctrl]) + struct.pack("<H", fcnt) + fopts + bytes([fport]) + payload + b"\xAA\xBB\xCC\xDD"


def varint(v):
    out = bytearray()
    while True:
        b = v & 0x7F
        v >>= 7
        out.append(b | (0x80 if v else 0))
        if not v:
            return bytes(out)


def pb(num, value):
    if isinstance(value, int):
        return varint(num << 3) + varint(value)
    if isinstance(value, bytes):
        return varint(num << 3 | 2) + varint(len(value)) + value
    return varint(num << 3 | 5) + struct.pack("<f", value)


def fixed32(num, v):
    return varint(num << 3 | 5) + struct.pack("<i", v)


def mt_packet(plain, frm=0x1A2B3C4D, pid=0xCAFEF00D, key=meshtastic.DEFAULT_KEY):
    body = meshtastic.aes_ctr(key, struct.pack("<QI", pid, frm) + bytes(4), plain) if key else plain
    return struct.pack("<IIIBBBB", 0xFFFFFFFF, frm, pid, 3 | (3 << 5), 8, 0, 0x4D) + body


def lora_cases():
    import random
    rnd = random.Random(1234)
    specs = [("", "EU868"), ("", "US915"), ("", "ANZ"), ("", "XX"), ("meshtastic:EU868", None), ("meshtastic:us:mediumfast", None),
             ("meshtastic:EU433:ShortTurbo", None), ("meshtastic:XX", None), ("meshtastic:EU868:Nope", None), ("lorawan:EU868", None),
             ("lorawan:US915:7-10", None), ("lorawan:IN865:7", None), ("lorawan:au915:12", None), ("lorawan:EU868:13", None),
             ("lorawan:KR920:7-8", None), ("mesh=868.3/125/7/5/0x34", None), ("Mine=869.525/250/11/5/43/16", None),
             ("a=868/62.5/8/5/0x12", None), ("x=1000/125/7/5/0x12", None), ("bad name=868/125/7/5/0x12", None),
             ("x=868/123/7/5/0x12", None), ("x=868/125/7/5", None), ("x=868/125/7/5/zz", None),
             ("meshtastic-eu868,meshcore-eu868", None), ("lorawan-eu868, lorawan:eu868", None), ("nonsense", None),
             ("LoRa-APRS-433 , meshcore-nz-narrow", None), (" , ", "EU868")]
    profiles = []
    for spec, region in specs:
        try:
            got = [profile_dict(p) for p in (lora_protocols.parse_profiles(spec, region) if region else lora_protocols.parse_profiles(spec))]
            profiles.append({"spec": spec, "region": region, "profiles": got})
        except ValueError:
            profiles.append({"spec": spec, "region": region, "error": True})
    freqs = {f"{r}/{p}": lora_protocols.meshtastic_frequency(r, p) for r in lora_protocols.MESHTASTIC_REGIONS for p in lora_protocols.MESHTASTIC_PRESETS}
    rx = ["RX,1234,869.5250,250.0,11,0x2B,-97.5,6.25,-1200,4,deadbeef", "RX,1,868.1000,125.0,7,0x34,-120.0,-12.50,15,2,AbCd",
          "RX,1,868.1,125,7,43,-80,1,0,1,00", "RX,1,868.1,125,7,0x34,-80,1,0,3,abcd", "RX,1,868.1,125,7,0x34,-80,1,0,0,",
          "RX,1,868.1,125,7,0x34,-80,1,0,1,zz", "RX,1,868.1,125,seven,0x34,-80,1,0,1,00", "STAT,1,2,3", "PONG", "RX,1,2"]
    frames = [
        (mt_frame(), 0x2B, "meshtastic"), (mt_frame(), 0x2B, None), (mt_frame(), 0x12, None), (mt_frame(), 0x34, None),
        (mt_frame(), 0x12, "meshtastic"), (mt_frame(to=0x0C0FFEE0, hop_limit=1, want_ack=True, via_mqtt=True, next_hop=0xE0), 0x2B, None),
        (mt_frame(hop_start=0), 0x2B, None), (mt_frame(hop_limit=7, hop_start=2), 0x2B, None), (mt_frame()[:16], 0x2B, None),
        (mt_frame(frm=0, pid=0, to=0), 0x2B, None), (mt_frame(frm=0xFFFFFFFF), 0x2B, None),
        (mc_advert(), 0x12, "meshcore"), (mc_advert(), 0x12, None), (mc_advert(name=b"Hut \xe2\x9c\x93"), 0x12, None),
        (mc_advert(name=b""), 0x12, "meshcore"), (mc_advert(ntype=1, location=False, route=0, hops=2), 0x12, "meshcore"),
        (mc_advert(hops=3, hsize=2, extra=0x60), 0x12, "meshcore"), (mc_advert(ntype=9), 0x12, "meshcore"),
        (bytes([(2 << 2) | 1, 2, 0x10, 0x20, 0xA1, 0x4F, 0xBE, 0xEF]) + bytes(32), 0x12, "meshcore"),
        (bytes([(3 << 2) | 2, 0]) + bytes(4), 0x12, "meshcore"), (bytes([(7 << 2) | 1, 0, 0xA1]) + bytes(40), 0x12, "meshcore"),
        (bytes([(5 << 2) | 1, 1, 0x33, 0x11, 0, 0, 0]), 0x12, "meshcore"), (bytes([(11 << 2) | 2, 0, 0x90]), 0x12, "meshcore"),
        (bytes([(12 << 2) | 1, 0]) + bytes(8), 0x12, "meshcore"), (bytes([(15 << 2) | 1, 0x41]) + bytes(80), 0x12, "meshcore"),
        (bytes([(2 << 2) | 0, 0, 0, 0]), 0x12, "meshcore"),
        (lw_uplink(), 0x34, None), (lw_uplink(), 0x34, "lorawan"), (lw_uplink(), 0x12, "lorawan"), (lw_uplink(), 0x12, None),
        (lw_uplink(mtype=3, ack=True), 0x34, None), (lw_uplink(mtype=4, fopts=b"\x02\x03"), 0x34, None),
        (lw_uplink(mtype=5, fopts=bytes(15), payload=b""), 0x34, None), (lw_uplink(payload=b"")[:-1], 0x34, None),
        (bytes([0x00]) + bytes.fromhex("70b3d57ed0000000")[::-1] + bytes.fromhex("0004a30b001a2b3c")[::-1] + b"\x12\x34\x00\x11\x22\x33", 0x34, None),
        (bytes([0x20]) + bytes(16), 0x34, None), (bytes([0x20]) + bytes(20), 0x34, None), (bytes([0xC0]) + bytes(14), 0x34, None),
        (bytes([0xE0]) + bytes(14), 0x34, None), (bytes([0x41]) + bytes(14), 0x34, None),
        (b"<\xff\x01PA3ABC-9>APLRT1,WIDE1-1:!5207.00N/00455.00E-testing", 0x12, "lora-aprs"),
        (b"<\xff\x01PA3ABC>APRS::PB0XYZ   :hello", 0x12, None), (b"<\xff\x01PA3ABC>APRS,WIDE1-1*,WIDE2-1:>status", 0x12, None),
        (b"<\xff\x01not a packet", 0x12, None), (b"<\xff\x01ab", 0x12, None), (b"<\xff\x01X>Y:_weather\nline", 0x12, None),
        (b"hello world, plain text", 0x12, None), (b"\x00\x01", 0x12, None), (b"", 0x12, None), (bytes(range(256))[:200], 0x12, None),
    ]
    for _ in range(400):
        n = rnd.randrange(0, 60)
        frames.append((bytes(rnd.randrange(256) for _ in range(n)), rnd.choice([0x12, 0x2B, 0x34, 0x77]),
                       rnd.choice([None, "meshtastic", "meshcore", "lorawan", "lora-aprs"])))
    for _ in range(100):  # structured noise: plausible first bytes
        head = rnd.choice([b"<\xff\x01", bytes([rnd.randrange(8) << 5]), bytes([(rnd.randrange(16) << 2) | rnd.randrange(4), rnd.randrange(256)])])
        frames.append((head + bytes(rnd.randrange(256) for _ in range(rnd.randrange(0, 120))), rnd.choice([0x12, 0x2B, 0x34]),
                       rnd.choice([None, "meshcore", "lorawan"])))
    classify = [{"hex": f.hex(), "sync": sync, "hint": hint, "result": lora_protocols.classify(f, sync, hint)} for f, sync, hint in frames]

    position = fixed32(1, 521234567) + fixed32(2, 49876543) + pb(3, 12) + pb(19, 7) + pb(23, 16) + pb(15, 3)
    packets = [
        mt_packet(pb(1, 1) + pb(2, "Hello mesh, test 1 2 3 \u2713".encode())), mt_packet(pb(1, 3) + pb(2, position)),
        mt_packet(pb(1, 3) + pb(2, fixed32(1, -335000000) + fixed32(2, -700000000) + pb(3, (1 << 32) - 5))),
        mt_packet(pb(1, 4) + pb(2, pb(1, b"!1a2b3c4d") + pb(2, b"Garage node") + pb(3, b"GRG") + pb(5, 43) + pb(7, 2))),
        mt_packet(pb(1, 4) + pb(2, pb(2, b"x") + pb(5, 200) + pb(7, 99))),
        mt_packet(pb(1, 67) + pb(2, pb(1, 1700000000) + pb(2, pb(1, 87) + pb(2, 4.05) + pb(3, 12.5) + pb(4, 0.33) + pb(5, 3600)))),
        mt_packet(pb(1, 67) + pb(2, pb(3, pb(1, 21.5) + pb(2, 45.0) + pb(3, 1013.25)))),
        mt_packet(pb(1, 8) + pb(2, fixed32(2, 521000000) + fixed32(3, 49000000) + pb(6, b"Camp") + pb(7, b"Meet here"))),
        mt_packet(pb(1, 1) + pb(2, b"no key at all"), key=None), mt_packet(pb(1, 6) + pb(2, b"admin payload")),
        mt_packet(pb(1, 1) + pb(2, b"secret"), key=bytes(range(16))), mt_packet(pb(1, 99) + pb(2, b"x")), mt_packet(pb(1, 1)),
        mt_packet(pb(1, 1) + pb(2, b"\x00\x01\x02\xff\xfe")), mt_packet(pb(1, 3) + pb(2, b"\x0d\x01")),
        mt_packet(pb(1, 4) + pb(2, b"\x12\xff\xff\x03")), mt_packet(pb(1, 1) + pb(2, b"\xc3\x28")), mt_packet(pb(1, 67) + pb(2, b"\x12\x05\x08")),
        mt_packet(pb(1, 8) + pb(2, b"\x10")), mt_packet(pb(1, 1) + pb(2, ("long " * 80).encode())), mt_frame(), b"", bytes(17),
        mt_packet(pb(1, 1) + pb(2, b"tab\tand\nnewline\x07bell")),
    ]
    for _ in range(300):
        packets.append(bytes(rnd.randrange(256) for _ in range(rnd.randrange(0, 80))))
    decode = [{"hex": p.hex(), "result": meshtastic.decode(p)} for p in packets]
    airtime = [{"len": n, "sf": sf, "bw": bw, "cr": cr, "preamble": pre, "ms": lora_protocols.airtime_ms(n, sf, bw, cr, pre)}
               for n, sf, bw, cr, pre in [(20, 7, 125, 5, 8), (50, 11, 250, 5, 16), (255, 12, 125, 8, 8), (1, 7, 500, 5, 8), (30, 8, 62.5, 5, 16)]]
    return {"profiles": profiles, "meshtastic_freqs": freqs, "rx": [{"line": x, "rx": parse_rx(x)} for x in rx],
            "classify": classify, "decode": decode, "airtime": airtime}


def main():
    os.makedirs(OUT, exist_ok=True)
    os.makedirs(RES, exist_ok=True)
    meta = {"sniffowireless": git_rev(), "ts": TS}
    write("wifi.json", {"meta": meta, "cases": wifi_cases()})
    events, interval = bt_cases()
    write("bluetooth.json", {"meta": meta, "events": events, "interval": interval, "ad": ad_cases()})
    write("zigbee.json", {"meta": meta, **zigbee_cases()})
    write("channels.json", {"meta": meta, **channel_cases()})
    write("lora.json", {"meta": meta, **lora_cases()})
    companies = {f"{k:04x}": v for k, v in sorted(oui._COMPANIES.items())}
    with open(os.path.join(RES, "bt_companies.json"), "w", encoding="utf-8") as f:
        json.dump({"source": f"sniffowireless {git_rev()} (Bluetooth SIG assigned numbers, with its short names)",
                   "unregistered_from": oui.UNREGISTERED_FROM, "companies": companies}, f, ensure_ascii=False, separators=(",", ":"))
    print("fixtures written to", OUT)


if __name__ == "__main__":
    main()
