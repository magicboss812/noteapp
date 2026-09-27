#!/usr/bin/env bash
# Send a debug command to the running debug app (handled by app/src/debug DebugCommands) and print its reply.
# Reply format in logcat tag FolioDebug: "nonce=<n> ok=<true|false> <json>".
source "$(dirname "$0")/_common.sh"
init_device
cmd="${1:-}"; arg="${2:-}"
case "$cmd" in ''|*[!a-z0-9-]*) die "command must match [a-z0-9-]+" 2 ;; esac
case "$arg" in *[!A-Za-z0-9._,:/=+-]*) die "argument may contain only A-Za-z0-9._,:/=+-" 2 ;; esac
nonce="$(date +%s)$RANDOM"
dshell am start -n "$PKG/$MAIN_ACTIVITY" --activity-single-top \
  --es folio.debug.cmd "$cmd" --es folio.debug.arg "${arg:-_}" --es folio.debug.nonce "$nonce" >/dev/null
for _ in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15 16 17 18 19 20; do
  sleep 0.5
  reply="$(adbs logcat -d -v raw -s FolioDebug:I | tr -d '\r' | grep "nonce=$nonce" | tail -n1 || true)"
  [ -n "$reply" ] && { echo "$reply"; exit 0; }
done
die "no reply for nonce=$nonce within 10 s (is the command implemented?)" 15
