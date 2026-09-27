#!/usr/bin/env bash
# Run connected tests of one module on the selected tablet. Usage: instrumented.sh :module [connectedXxxAndroidTest] [gradle args]
source "$(dirname "$0")/_common.sh"
init_device
mod="${1:-:app}"
task="${2:-connectedDebugAndroidTest}"
case "$mod" in :[a-z]*) ;; *) die "module path like :feature:editor" 2 ;; esac
case "$task" in connected*AndroidTest) ;; *) die "task must be connected*AndroidTest" 2 ;; esac
shift $(( $# >= 2 ? 2 : $# ))
ANDROID_SERIAL="$SERIAL" "$ROOT/gradlew" -p "$ROOT" "$mod:$task" "$@"
