#!/usr/bin/env bash
# Toolchain check for Windows (Git Bash) and Linux. Exit 0 = PASS.
set -u
status=0
ok()   { echo "OK    $*"; }
warn() { echo "WARN  $*"; }
bad()  { echo "FAIL  $*"; status=1; }

echo "OS: $(uname -s) $(uname -r)"
if command -v java >/dev/null 2>&1; then
  v="$(java -version 2>&1 | head -n1)"
  major="$(printf '%s' "$v" | sed -nE 's/.*version "([0-9]+).*/\1/p')"
  if [ "${major:-0}" -ge 17 ]; then ok "java $major ($v)"; else bad "JDK 17+ required (found: $v)"; fi
else
  bad "java not on PATH (install JDK 21)"
fi

sdk="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
if [ -n "$sdk" ] && [ -d "$sdk" ]; then
  ok "ANDROID_HOME=$sdk"
  if ls "$sdk"/platform-tools/adb* >/dev/null 2>&1; then ok "platform-tools"; else bad "platform-tools missing"; fi
  if ls -d "$sdk"/platforms/android-* >/dev/null 2>&1; then ok "platforms: $(ls "$sdk"/platforms | tr '\n' ' ')"; else warn "no SDK platform installed"; fi
  if ls -d "$sdk"/build-tools/* >/dev/null 2>&1; then ok "build-tools: $(ls "$sdk"/build-tools | tr '\n' ' ')"; else warn "no build-tools installed"; fi
  sm="$(ls "$sdk"/cmdline-tools/latest/bin/sdkmanager* 2>/dev/null | head -n1 || true)"
  if [ -n "$sm" ]; then ok "sdkmanager: $sm"; else warn "cmdline-tools/latest missing (needed for scripted SDK installs)"; fi
  if [ -d "$sdk/licenses" ] && ls "$sdk"/licenses/android-sdk-license >/dev/null 2>&1; then ok "SDK license accepted"; else bad "SDK licenses not accepted: the USER runs 'sdkmanager --licenses'"; fi
else
  bad "ANDROID_HOME (or ANDROID_SDK_ROOT) not set to an existing directory"
fi

for t in git jq bash; do
  if command -v "$t" >/dev/null 2>&1; then ok "$t"; else bad "$t missing"; fi
done
if command -v claude >/dev/null 2>&1; then ok "claude CLI"; else warn "claude CLI not on PATH (needed only for run-loop.sh)"; fi

if [ "$status" -eq 0 ]; then echo "DOCTOR: PASS"; else echo "DOCTOR: FAIL"; fi
exit "$status"
