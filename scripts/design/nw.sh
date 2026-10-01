#!/usr/bin/env bash
# Notewise reference capture: drive Notewise on the tablet by hand-like input and save screenshots.
# Used by the session that scripts/auto/notewise-capture.sh starts (spec: docs/design/notewise/CAPTURE.md).
# Acts only while Notewise is in the foreground; never launches, clears or touches any other package.
# Usage: nw.sh info | launch | ui | tap X Y | longpress X Y | swipe X1 Y1 X2 Y2 [MS] | key NAME | text "words"
#        nw.sh shot NAME | crop NAME X Y W H [SCALE] | px NAME X Y [X Y ...]
# Coordinates are device pixels of the current orientation (the `ui` list and `shot` raw images use them).
source "$(dirname "$0")/../device/_common.sh"
cd "$ROOT"
init_device

REFS="$STATE_DIR/refs"                 # raw PNGs, previews, crops, UI dumps (local only)
SHOTS="docs/design/notewise/shots"     # committed JPEG copies
mkdir -p "$REFS" "$SHOTS"
JAVA="java"; [ -n "${JAVA_HOME:-}" ] && JAVA="$JAVA_HOME/bin/java"
tool() { "$JAVA" scripts/design/DesignTool.java "$@" 2>&1 | grep -v '^Picked up JAVA_TOOL_OPTIONS'; }
settle() { sleep "${NW_SETTLE:-0.7}"; }   # let animations finish before the next look
nums() { for v in "$@"; do is_int "$v" || die "numeric arguments required" 2; done; }
check_name() { case "$1" in ''|*[!A-Za-z0-9._-]*) die "name may contain only A-Z a-z 0-9 . _ -" 2 ;; esac; }

nw_pkg() {
  if [ -n "${NOTEWISE_PKG:-}" ]; then echo "$NOTEWISE_PKG"; return; fi
  if [ -s "$STATE_DIR/notewise.pkg" ]; then cat "$STATE_DIR/notewise.pkg"; return; fi
  local found n
  found="$(dshell pm list packages | sed 's/^package://' | grep -i notewise || true)"
  n="$(printf '%s\n' "$found" | grep -c . || true)"
  [ "$n" -eq 1 ] || die "Found $n Notewise packages (${found:-none}). Set NOTEWISE_PKG=<package>." 13
  printf '%s\n' "$found" > "$STATE_DIR/notewise.pkg"
  echo "$found"
}
NW_PKG="$(nw_pkg)"
case "$NW_PKG" in dev.folio.*) die "REFUSED: $NW_PKG is a Folio package" 2 ;; esac

require_notewise() {
  local top
  top="$(dshell dumpsys activity activities 2>/dev/null | grep -m1 -E 'topResumedActivity|mResumedActivity' || true)"
  case "$top" in *"$NW_PKG/"*) return 0 ;; esac
  die "REFUSED: Notewise ($NW_PKG) is not in the foreground (top: ${top:-unknown}). Run: nw.sh launch" 12
}

op="${1:-}"; shift || true
case "$op" in
  info)
    echo "package: $NW_PKG"
    dshell wm size | tail -n1
    dshell wm density | tail -n1
    echo "rotation: $(dshell dumpsys input | grep -m1 -oE 'SurfaceOrientation: [0-9]' || echo unknown)"
    echo "dp = px / (density / 160)" ;;
  launch)
    dshell monkey -p "$NW_PKG" -c android.intent.category.LAUNCHER 1 >/dev/null
    sleep 2; require_notewise; echo "Notewise in foreground" ;;
  ui)
    require_notewise
    dshell uiautomator dump /data/local/tmp/nw-ui.xml >/dev/null
    adbs exec-out cat /data/local/tmp/nw-ui.xml > "$REFS/ui.xml"
    tool ui "$REFS/ui.xml" ;;
  tap)       require_notewise; nums "$1" "$2"; dshell input tap "$1" "$2"; settle ;;
  longpress) require_notewise; nums "$1" "$2"; dshell input swipe "$1" "$2" "$1" "$2" 800; settle ;;
  swipe)     require_notewise; nums "$1" "$2" "$3" "$4" "${5:-300}"; dshell input swipe "$1" "$2" "$3" "$4" "${5:-300}"; settle ;;
  key)
    require_notewise
    case "${1:-}" in
      BACK|ESCAPE|ENTER|TAB|DEL|PAGE_UP|PAGE_DOWN|DPAD_UP|DPAD_DOWN|DPAD_LEFT|DPAD_RIGHT|MOVE_END)
        dshell input keyevent "KEYCODE_$1"; settle ;;
      *) die "key must be one of BACK ESCAPE ENTER TAB DEL PAGE_UP PAGE_DOWN DPAD_* MOVE_END" 2 ;;
    esac ;;
  text)
    require_notewise
    t="${1:-}"
    case "$t" in *[\;\&\|\`\$\\\"\']*) die "REFUSED: shell metacharacters in text" 2 ;; esac
    dshell input text "$(printf '%s' "$t" | sed 's/ /%s/g')"; settle ;;
  shot)
    require_notewise
    name="${1:-}"; check_name "$name"
    adbs exec-out screencap -p > "$REFS/$name.png"
    tool jpeg "$REFS/$name.png" "$SHOTS/$name.jpg" 1600 85
    tool jpeg "$REFS/$name.png" "$REFS/$name.preview.jpg" "${NW_PREVIEW_WIDTH:-1280}" 80
    echo "view: $REFS/$name.preview.jpg (device px = preview px x source width / preview width)" ;;
  crop)
    name="${1:-}"; check_name "$name"; nums "$2" "$3" "$4" "$5"
    [ -f "$REFS/$name.png" ] || die "no raw shot $name (run: nw.sh shot $name)" 2
    tool crop "$REFS/$name.png" "$REFS/$name.crop-$2-$3.png" "$2" "$3" "$4" "$5" "${6:-1}" ;;
  px)
    name="${1:-}"; check_name "$name"; shift
    [ -f "$REFS/$name.png" ] || die "no raw shot $name (run: nw.sh shot $name)" 2
    nums "$@"; tool px "$REFS/$name.png" "$@" ;;
  *) die "usage: nw.sh info|launch|ui|tap|longpress|swipe|key|text|shot|crop|px ... (see header)" 2 ;;
esac
