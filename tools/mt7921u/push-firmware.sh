#!/usr/bin/env bash
# Copies the MT7921U firmware from this computer's linux-firmware onto the phone, for BanaanScan's USB adapter mode.
# The files aren't bundled with the app until their licence has been checked. Works with debug builds (run-as).
# Usage: tools/mt7921u/push-firmware.sh [adb serial]
set -euo pipefail
ADB=${ADB:-adb}
command -v "$ADB" >/dev/null || ADB="$HOME/Android/Sdk/platform-tools/adb"
SERIAL=${1:-}
a() { if [ -n "$SERIAL" ]; then "$ADB" -s "$SERIAL" "$@"; else "$ADB" "$@"; fi; }
PKG=com.example.banaanscan
SRC=${FIRMWARE_DIR:-/lib/firmware/mediatek}
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT
for f in WIFI_MT7961_patch_mcu_1_2_hdr.bin WIFI_RAM_CODE_MT7961_1.bin; do
  if [ -f "$SRC/$f" ]; then cp "$SRC/$f" "$TMP/$f"
  elif [ -f "$SRC/$f.zst" ]; then zstd -q -d "$SRC/$f.zst" -o "$TMP/$f"
  elif [ -f "$SRC/$f.xz" ]; then xz -dc "$SRC/$f.xz" > "$TMP/$f"
  else echo "Not found: $SRC/$f(.zst/.xz). Install your distribution's linux-firmware package." >&2; exit 1; fi
  a push -q "$TMP/$f" "/data/local/tmp/$f"
  a shell "run-as $PKG mkdir -p files/mt7921u && run-as $PKG cp /data/local/tmp/$f files/mt7921u/$f && rm /data/local/tmp/$f"
  echo "Copied $f ($(stat -c %s "$TMP/$f") bytes)"
done
echo "Done. In BanaanScan: Wi-Fi tab → USB adapter."
