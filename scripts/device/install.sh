#!/usr/bin/env bash
# Install a debug/benchmark APK from app/build/outputs only, after verifying its package id.
source "$(dirname "$0")/_common.sh"
init_device
apk="${1:-}"
if [ -z "$apk" ]; then
  apk="$(ls -t "$ROOT"/app/build/outputs/apk/debug/*.apk 2>/dev/null | head -n1 || true)"
fi
[ -n "$apk" ] && [ -f "$apk" ] || die "APK not found. Run ./gradlew :app:assembleDebug first." 14
case "$(cd "$(dirname "$apk")" && pwd)" in
  "$ROOT"/app/build/outputs/apk/debug|"$ROOT"/app/build/outputs/apk/benchmark) ;;
  *) die "REFUSED: only APKs from app/build/outputs/apk/{debug,benchmark}." 13 ;;
esac
sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
aapt="$(ls "$sdk"/build-tools/*/aapt2* 2>/dev/null | sort | tail -n1 || true)"
if [ -n "$aapt" ]; then
  id="$("$aapt" dump badging "$apk" 2>/dev/null | tr -d '\r' | sed -nE "s/^package: name='([^']+)'.*/\1/p")"
  case " $ALLOWED_PKGS " in
    *" $id "*) ;;
    *) die "REFUSED: APK package '$id' is not one of: $ALLOWED_PKGS" 13 ;;
  esac
fi
adbs install -r -t "$apk" 2>&1 | tr -d '\r' | tail -n1
