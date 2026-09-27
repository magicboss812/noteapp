#!/usr/bin/env bash
# Connect to the tablet: .device/target (ip:port) -> mDNS discovery -> single USB device.
source "$(dirname "$0")/_common.sh"
target=""
if [ -f "$STATE_DIR/target" ]; then target="$(tr -d '\r\n' < "$STATE_DIR/target")"; fi
if [ -z "$target" ]; then
  target="$("$ADB_BIN" mdns services 2>/dev/null | tr -d '\r' | awk '/_adb-tls-connect/ {print $NF}' | head -n1 || true)"
fi
rm -f "$STATE_DIR/serial"
if [ -n "$target" ]; then
  "$ADB_BIN" connect "$target" 2>&1 | tr -d '\r' | tail -n1
  echo "$target" > "$STATE_DIR/serial"
fi
init_device
echo "Connected: $SERIAL ($(dshell getprop ro.product.model))"
