#!/usr/bin/env bash
# Input injection into our foreground app only. Usage:
#   input.sh tap X Y | swipe X1 Y1 X2 Y2 [MS] | stylus-tap X Y | stylus-swipe X1 Y1 X2 Y2 [MS]
#   input.sh text "words" | key KEYCODE_X | combo MOD KEY   (MOD: CTRL_LEFT|SHIFT_LEFT|ALT_LEFT, KEY: A-Z 0-9 or named)
source "$(dirname "$0")/_common.sh"
init_device
require_foreground
op="${1:-}"; shift || true
nums() { for v in "$@"; do is_int "$v" || die "numeric arguments required" 2; done; }
case "$op" in
  tap)          nums "$1" "$2"; dshell input tap "$1" "$2" ;;
  swipe)        nums "$1" "$2" "$3" "$4" "${5:-300}"; dshell input swipe "$1" "$2" "$3" "$4" "${5:-300}" ;;
  stylus-tap)   nums "$1" "$2"; dshell input stylus tap "$1" "$2" ;;
  stylus-swipe) nums "$1" "$2" "$3" "$4" "${5:-400}"; dshell input stylus swipe "$1" "$2" "$3" "$4" "${5:-400}" ;;
  text)
    t="${1:-}"
    case "$t" in *[\;\&\|\`\$\\\"\']*) die "REFUSED: shell metacharacters in text" 2 ;; esac
    dshell input text "$(printf '%s' "$t" | sed 's/ /%s/g')" ;;
  key)
    k="${1:-}"
    case "$k" in
      KEYCODE_HOME|KEYCODE_APP_SWITCH|KEYCODE_POWER|KEYCODE_SLEEP|KEYCODE_SETTINGS|KEYCODE_ASSIST|KEYCODE_VOICE_ASSIST|KEYCODE_CALL|KEYCODE_CAMERA)
        die "REFUSED: $k leaves the app or affects the system" 2 ;;
      KEYCODE_[A-Z0-9_]*) dshell input keyevent "$k" ;;
      *) die "key must look like KEYCODE_X" 2 ;;
    esac ;;
  combo)
    m="${1:-}"; k="${2:-}"
    case "$m" in CTRL_LEFT|SHIFT_LEFT|ALT_LEFT|META_LEFT) ;; *) die "modifier not allowed" 2 ;; esac
    case "$k" in [A-Z0-9]|ENTER|TAB|DEL|SLASH|EQUALS|MINUS|BACKSLASH|PAGE_UP|PAGE_DOWN|DPAD_UP|DPAD_DOWN|DPAD_LEFT|DPAD_RIGHT) ;; *) die "key not allowed in combo" 2 ;; esac
    dshell input keycombination "KEYCODE_$m" "KEYCODE_$k" ;;
  *) die "usage: input.sh tap|swipe|stylus-tap|stylus-swipe|text|key|combo ..." 2 ;;
esac
