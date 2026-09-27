#!/usr/bin/env bash
# Last N log lines of our process (or Folio tags and crashes mentioning our package if not running).
source "$(dirname "$0")/_common.sh"
init_device
lines="${1:-200}"
is_int "$lines" || die "lines must be numeric" 2
pid="$(dshell pidof "$PKG" || true)"
out="$STATE_DIR/logs/logcat-last.txt"
if [ -n "$pid" ]; then
  adbs logcat -d -v brief --pid="$pid" | tr -d '\r' | tail -n "$lines" > "$out"
else
  adbs logcat -d -v brief | tr -d '\r' | grep -E "Folio|$PKG" | tail -n "$lines" > "$out" || true
fi
cat "$out"
