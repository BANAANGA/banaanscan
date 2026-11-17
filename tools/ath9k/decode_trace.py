#!/usr/bin/env python3
"""Turns a USB capture of the ath9k_htc driver (from capture-reference.sh) into a readable log: firmware download,
HTC messages and every WMI command, with register reads and writes spelled out.

    tshark -r usb.pcapng -Y "usb.device_address==ADDR && usb.bus_id==BUS" -T fields -e frame.time_epoch \\
        -e usb.urb_type -e usb.transfer_type -e usb.endpoint_address -e usb.setup.bRequest -e usb.setup.wValue \\
        -e usb.data_len -e usb.capdata > usb.tsv
    python3 tools/ath9k/decode_trace.py usb.tsv steps.txt > wmi.log

With --regs, prints only register operations, one per line ("W reg val", "R reg = val", "M reg set clr"), the form
the PC prototype's trace uses, so the two can be compared with diff.
"""
import struct
import sys

WMI = {1: "ECHO", 2: "ACCESS_MEMORY", 3: "GET_FW_VERSION", 4: "DISABLE_INTR", 5: "ENABLE_INTR", 6: "ATH_INIT", 7: "ABORT_TXQ",
       8: "STOP_TX_DMA", 9: "ABORT_TX_DMA", 10: "DRAIN_TXQ", 11: "DRAIN_TXQ_ALL", 12: "START_RECV", 13: "STOP_RECV",
       14: "FLUSH_RECV", 15: "SET_MODE", 16: "NODE_CREATE", 17: "NODE_REMOVE", 18: "VAP_REMOVE", 19: "VAP_CREATE",
       20: "REG_READ", 21: "REG_WRITE", 22: "RC_STATE_CHANGE", 23: "RC_RATE_UPDATE", 24: "TARGET_IC_UPDATE",
       25: "TX_AGGR_ENABLE", 26: "TGT_DETACH", 27: "NODE_UPDATE", 28: "INT_STATS", 29: "TX_STATS", 30: "RX_STATS",
       31: "BITRATE_MASK", 32: "REG_RMW"}


def main():
    regs_only = "--regs" in sys.argv
    args = [a for a in sys.argv[1:] if not a.startswith("--")]
    steps = []
    if len(args) > 1:
        for line in open(args[1]):
            t, _, what = line.strip().partition(" ")
            steps.append((float(t), what))
    pending = {}  # seq -> (cmd, payload)
    t0 = None
    for line in open(args[0]):
        f = line.rstrip("\n").split("\t")
        if len(f) < 8:
            f += [""] * (8 - len(f))
        t, urb, ttype, ep, req, wval, dlen, data = f
        t = float(t)
        t0 = t0 or t
        while steps and steps[0][0] <= t:
            if not regs_only:
                print("==== %s" % steps[0][1])
            else:
                print("# %s" % steps[0][1])
            steps.pop(0)
        raw = bytes.fromhex(data.replace(":", "")) if data else b""
        ts = "%9.3f" % (t - t0)
        if ttype == "0x02" and urb == "'S'" and req:
            if not regs_only:
                print(ts, "CTRL req 0x%02x value %s len %s" % (int(req), wval, dlen))
            continue
        if ep == "0x04" and urb == "'S'" and len(raw) >= 12:
            epid, flags, plen = raw[0], raw[1], struct.unpack(">H", raw[2:4])[0]
            body = raw[8:8 + plen]
            if epid == 0:
                if not regs_only:
                    print(ts, "HTC ctrl", body.hex())
                continue
            cmd, seq = struct.unpack(">HH", body[:4])
            p = body[4:]
            pending[seq] = (cmd, p)
            name = WMI.get(cmd, str(cmd))
            if cmd == 21:
                for i in range(0, len(p) - 7, 8):
                    r, v = struct.unpack(">II", p[i:i + 8])
                    print(("W %08x %08x" if regs_only else ts + " W %08x : %08x") % (r, v))
            elif cmd == 32:
                for i in range(0, len(p) - 11, 12):
                    r, s, c = struct.unpack(">III", p[i:i + 12])
                    print(("M %08x %08x %08x" if regs_only else ts + " M %08x set %08x clr %08x") % (r, s, c))
            elif cmd == 20:
                pass  # printed with the answer
            elif not regs_only:
                print(ts, "WMI", name, p.hex())
            elif cmd not in (20, 21, 32):
                print("C %s %s" % (name, p.hex()))
            continue
        if ep == "0x83" and urb == "'C'" and len(raw) >= 12:
            epid, flags, plen = raw[0], raw[1], struct.unpack(">H", raw[2:4])[0]
            body = raw[8:8 + plen]
            if epid == 0:
                if not regs_only:
                    print(ts, "HTC ctrl in", body.hex())
                continue
            if len(body) < 4:
                continue
            cmd, seq = struct.unpack(">HH", body[:4])
            if cmd & 0x1000:
                if not regs_only:
                    print(ts, "EVENT 0x%04x %s" % (cmd, body[4:].hex()))
                continue
            sent = pending.pop(seq, None)
            if sent and sent[0] == 20:
                addrs = [struct.unpack(">I", sent[1][i:i + 4])[0] for i in range(0, len(sent[1]), 4)]
                vals = [struct.unpack(">I", body[4 + 4 * i:8 + 4 * i])[0] for i in range(len(addrs)) if 8 + 4 * i <= len(body)]
                for a, v in zip(addrs, vals):
                    print(("R %08x = %08x" if regs_only else ts + " R %08x = %08x") % (a, v))
            elif sent and sent[0] not in (21, 32) and not regs_only:
                print(ts, "  answer", WMI.get(sent[0], sent[0]), body[4:].hex())


if __name__ == "__main__":
    main()
