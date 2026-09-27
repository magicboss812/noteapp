# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P01
next: P01-T08
updated: 2026-09-28

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.

## Current phase progress
- P01: S1, S2 (ADR-003 A), S3 (ADR-008 grid pitch), S4 (ADR-007 jlatexmath), S5 (ADR-006), S6 (ADR-004), S7 (automated part)

## Blocked (needs user; stops dependent tasks)
- (none)

## USER-CHECK (human verification; non-blocking)
- P01-S1: `bash scripts/device/launch.sh --route spike-ink`, write with the Focus Pen there, then in Xiaomi Notes -> rate perceived lag 1-5 for each and report any flicker or gap when lifting the pen.
- P01-S3: `bash scripts/device/launch.sh --route spike-fonts` (drag to scroll, `debugcmd.sh spike-fonts zoom=2`) -> every font sits on the lines and all look equally sized; note any font that looks too bold or too light.
- P01-S7: `bash scripts/device/launch.sh --route spike-stylus`, `debugcmd.sh spike-stylus reset`; with the Focus Pen: hover 5 s, write, tilt the pen while writing, press each pen button while hovering and while touching -> reply "-> ok"; then Claude runs `pull.sh probe` and fills device.md#stylus (tilt, hover, buttons, real sample rate). P03-T09 depends on it.
- P01-S5: Developer options > "USB debugging (Security settings)" was off on 2026-09-28 (test APK refused) -> turn it on again so `instrumented.sh` works (app-route fallback used meanwhile).
- P01-S1: HyperOS Settings > Display > refresh rate: which option is set, and is 144 Hz offered? -> with 144 Hz set, `bash scripts/device/diag.sh refresh` on spike-ink shows 120 or 144 (app request stayed at 120 Hz).

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).

## Handoff (<= 5 lines, overwritten each session)
- All spikes S1..S7 done; ADR-002..008 Accepted (S7 physical part is a USER-CHECK).
- Spike code: app/src/debug/.../spikes (routes spike-ink/tiles/fonts/io/stylus/pdf), core:text spike/ + math/, core:pdf spike/ + export/.
- T08 must: promote JLatexMathRenderer (+0.04 em padding, A-007) and drop RaTeX; keep FontRegistry, FontMetricsCache, GridTextLayouter, PdfBoxOverlayMerger; delete the rest of the spike code; qa green.
- Next: P01-T08, then P01 REVIEW.
