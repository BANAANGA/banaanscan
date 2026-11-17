#!/usr/bin/env bash
# Records the Linux ath9k_htc driver's USB traffic with an AR9271 adapter while it loads its firmware, starts up and
# listens in monitor mode on 2.4 GHz channels 1, 6, 11 and 13. The trace is the reference for sniffowireless-mobile's
# own userspace driver: which registers Linux writes, in what order.
#
# What it changes, all undone on exit (also on Ctrl+C): it loads the usbmon module, stops NetworkManager managing the
# adapter, restarts the adapter's driver and puts it in monitor mode. It only listens: nothing is transmitted.
# Your other Wi-Fi adapter is not touched.
#
# Usage: tools/ath9k/capture-reference.sh            (asks for your sudo password)
#        SECONDS_PER_CHANNEL=20 tools/ath9k/capture-reference.sh
set -euo pipefail

SECONDS_PER_CHANNEL=${SECONDS_PER_CHANNEL:-12}
OUT=${OUT:-$PWD/ath9k-trace}
CHANNELS=("2412 ch1" "2437 ch6" "2462 ch11" "2472 ch13")

if [ "$(id -u)" != 0 ]; then
  exec sudo SECONDS_PER_CHANNEL="$SECONDS_PER_CHANNEL" OUT="$OUT" "$0" "$@"
fi

DEV=""
for d in /sys/bus/usb/devices/*; do
  [ "$(cat "$d/idVendor" 2>/dev/null):$(cat "$d/idProduct" 2>/dev/null)" = "0cf3:9271" ] && DEV=$(basename "$d") && break
done
[ -n "$DEV" ] || { echo "No AR9271 (0cf3:9271) found. Is it plugged in?" >&2; exit 1; }
SYS=/sys/bus/usb/devices/$DEV

# Empty while the driver is (re)starting; must not trip `set -e`.
iface() { ls "$SYS/$DEV:1.0/net" 2>/dev/null | head -1 || true; }
step() { echo "$(date +%s.%N) $*" | tee -a "$OUT/steps.txt"; }

mkdir -p "$OUT"
: > "$OUT/steps.txt"
# Capture into a root-owned scratch folder; tshark's capture helper may run unprivileged, so it gets write access.
WORK=$(mktemp -d /tmp/ath9k-trace.XXXXXX)
chmod 0733 "$WORK"
IFACE=$(iface)
[ -n "$IFACE" ] || { echo "The adapter has no network interface; is the ath9k_htc driver loaded?" >&2; exit 1; }
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

# The bus number can change when the adapter re-enumerates after the driver restart (the firmware reboots it), but
# the bus stays the same: capture the whole bus and filter by address when analysing.
BUS=$(cat "$SYS/busnum")
tshark -q -i "usbmon$BUS" -s 0 -w "$WORK/usb.pcapng" 2>"$WORK/tshark-usb.log" &
PIDS+=($!)
sleep 2
[ -s "$WORK/usb.pcapng" ] || { cat "$WORK/tshark-usb.log" >&2; echo "The USB capture didn't start" >&2; exit 1; }

step "driver restart (address before: $(cat "$SYS/devnum"))"
echo "$DEV:1.0" > /sys/bus/usb/drivers/ath9k_htc/unbind
# Unbinding reboots the chip into its boot ROM, which re-enumerates; wait for that before binding again.
sleep 3
for _ in $(seq 50); do [ -e "$SYS/$DEV:1.0" ] && break; sleep 0.2; done
[ -e "$SYS/$DEV:1.0/driver" ] || echo "$DEV:1.0" > /sys/bus/usb/drivers/ath9k_htc/bind 2>/dev/null || true
for _ in $(seq 100); do IFACE=$(iface); [ -n "$IFACE" ] && break; sleep 0.2; done
[ -n "$IFACE" ] || { echo "The interface didn't come back after the driver restart" >&2; exit 1; }
for _ in $(seq 50); do iw dev "$IFACE" info >/dev/null 2>&1 && break; sleep 0.2; done
sleep 3
command -v nmcli >/dev/null && nmcli dev set "$IFACE" managed no || true
{
  echo "adapter $DEV bus $BUS address $(cat "$SYS/devnum") interface $IFACE"
  echo "kernel $(uname -r), $(ethtool -i "$IFACE" 2>/dev/null | grep firmware-version)"
  echo "regulatory: $(iw reg get | grep -m1 country)"
} | tee "$OUT/info.txt"

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
