#!/usr/bin/env bash
# Push a fixture from testdata/device/ to Folio-Debug/fixtures/.
source "$(dirname "$0")/_common.sh"
init_device
src="${1:-}"
[ -f "$src" ] || die "file not found: $src" 2
abs="$(cd "$(dirname "$src")" && pwd)/$(basename "$src")"
case "$abs" in "$ROOT"/testdata/device/*) ;; *) die "REFUSED: fixtures must come from testdata/device/" 13 ;; esac
case "$(basename "$src")" in *[!A-Za-z0-9._-]*) die "fixture names: A-Z a-z 0-9 . _ - only" 2 ;; esac
dshell mkdir -p "$DEVICE_DIR/fixtures"
adbs push "$abs" "$DEVICE_DIR/fixtures/" 2>&1 | tr -d '\r' | tail -n1
