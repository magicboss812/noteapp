# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P02
next: P02-T04
updated: 2026-09-28

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.

## Current phase progress
- P02: T01 T02 T03

## Blocked (needs user; stops dependent tasks)
- (none)

## USER-CHECK (human verification; non-blocking)
- (none)

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).
- D-002 P01-T08: spike routes spike-ink/spike-stylus (remove in P03-T09) and spike-fonts + core:text spike/ (remove in P06-T03); their USER-CHECKs are answered (A-008).
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-005 P01-REVIEW: .claude/rules/text-engine.md line 3 should name the grid-pitch reference scale + measured first-baseline correction (A-006); the edit was denied in the headless run, apply it interactively.
- D-006 P01-S3 (user feedback): decide BACKLOG B-12 (free font size tied to the rules, top/middle/bottom placement in the line box) in P06-T02 before building size styles.

## Handoff (<= 5 lines, overwritten each session)
- P01 USER-CHECKs answered: stylus facts in device.md#stylus (457 Hz, tilt, hover, no buttons); D-003 ran 1/1 on the Pad 7.
- P02-T01..T03 done: model, commands + UndoManager (A-009, A-010), Wire page codec (1000x120 inputs = 709 KB).
- Next: P02-T04 (manifest and container).
