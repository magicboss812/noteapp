#!/usr/bin/env bash
# Deletes and recreates the DEBUG library folder only.
source "$(dirname "$0")/_common.sh"
init_device
case "$DEVICE_DIR" in /sdcard/Documents/Folio-Debug) ;; *) die "unexpected DEVICE_DIR" 13 ;; esac
dshell rm -rf "$DEVICE_DIR"
dshell mkdir -p "$DEVICE_DIR"
echo "wiped $DEVICE_DIR"
