# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P01
next: P01-S3
updated: 2026-09-27

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.

## Current phase progress
- P01: S1, S2a (build only)

## Blocked (needs user; stops dependent tasks)
- 2026-09-27 tablet unreachable: `connect.sh` finds 0 devices (no USB serial 2b6e1b3e, no wireless debugging). Needed: connect the Pad 7 (USB + accept the debugging prompt, or Wireless debugging on the same Wi-Fi) and keep it unlocked. Stops P01-S2b (tile measurements) and the device parts of S3..S7.

## USER-CHECK (human verification; non-blocking)
- P01-S1: `bash scripts/device/launch.sh --route spike-ink`, write with the Focus Pen there, then in Xiaomi Notes -> rate perceived lag 1-5 for each and report any flicker or gap when lifting the pen.
- P01-S1: HyperOS Settings > Display > refresh rate: which option is set, and is 144 Hz offered? -> with 144 Hz set, `bash scripts/device/diag.sh refresh` on spike-ink shows 120 or 144 (app request stayed at 120 Hz).

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).

## Handoff (<= 5 lines, overwritten each session)
- P01-S2a: route `spike-tiles` built (A bitmap / B RenderNode tiles, 1500 strokes, `zoom-anim`, `spike-tiles verify` pixel diff vs direct render); unit tests green.
- P01-S2b needs the tablet: 3 runs per strategy of zoom-anim 1->3->1, 10 s finger pan at zoom 3 (gfxinfo), perf-dump tiles:*, meminfo, verify at zoom 1 and 3; then ADR-003 evidence.
- Spike code lives in app/src/debug/.../spikes (routes spike-ink, spike-tiles); P01-T08 cleans up.
- Next: P01-S3 build parts that need no tablet.
