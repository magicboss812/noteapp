#!/usr/bin/env bash
# Launch the debug app. Optional: --doc <file relative to Folio-Debug> --route <route>. No spaces in values.
source "$(dirname "$0")/_common.sh"
init_device
extras=""
while [ $# -gt 0 ]; do
  case "$1" in
    --doc)   [ -n "${2:-}" ] || die "--doc needs a value" 2; extras="$extras --es folio.debug.doc $2"; shift 2 ;;
    --route) [ -n "${2:-}" ] || die "--route needs a value" 2; extras="$extras --es folio.debug.route $2"; shift 2 ;;
    *) die "unknown argument: $1" 2 ;;
  esac
done
case "$extras" in *[\;\&\|\`\$]*) die "REFUSED: shell metacharacters in arguments." 2 ;; esac
dshell input keyevent KEYCODE_WAKEUP >/dev/null 2>&1 || true
# shellcheck disable=SC2086
dshell am start -W -n "$PKG/$MAIN_ACTIVITY" $extras | grep -E 'Status|TotalTime|WaitTime|Error' || true
