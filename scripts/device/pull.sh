#!/usr/bin/env bash
# Pull a subdirectory of Folio-Debug (e.g. exports, probe, logs) into .device/pulled/.
source "$(dirname "$0")/_common.sh"
init_device
sub="${1:-exports}"
case "$sub" in ''|*..*|/*|*[!A-Za-z0-9._/-]*) die "invalid subdirectory" 2 ;; esac
mkdir -p "$STATE_DIR/pulled"
adbs pull "$DEVICE_DIR/$sub" "$STATE_DIR/pulled/" 2>&1 | tr -d '\r' | tail -n1
