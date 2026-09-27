# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P01
next: P01-S2
updated: 2026-09-27

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.

## Current phase progress
- P01: S1

## Blocked (needs user; stops dependent tasks)
- (none)

## USER-CHECK (human verification; non-blocking)
- P01-S1: `bash scripts/device/launch.sh --route spike-ink`, write with the Focus Pen there, then in Xiaomi Notes -> rate perceived lag 1-5 for each and report any flicker or gap when lifting the pen.
- P01-S1: HyperOS Settings > Display > refresh rate: which option is set, and is 144 Hz offered? -> with 144 Hz set, `bash scripts/device/diag.sh refresh` on spike-ink shows 120 or 144 (app request stayed at 120 Hz).

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).

## Handoff (<= 5 lines, overwritten each session)
- P01-S1 done: ADR-002 Accepted, front buffer works, onTouch p95 0.28 ms, handoff via frame-commit callback (A-005). Display stays 120 Hz (USER-CHECK).
- Spike code: app/src/debug/.../spikes (route spike-ink, `debugcmd.sh spike-ink`); DisplayModeHelper already in core:render (P03 wires it). P01-T08 cleans up.
- Next: P01-S2 (committed-content renderer) via the spike skill.
