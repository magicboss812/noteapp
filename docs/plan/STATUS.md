# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P03
next: P03-T07
updated: 2026-09-30

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.

## Current phase progress
- P03: T01, T02, T03, T04, T05, T06

## Blocked (needs user; stops dependent tasks)
- P02-T06: storage onboarding never seen on the tablet (Roborazzi + unit tests cover it). Recheck 2026-09-29: All files access was still granted before any grant, so the `[Checked]` mark did not revoke it. -> turn off Settings > Apps > Folio Debug > Permissions > All files access, then write "-> revoked" here (Claude reruns without grant-storage.sh first); or approve new protected `scripts/device/revoke-storage.sh`: `source "$(dirname "$0")/_common.sh"; init_device; dshell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default; echo "MANAGE_EXTERNAL_STORAGE: $(dshell appops get --uid "$PKG" MANAGE_EXTERNAL_STORAGE | head -n1)"`. Blocks nothing else. [technically revoked, no app permissions yet to change (none at all), so there is no "All files access" permission]

## USER-CHECK (human verification; non-blocking)
- (none)

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).
- D-002 P01-T08: spike routes spike-ink/spike-stylus (remove in P03-T09) and spike-fonts + core:text spike/ (remove in P06-T03); their USER-CHECKs are answered (A-008).
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-005 P01-REVIEW: .claude/rules/text-engine.md line 3 should name the grid-pitch reference scale + measured first-baseline correction (A-006); the edit was denied in the headless run, apply it interactively.
- D-006 P01-S3 (user feedback): decide BACKLOG B-12 (free font size tied to the rules, top/middle/bottom placement in the line box) in P06-T02 before building size styles.
- D-007 P02-REVIEW: repositories do not refuse open documents (favorite/rename/move/delete of an open doc); add an open-documents check with a typed error in P05-T01.
- D-009 P02-REVIEW: unpack fsyncs every entry and bounds total size at 8 GB; one fsync pass and a size-ratio zip-bomb bound (P11 perf).
- D-010 P02-REVIEW: session packs drop unknown manifest keys, unknown proto fields and unknown object kinds (04#versioning); preserve raw JSON and opaque objects before v2 exists. Also missing: LibraryWatcher event test (P05).
- D-011 P03-T03: CUSTOM templates render through `TemplateAssets` but nothing implements it yet (PNG decode from session assets, PDF page raster via core:pdf) and there is no import UI; add both in P08 (with the PDF raster).
- D-012 P03-T06: wet ink has no motion prediction yet (06#wet-ink, ADR-002); `androidx.input:input-motionprediction` is pre-release (1.0.0-rc01 on androidx main), so decide it with a decisions.md note and measure in P03-T10.

## Handoff (<= 5 lines, overwritten each session)
- P03-T06 done: core/ink `input/` (`InputRouter` + `StylusTarget`/`NavigationTarget`, palm rules, `StylusCapabilities`); editor `PenInput` starts strokes in page pt (strokeToView = page->px), `WetSurface`/`InkWetSurface` wrap InProgressStrokesView (tests use `FakeWetSurface`); `CanvasController.activeBrush`.
- Finished wet strokes stay in InProgressStrokesView at screen position (they do not follow pan/zoom) until T07's dry handoff; `PenInput.pageIndex` is the stroke's page, `specOf(stroke.brush)` recovers the spec.
- Device: 50 stylus-swipes -> 50 finished, 20 finger swipes -> 0 strokes + panned, `ink:onTouch` p95 0.64 ms. The first injected stylus swipe after launch is lost (do a throwaway stylus-tap); injected events report no caps (`state.canvas.stylus` all false), pencil ramp needs the real pen.
- Debug `state.canvas.ink` {started, finished, canceled}; each `debugcmd.sh` call takes ~6 s.
- Next: P03-T07 dry handoff and persistence.
