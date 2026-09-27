#!/usr/bin/env bash
# Grants All-files access to the debug package only and ensures the debug library folder exists.
source "$(dirname "$0")/_common.sh"
init_device
dshell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE allow
dshell mkdir -p "$DEVICE_DIR"
echo "MANAGE_EXTERNAL_STORAGE: $(dshell appops get --uid "$PKG" MANAGE_EXTERNAL_STORAGE | head -n1)"
