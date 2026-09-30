# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P03
next: P03 REVIEW
updated: 2026-09-30

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.

## Current phase progress
- P03: T01, T02, T03, T04, T05, T06, T07, T08, T09, T10

## Blocked (needs user; stops dependent tasks)
- P02-T06: storage onboarding never seen on the tablet (Roborazzi + unit tests cover it). Recheck 2026-09-29: All files access was still granted before any grant, so the `[Checked]` mark did not revoke it. -> turn off Settings > Apps > Folio Debug > Permissions > All files access, then write "-> revoked" here (Claude reruns without grant-storage.sh first); or approve new protected `scripts/device/revoke-storage.sh`: `source "$(dirname "$0")/_common.sh"; init_device; dshell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default; echo "MANAGE_EXTERNAL_STORAGE: $(dshell appops get --uid "$PKG" MANAGE_EXTERNAL_STORAGE | head -n1)"`. Blocks nothing else. [technically revoked, no app permissions yet to change (none at all), so there is no "All files access" permission]

## USER-CHECK (human verification; non-blocking)
- P03-T10 (your T07 flicker report, A-026): install the debug app, open a page with lots of ink, then pan quickly with one finger and pinch in and out (also right after writing, without restarting) -> template and strokes stay visible (briefly blurrier while zoomed, sharp again within about 0.2 s); no white patches or vanishing lines. Eraser width choice comes with the P04-T03 tool options row (4/10/24 pt exist). [Checked, smooth and non flicker, short blur sharpens very fast when zoomed in, so no issue. Note for Phase 11 or whatever else phase to increase detail of strokes or let it at least be customizeable (more edges=less blocky strokes)]

## Deferred (id: reason)
- D-001 P00-T07: P11 `benchmark` build type needs its own DebugHooksModule (src/benchmark, bind NoOpDebugHooks).
- D-002 P01-T08: spike route spike-fonts + core:text spike/ (remove in P06-T03; spike-ink/spike-stylus went in P03-T09); USER-CHECKs answered (A-008).
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-005 P01-REVIEW: .claude/rules/text-engine.md line 3 should name the grid-pitch reference scale + measured first-baseline correction (A-006); the edit was denied in the headless run, apply it interactively.
- D-006 P01-S3 (user feedback): decide BACKLOG B-12 (free font size tied to the rules, top/middle/bottom placement in the line box) in P06-T02 before building size styles.
- D-007 P02-REVIEW: repositories do not refuse open documents (favorite/rename/move/delete of an open doc); add an open-documents check with a typed error in P05-T01.
- D-009 P02-REVIEW: unpack fsyncs every entry and bounds total size at 8 GB; one fsync pass and a size-ratio zip-bomb bound (P11 perf).
- D-010 P02-REVIEW: session packs drop unknown manifest keys, unknown proto fields and unknown object kinds (04#versioning); preserve raw JSON and opaque objects before v2 exists. Also missing: LibraryWatcher event test (P05).
- D-011 P03-T03: CUSTOM templates render through `TemplateAssets` but nothing implements it yet (PNG decode from session assets, PDF page raster via core:pdf) and there is no import UI; add both in P08 (with the PDF raster).
- D-014 P03-T09: tilt shading has no off switch (A-025): dry rendering applies tilt whenever stored inputs carry tilt; the P11-T01 setting needs a stored per-stroke choice (or dropping tilt at commit) so wet and dry ink agree.
- D-015 P03-T10: `ink:commit` p95 2.02 ms vs 2 ms in debug (A-026 known gap); re-measure in the P11 benchmark build, if still over convert inputs (`StrokeBuilder.inputsOf`, `InkStroke.of`) off main.
- D-016 P03-T10: .claude/rules/rendering.md line 5 should read "During pan/zoom gestures transform existing tiles; only newly uncovered tiles at the current bucket are requested (throttled). Re-render at the new bucket after 100 ms idle: visible tiles first, center-out. Base tiles (low bucket) of visible pages are never evicted by the budget (A-026)."; edit denied headless, apply interactively.
- D-017 P03-T09 (USER-CHECK: "no thin ring visible"): hover ring never shows on the Pad 7. Likely cause: hover reaches `CanvasHostView` via `dispatchHoverEvent`/`onHoverEvent`, not the overridden `dispatchGenericMotionEvent`, so `InputRouter` never sees it. Fix in P03-REVIEW with a failing Robolectric test first, then a new USER-CHECK.

## Handoff (<= 5 lines, overwritten each session)
- P03-T10 done (A-026): pan-time tile requests (50 ms throttle), protected base tiles (fit-width bucket - 4), layered fallback, prefetch budget ignores evictable buckets; `ink:erase` section; motion prediction not adopted (ADR-002 note).
- Budgets (debug, 1500 strokes): onTouch 0.33, commit 2.02 (gap, D-015), settle p95 119 ms, janky 0.24%; gfxinfo p95 21 ms is a known gap, R-PERF-04 p95 moves to P11 CPU frame time (12-performance.md).
- P02-T06 user note says the app now has no All files access; recheck the onboarding (without grant-storage.sh) during P03-REVIEW device checks.
- Next: P03-REVIEW (reviewer subagent, fix D-017 hover ring, full `./gradlew qa`, tag p03-done).
