# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P02
next: P02-T01
updated: 2026-09-28

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.

## Current phase progress
- P02: (none)

## Blocked (needs user; stops dependent tasks)
- (none)

## USER-CHECK (human verification; non-blocking)
- P01-S1: `bash scripts/device/launch.sh --route spike-ink`, write with the Focus Pen there, then in Xiaomi Notes -> rate perceived lag 1-5 for each and report any flicker or gap when lifting the pen. [Checked, very responsive]
- P01-S3: `bash scripts/device/launch.sh --route spike-fonts` (drag to scroll, `debugcmd.sh spike-fonts zoom=2`) -> every font sits on the lines and all look equally sized; note any font that looks too bold or too light. [Checked, very good spacing, consider if not in the plan: font size can be changed, but relative spacing to template lines stays, so absolute spacing changes dynamically with font size; font can be alligned from top to middle to bottom in between the template lines]
- P01-S7: `bash scripts/device/launch.sh --route spike-stylus`, `debugcmd.sh spike-stylus reset`; with the Focus Pen: hover 5 s, write, tilt the pen while writing, press each pen button while hovering and while touching -> reply "-> ok"; then Claude runs `pull.sh probe` and fills device.md#stylus (tilt, hover, buttons, real sample rate). P03-T09 depends on it. [Checked, 450hz response, really good results on all tilting angles which is great, hovering also xtremely sharp]
- P01-S5: Developer options > "USB debugging (Security settings)" was off on 2026-09-28 (test APK refused) -> turn it on again so `instrumented.sh` works (app-route fallback used meanwhile). [Checked, it's on]
- P01-S1: HyperOS Settings > Display > refresh rate: which option is set, and is 144 Hz offered? -> with 144 Hz set, `bash scripts/device/diag.sh refresh` on spike-ink shows 120 or 144 (app request stayed at 120 Hz). [Checked and its obsolute, no usercheck for this necessary]

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).
- D-002 P01-T08: routes spike-ink/spike-stylus (remove in P03-T09) and spike-fonts + core:text spike/ (remove in P06-T03) stay for open USER-CHECKs (A-008).
- D-003 P01-REVIEW: run `instrumented.sh :core:pdf` (PdfBoxOverlayMergerInstrumentedTest, PdfRenderer checks) once the tablet accepts test APKs again (USER-CHECK P01-S5); next session with the setting on.
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-005 P01-REVIEW: .claude/rules/text-engine.md line 3 should name the grid-pitch reference scale + measured first-baseline correction (A-006); the edit was denied in the headless run, apply it interactively.

## Handoff (<= 5 lines, overwritten each session)
- P01 closed with tag p01-done after REVIEW (4 blocking findings fixed; D-003..D-005 deferred).
- Tablet refuses test APKs until the P01-S5 USER-CHECK setting is on; app-route probes work.
- Next: P02-T01 (model types and geometry).
