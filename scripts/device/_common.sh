#!/usr/bin/env bash
# Shared configuration for device wrappers. The ONLY code in this repository that invokes adb.
# Protected by .claude/hooks/guard-protected-files.sh. Works in Git Bash (Windows) and bash (Linux).
set -euo pipefail

PKG="dev.folio.notes.debug"
ALLOWED_PKGS="dev.folio.notes.debug dev.folio.notes.benchmark"
MAIN_ACTIVITY="dev.folio.app.MainActivity"
DEVICE_DIR="/sdcard/Documents/Folio-Debug"

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
STATE_DIR="$ROOT/.device"
mkdir -p "$STATE_DIR/screens" "$STATE_DIR/logs" "$STATE_DIR/pulled"

die() { printf '%s\n' "$*" >&2; exit "${2:-1}"; }

find_adb() {
  local sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  if [ -n "$sdk" ]; then
    if [ -x "$sdk/platform-tools/adb" ]; then echo "$sdk/platform-tools/adb"; return; fi
    if [ -f "$sdk/platform-tools/adb.exe" ]; then echo "$sdk/platform-tools/adb.exe"; return; fi
  fi
  command -v adb 2>/dev/null && return
  command -v adb.exe 2>/dev/null && return
  echo ""
}
ADB_BIN="$(find_adb)"
[ -n "$ADB_BIN" ] || die "adb not found. Set ANDROID_HOME to the Android SDK (platform-tools installed)." 10

SERIAL=""
init_device() {
  if [ -f "$STATE_DIR/serial" ]; then
    SERIAL="$(tr -d '\r\n' < "$STATE_DIR/serial")"
  else
    local list n
    list="$("$ADB_BIN" devices | tr -d '\r' | awk 'NR>1 && $2=="device" {print $1}')"
    n="$(printf '%s\n' "$list" | grep -c . || true)"
    [ "$n" -eq 1 ] || die "Expected exactly one device, found $n. Run scripts/device/connect.sh." 11
    SERIAL="$list"
  fi
  "$ADB_BIN" -s "$SERIAL" get-state >/dev/null 2>&1 \
    || die "Device $SERIAL not reachable. Run connect.sh; if that fails the user must enable Wireless debugging (README)." 11
}

adbs() { "$ADB_BIN" -s "$SERIAL" "$@"; }
dshell() { adbs shell "$@" | tr -d '\r'; }

is_int() { case "${1:-}" in ''|*[!0-9]*) return 1;; *) return 0;; esac; }

require_foreground() {
  local top
  top="$(dshell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' || true)"
  case "$top" in
    *"$PKG/"*) return 0 ;;
  esac
  die "REFUSED: $PKG is not in the foreground (top: ${top:-unknown}). Run launch.sh; if the tablet is locked, report BLOCKED." 12
}
