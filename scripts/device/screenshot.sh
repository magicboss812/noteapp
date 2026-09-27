#!/usr/bin/env bash
# Screenshot of our app only (refuses if another app is in the foreground).
source "$(dirname "$0")/_common.sh"
init_device
name="${1:-shot}"
case "$name" in *[!A-Za-z0-9._-]*) die "name may contain only A-Z a-z 0-9 . _ -" 2 ;; esac
require_foreground
out="$STATE_DIR/screens/$name.png"
adbs exec-out screencap -p > "$out"
echo "$out ($(wc -c < "$out" | tr -d ' ') bytes)"
