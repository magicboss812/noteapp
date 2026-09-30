# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P04
next: P04-T01
updated: 2026-09-30

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.
- P03 done 2026-09-30 (tag p03-done): viewport + page stack, templates, tiles (bucketed, base tiles, pan-time requests), wet ink + dry handoff (handoff p95 31 ms), eraser, stylus caps + hover ring; debug 1500 strokes: onTouch p95 0.33 ms, settle p95 119 ms, janky 0.24%, commit 2.02 ms (D-015); REVIEW fixed 1 blocking finding (commits survive a detach); A-017..A-026; qa green.

## Current phase progress
- P04: (none)

## Blocked (needs user; stops dependent tasks)
- P02-T06: storage onboarding never seen on the tablet (Roborazzi + unit tests cover it). Recheck P03-REVIEW (no grant-storage.sh, after `pm clear`): the app still reports All files access granted and shows the library. The toggle is not in the app's permission list: open Settings > Privacy (or Apps) > Special app access (Special permissions) > All files access > Folio Debug > off, then write "-> revoked" here; or approve new protected `scripts/device/revoke-storage.sh`: `source "$(dirname "$0")/_common.sh"; init_device; dshell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default; echo "MANAGE_EXTERNAL_STORAGE: $(dshell appops get --uid "$PKG" MANAGE_EXTERNAL_STORAGE | head -n1)"`. Blocks nothing else.

## USER-CHECK (human verification; non-blocking)
- P03-REVIEW (D-017, your "no thin ring visible"): install the debug app, open a document, hold the Focus Pen just above the page -> a thin dark ring (light edge) follows the tip, at least pen-dot size; the system hover dot is gone over the canvas; the ring disappears when the pen touches or leaves. If still no ring, keep the app open and write "no ring": Claude reads `hoverEvents` from debug `state` (0 = hover never reaches the canvas).

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
- D-018 P03-T10 (user note): dry strokes look slightly blocky when zoomed in; raise mesh/tile detail or make it a setting (P11).
- D-019 P03-REVIEW: `TileLayer.launchRender` leaves a failed (non-cancel) render's key in `inFlight`/`waitingVisible` (never retried, uncaught); catch, clear the key, log, retry. Fix with a failing test before P08 (PDF rasters can throw).
- D-020 P03-REVIEW: pan-time `requestTiles` allocates lists/`RectPt`/`VisiblePage`/boxed `Pair` per tile and may log evictions; `DryHandoff.check` allocates and `isDrawn` scans all page objects per pending stroke. Reuse scratch objects, id-set lookup (P11 perf).
- D-021 P03-REVIEW: `CanvasHostView` hardcodes `Dispatchers.Main.immediate` (inject via `CanvasController`) and has no accessibility description; fix in P04-T02 editor scaffold.
- D-022 P03-REVIEW: an erase gesture right after a pen lift cannot erase strokes still in the dry handoff (snapshot = committed document); include pending strokes (P07 or P11).

## Handoff (<= 5 lines, overwritten each session)
- P03 closed (tag p03-done). REVIEW: commits/erases run NonCancellable so a detach never drops finished strokes; detach removes pending wet copies; hover ring hides the system stylus pointer + `hoverEvents` in debug state (D-017, new USER-CHECK); qa green.
- Reviewer ran without git diff (Bash denied in its session); it read the canvas/tile/handoff core by path. Its non-blocking findings are D-019..D-022.
- Next: P04-T01 (design system foundation).
