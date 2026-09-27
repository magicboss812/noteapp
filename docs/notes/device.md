# Device: Xiaomi Pad 7
Filled by P00-T02, P01-S1, P01-S7 and later discoveries. Known from Xiaomi specs (unverified on this unit until probed): 11.2" 3200x2136, up to 144 Hz; Focus Pen 8192 pressure levels, 240 Hz sampling, three buttons bound to HyperOS functions (spotlight, screenshot, writing); tilt not listed in official specs.

## Identity
- Model 2410CRP4CG, device codename `uke`, adb serial `2b6e1b3e` (USB)
- Android 16, SDK 36, HyperOS OS3.0 (build OS3.0.303.0.WOZEUXM, EEA)
- minSdk for Folio: 35 (= min(35, 36); ADR-012 note 2026-09-27)

## Display
- Physical 2136x3200 (natural orientation portrait per `wm size`), density 440 dpi
- Refresh modes (fps): 30, 48, 50, 60, 90, 120, 144
- Editor mode: not chosen yet (P01)

## Input
- Touch: `NVTCapacitiveTouchScreen`
- Pen digitizer: `NVTCapacitivePenM80p` and `NVTCapacitivePenP81c` (sources KEYBOARD | TOUCHSCREEN | STYLUS)
- Focus Pen BT companions: `Xiaomi Focus Pen`, `Xiaomi Focus Pen Keyboard`, `Xiaomi Focus Pen Mouse`
- System window `stylus-handwriting-event-receiver-0` is a SPY with INTERCEPTS_STYLUS over the full screen (Android stylus handwriting); watch for it in P01-S7
- Touch sample rate: not measured yet

## Stylus
(from the P01-S7 probe: pressure range, tilt yes/no + range, orientation, hover yes/no, buttons delivered to apps, sample rate, synthetic `input stylus` limitations)

## Rendering
(front-buffer layer present while inking? observed latency notes from P01-S1)

## Quirks
(HyperOS install prompts, permission dialogs, background restrictions, anything surprising)
