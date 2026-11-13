#!/usr/bin/env bash
# Records the Linux mt7921u driver's USB traffic while it starts up and listens in monitor mode on a 2.4, a 5 and a
# 6 GHz channel. The trace is the reference for BanaanScan's own userspace driver: what the chip expects, in what order.
#
# What it changes, all undone on exit (also on Ctrl+C): it loads the usbmon module, stops NetworkManager managing the
# adapter, restarts the adapter's driver and puts it in monitor mode. It only listens: nothing is transmitted.
# Your other Wi-Fi adapter (wlan0) is not touched.
#
# Usage: tools/mt7921u/capture-reference.sh            (asks for your sudo password)
#        DEV=2-3.1 SECONDS_PER_CHANNEL=20 tools/mt7921u/capture-reference.sh
set -euo pipefail

DEV=${DEV:-2-3.1}                          # the adapter's place on the USB bus, from /sys/bus/usb/devices
SECONDS_PER_CHANNEL=${SECONDS_PER_CHANNEL:-15}
OUT=${OUT:-$PWD/mt7921u-trace}
# 2.4 GHz channel 6, 5 GHz channel 36, 6 GHz channel 37 (a preferred scanning channel)
CHANNELS=("2437 2.4GHz-ch6" "5180 5GHz-ch36" "6135 6GHz-ch37")

if [ "$(id -u)" != 0 ]; then
  exec sudo DEV="$DEV" SECONDS_PER_CHANNEL="$SECONDS_PER_CHANNEL" OUT="$OUT" "$0" "$@"
fi

SYS=/sys/bus/usb/devices/$DEV
[ "$(cat "$SYS/idVendor" 2>/dev/null):$(cat "$SYS/idProduct" 2>/dev/null)" = "0e8d:7961" ] || {
  echo "No MT7921U (0e8d:7961) at $SYS. Set DEV to its place on the bus (see: lsusb -t)." >&2; exit 1; }
BUS=$(cat "$SYS/busnum")
ADDR=$(cat "$SYS/devnum")

# Empty while the driver is (re)starting; must not trip `set -e`.
iface() { ls "$SYS/$DEV:1.0/net" 2>/dev/null | head -1 || true; }
step() { echo "$(date +%s.%N) $*" | tee -a "$OUT/steps.txt"; }

mkdir -p "$OUT"
: > "$OUT/steps.txt"
# Capture into a root-owned scratch folder: tshark refused to write straight into the home folder.
# tshark's capture helper may run as an unprivileged user, so it gets write-only access to this folder.
WORK=$(mktemp -d /tmp/mt7921u-trace.XXXXXX)
chmod 0733 "$WORK"
IFACE=$(iface)
[ -n "$IFACE" ] || { echo "The adapter has no network interface; is the mt7921u driver loaded?" >&2; exit 1; }
PIDS=()

cleanup() {
  set +e
  step "cleanup"
  for p in "${PIDS[@]}"; do kill "$p" 2>/dev/null; wait "$p" 2>/dev/null; done
  IFACE=$(iface)
  sleep 1
  if [ -n "$IFACE" ]; then
    ip link set "$IFACE" down
    iw dev "$IFACE" set type managed
    command -v nmcli >/dev/null && nmcli dev set "$IFACE" managed yes
  fi
  mv -f "$WORK"/* "$OUT"/ 2>/dev/null
  rmdir "$WORK" 2>/dev/null
  [ -n "${SUDO_UID:-}" ] && chown -R "$SUDO_UID:$SUDO_GID" "$OUT"
  echo "Done. Files are in $OUT"
}
trap cleanup EXIT

modprobe usbmon
command -v nmcli >/dev/null && nmcli dev set "$IFACE" managed no || true
{
  echo "adapter $DEV bus $BUS address $ADDR interface $IFACE"
  echo "kernel $(uname -r), $(ethtool -i "$IFACE" 2>/dev/null | grep firmware-version)"
  echo "regulatory: $(iw reg get | grep -m1 country)"
} | tee "$OUT/info.txt"

# usbmon sees the whole bus; the trace is filtered to this adapter (usb.device_address == ADDR) when analysed.
tshark -q -i "usbmon$BUS" -s 0 -w "$WORK/usb.pcapng" 2>"$WORK/tshark-usb.log" &
PIDS+=($!)
sleep 2
[ -s "$WORK/usb.pcapng" ] || { cat "$WORK/tshark-usb.log" >&2; echo "The USB capture didn't start" >&2; exit 1; }

step "driver restart"
echo "$DEV:1.0" > /sys/bus/usb/drivers/mt7921u/unbind
sleep 1
echo "$DEV:1.0" > /sys/bus/usb/drivers/mt7921u/bind
for _ in $(seq 50); do IFACE=$(iface); [ -n "$IFACE" ] && break; sleep 0.2; done
[ -n "$IFACE" ] || { echo "The interface didn't come back after the driver restart" >&2; exit 1; }
# Let the driver finish starting before touching the interface.
for _ in $(seq 50); do iw dev "$IFACE" info >/dev/null 2>&1 && break; sleep 0.2; done
sleep 3
command -v nmcli >/dev/null && nmcli dev set "$IFACE" managed no || true

step "monitor mode on $IFACE"
ip link set "$IFACE" down
iw dev "$IFACE" set type monitor
ip link set "$IFACE" up

# What the kernel driver hears: the ground truth to compare our driver's output with later.
tshark -q -i "$IFACE" -s 0 -w "$WORK/air.pcapng" 2>"$WORK/tshark-air.log" &
PIDS+=($!)
sleep 1

for c in "${CHANNELS[@]}"; do
  set -- $c
  step "channel $2 ($1 MHz)"
  iw dev "$IFACE" set freq "$1" || step "could not tune to $1 MHz"
  sleep "$SECONDS_PER_CHANNEL"
done
step "finished"
