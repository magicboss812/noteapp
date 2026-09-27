---
name: device-test
description: Install, launch, drive, observe, and measure the Folio debug app on the Xiaomi Pad 7 using only scripts/device wrappers. Use whenever a task needs the physical tablet.
---
# device-test

## Safety model
- Wrappers hardcode package `dev.folio.notes.debug` (and `.benchmark`) and the device folder `/sdcard/Documents/Folio-Debug`. Raw adb is blocked by hook and by permissions.
- `input.sh` and `screenshot.sh` refuse unless our app is in the foreground. If refused: run `launch.sh` once. Still refused -> the tablet is locked or another app is on top: report BLOCKED with the exact user action. Never work around it.
- Nothing outside our package is changed. Read-only diagnostics (`info.sh`, `diag.sh`) are allowed.

## Standard flow
1. `bash scripts/device/connect.sh` (explicit `.device/target` ip:port, else mDNS discovery, else single USB device). Failure -> BLOCKED: "Enable Wireless debugging on the tablet, same Wi-Fi; re-pair if needed (README)". Install failures (`INSTALL_FAILED_USER_RESTRICTED`) -> BLOCKED: Developer options "Install via USB" + "USB debugging (Security settings)" (docs/notes/device.md Quirks).
2. `./gradlew :app:assembleDebug` then `bash scripts/device/install.sh`.
3. After first install or clear-data: `bash scripts/device/grant-storage.sh`.
4. `bash scripts/device/launch.sh [--doc <file in Folio-Debug, no spaces>] [--route <route>]`.
5. Drive the app. Prefer deterministic debug commands over coordinates:
   `bash scripts/device/debugcmd.sh <cmd> [arg]` (commands listed in `app/src/debug/.../DebugCommands.kt`; reply line `nonce=<n> ok=<bool> <json>` from logcat tag FolioDebug).
   Gestures: `input.sh stylus-swipe x1 y1 x2 y2 [ms]`, `input.sh stylus-tap x y`, `input.sh tap x y`, `input.sh swipe x1 y1 x2 y2 [ms]`, `input.sh text "..."`, `input.sh key KEYCODE_X`, `input.sh combo CTRL_LEFT Z`.
   Run `info.sh` first for display size and rotation before using coordinates.
6. Observe: `screenshot.sh <name>` then Read the PNG; `logcat.sh [lines]`; `gfxinfo.sh reset` before and `gfxinfo.sh` after an interaction; `debugcmd.sh perf-dump` for PerfMonitor percentiles; `diag.sh layers|refresh|meminfo`.
7. Instrumented tests: `bash scripts/device/instrumented.sh :feature:editor` (judge by `<module>/build/outputs/androidTest-results/connected/**/TEST-*.xml`, not the Gradle status: a failed install still ends BUILD SUCCESSFUL) (default task connectedDebugAndroidTest; macrobenchmarks later: `instrumented.sh :benchmark connectedBenchmarkAndroidTest`).
8. Fixtures: files under `testdata/device/` via `push-fixture.sh <file>` (lands in Folio-Debug/fixtures/). Results: `pull.sh <subdir>` (exports, probe, logs) -> `.device/pulled/`.
9. Reset state: `clear-data.sh` (app data) and `wipe-debug-library.sh` (Folio-Debug contents).

## Report format (device-tester output, <= 40 lines)
```
RESULT: PASS | FAIL | BLOCKED
checks:
- <id>: pass|fail - <evidence: numbers, <=3 log lines, screenshot path>
perf:
- <metric> = <value> (budget <budget>)
blocked: <exact user action, if any>
notes: <= 3 lines
```

## Facts
Device facts (SDK, refresh modes, stylus capabilities, quirks) are in `docs/notes/device.md`. Update it when you learn something new about the tablet.
