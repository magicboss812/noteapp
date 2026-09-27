# Device: Xiaomi Pad 7
Filled by P00-T02, P01-S1, P01-S7 and later discoveries. Known from Xiaomi specs (unverified on this unit until probed): 11.2" 3200x2136, up to 144 Hz; Focus Pen 8192 pressure levels, 240 Hz sampling, three buttons bound to HyperOS functions (spotlight, screenshot, writing); tilt not listed in official specs.

## Identity
- Model 2410CRP4CG, device codename `uke`, adb serial `2b6e1b3e` (USB)
- Android 16, SDK 36, HyperOS OS3.0 (build OS3.0.303.0.WOZEUXM, EEA)
- minSdk for Folio: 35 (= min(35, 36); ADR-012 note 2026-09-27)

## Display
- Physical 2136x3200 (natural orientation portrait per `wm size`), density 440 dpi
- Refresh modes (fps): 30, 48, 50, 60, 90, 120, 144
- Editor mode: request the fastest mode (144 Hz, mode id 2) via `preferredDisplayModeId` (`DisplayModeHelper`). P01-S1: the active mode stayed 120 Hz, idle and inking (`diag.sh refresh`: `peakRefreshRate=120`); probably the HyperOS refresh-rate setting caps apps (USER-CHECK). Frame budgets at 120 Hz: 8.3 ms.
- Usual orientation while testing: landscape, rotation 1 (3200x2136)

## Input
- Touch: `NVTCapacitiveTouchScreen`
- Pen digitizer: `NVTCapacitivePenM80p` and `NVTCapacitivePenP81c` (sources KEYBOARD | TOUCHSCREEN | STYLUS)
- Focus Pen BT companions: `Xiaomi Focus Pen`, `Xiaomi Focus Pen Keyboard`, `Xiaomi Focus Pen Mouse`
- System window `stylus-handwriting-event-receiver-0` is a SPY with INTERCEPTS_STYLUS over the full screen (Android stylus handwriting); watch for it in P01-S7
- Touch sample rate: not measured yet

## Stylus
(from the P01-S7 probe: pressure range, tilt yes/no + range, orientation, hover yes/no, buttons delivered to apps, sample rate, synthetic `input stylus` limitations)

## Rendering
- Front-buffered wet ink works: InProgressStrokesView adds `SurfaceView[...](BLAST)` (z=1) above the app window on the first stroke and keeps it; `screencap` captures it (P01-S1).
- Wet-ink main-thread cost `ink:onTouch` p95 0.28 ms; wet-to-dry handoff one frame (decisions.md ADR-002 evidence).
- HWUI logs `E/HWUI [m2] set surface nullptr` about once per wet-ink frame; harmless noise.
- Synthetic `input stylus swipe` of 400 ms delivers 50 MotionEvents; gfxinfo flags ~80% of those frames "High input latency" (likely an artifact of injected event timestamps).

## Quirks
- adb installs fail with `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` unless Developer options > "Install via USB" is on (HyperOS may require a Mi account sign-in). Prompts on screen need a tap.
- The separate test APK (`dev.folio.notes.debug.test`, instrumented tests) also needs "USB debugging (Security settings)" (Mi account sign-in); without it only the app APK installs. Both enabled 2026-09-27. Library modules with instrumented tests set `defaultConfig.testApplicationId = "dev.folio.notes.debug.test"` so no other package is ever installed (first: core:text, P01-S3b).
- `pm uninstall` of a package that is not installed returns `DELETE_FAILED_INTERNAL_ERROR` (harmless).
- Our process logs HyperOS framework noise at start (E/ `MI-PreRender`, `FramePredict`, `FrameInsert`); ignore when scanning logcat.
- System locale de_DE and system dark mode on (the P00 placeholder renders light; dark UI arrives in P11).
