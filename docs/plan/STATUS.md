# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P01
next: P01-S5
updated: 2026-09-27

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.

## Current phase progress
- P01: S1, S2 (ADR-003 A), S3 (ADR-008 grid pitch), S4a (host evidence)

## Blocked (needs user; stops dependent tasks)
- 2026-09-27 tablet unreachable: `connect.sh` finds 0 devices (no USB serial 2b6e1b3e, no wireless debugging). Needed: connect the Pad 7 (USB + accept the debugging prompt, or Wireless debugging on the same Wi-Fi) and keep it unlocked. Stops P01-S2b (tile measurements), P01-S3b (`instrumented.sh :core:text`, FolioProbe table -> ADR-008 Accepted), P01-S4b (same command, MathProbe table + PDF vectorOnly for RaTeX -> ADR-007) and the device parts of S5..S7. [Checked]

## USER-CHECK (human verification; non-blocking)
- P01-S1: `bash scripts/device/launch.sh --route spike-ink`, write with the Focus Pen there, then in Xiaomi Notes -> rate perceived lag 1-5 for each and report any flicker or gap when lifting the pen.
- P01-S3: `bash scripts/device/launch.sh --route spike-fonts` (drag to scroll, `debugcmd.sh spike-fonts zoom=2`) -> every font sits on the lines and all look equally sized; note any font that looks too bold or too light.
- P01-S1: HyperOS Settings > Display > refresh rate: which option is set, and is 144 Hz offered? -> with 144 Hz set, `bash scripts/device/diag.sh refresh` on spike-ink shows 120 or 144 (app request stayed at 120 Hz).

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).

## Handoff (<= 5 lines, overwritten each session)
- P01-S2a: route `spike-tiles` built (A bitmap / B RenderNode tiles, 1500 strokes, `zoom-anim`, `spike-tiles verify` pixel diff vs direct render); unit tests green.
- P01-S2b needs the tablet: 3 runs per strategy of zoom-anim 1->3->1, 10 s finger pan at zoom 3 (gfxinfo), perf-dump tiles:*, meminfo, verify at zoom 1 and 3; then ADR-003 evidence.
- Spike code lives in app/src/debug/.../spikes (routes spike-ink, spike-tiles); P01-T08 cleans up.
- P01-S3a: 20 OFL fonts in core:text, FontRegistry/FontMetricsCache/GridTextLayouter, route spike-fonts; host probe: GRID_PITCH exact, descent method off by up to 10 px (A-006).
- P01-S4a: MathRenderer + RaTeX (A) and jlatexmath (C) adapters; B excluded by API (no Canvas, raster/SVG export). C host: 97.5% parsed, p95 1.87 ms. App now arm64-v8a only.
- Next: P01-S5 (PDF render/export merge) build and host parts.
