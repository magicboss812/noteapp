# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P01
next: P01 REVIEW
updated: 2026-09-28

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.

## Current phase progress
- P01: S1, S2 (ADR-003 A), S3 (ADR-008 grid pitch), S4 (ADR-007 jlatexmath), S5 (ADR-006), S6 (ADR-004), S7 (automated part), T08

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
- D-002 P01-T08: routes spike-ink/spike-stylus (remove in P03-T09) and spike-fonts + core:text spike/ (remove in P06-T03) stay for open USER-CHECKs (A-008).
- D-003 P01-REVIEW: run `instrumented.sh :core:pdf` (PdfBoxOverlayMergerInstrumentedTest, PdfRenderer checks) once the tablet accepts test APKs again (USER-CHECK P01-S5); next session with the setting on.
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.

## Handoff (<= 5 lines, overwritten each session)
- All spikes S1..S7 done; ADR-002..008 Accepted (S7 physical part is a USER-CHECK).
- T08 done: promoted FontRegistry/FontMetricsCache/GridTextLayouter, JLatexMathRenderer (0.04 em padding), PdfBoxOverlayMerger; RaTeX and tile/IO/PDF probes deleted (A-008); D-002 keeps 3 probe routes.
- Next: P01 REVIEW (reviewer p00-done..HEAD, fixes, full qa, maintain-memory, tag p01-done).
