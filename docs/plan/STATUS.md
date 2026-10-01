# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P04
next: P04-T09
updated: 2026-10-02

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.
- P03 done 2026-09-30 (tag p03-done): viewport + page stack, templates, tiles (bucketed, base tiles, pan-time requests), wet ink + dry handoff (handoff p95 31 ms), eraser, stylus caps + hover ring; debug 1500 strokes: onTouch p95 0.33 ms, settle p95 119 ms, janky 0.24%, commit 2.02 ms (D-015); REVIEW fixed 1 blocking finding (commits survive a detach); A-017..A-026; qa green.

## Current phase progress
- P04: T01, T02, T03, T04, T10, T05 (device check pending), T06 (device check pending), T07 (device check pending), T08 (device check pending)

## Blocked (needs user; stops dependent tasks)
- P02-T06: storage onboarding never seen on the tablet (Roborazzi + unit tests cover it). Recheck P03-REVIEW (no grant-storage.sh, after `pm clear`): the app still reports All files access granted and shows the library. The toggle is not in the app's permission list: open Settings > Privacy (or Apps) > Special app access (Special permissions) > All files access > Folio Debug > off, then write "-> revoked" here; or approve new protected `scripts/device/revoke-storage.sh`: `source "$(dirname "$0")/_common.sh"; init_device; dshell appops set --uid "$PKG" MANAGE_EXTERNAL_STORAGE default; echo "MANAGE_EXTERNAL_STORAGE: $(dshell appops get --uid "$PKG" MANAGE_EXTERNAL_STORAGE | head -n1)"`. Blocks nothing else. [revoked]
- P04-T05 device check not run (no tablet: connect.sh found 0 devices): connect the Pad 7 (Wireless debugging on, unlocked), then have device-tester run: Ctrl+Z undoes the last stroke (`input.sh combo CTRL_LEFT Z`), Ctrl+Y redoes, Alt+3 eraser / Alt+1 pen, Ctrl+/ help sheet + Esc, PageDown/PageUp, Ctrl+= / Ctrl+0 zoom. Blocks nothing else.
- P04-T06 device check not run (same: 0 devices): device-tester opens a 4+ page note, `debugcmd.sh reorder-page 2,1`, reads `pageOrder` in `state`, reopens the doc and checks the order held; smoke: panel, overview, add, duplicate, delete + Ctrl+Z, settings sheet apply. Blocks nothing else.
- P04-T07 device check not run (0 devices): device-tester taps "New note" on the library, edits the title (or keeps it), taps Create; the editor opens and the `.folio` appears in Folio-Debug (`scripts/device` file listing); reopen the sheet and check the last choices are the defaults. Blocks nothing else.

- P04-T08 device check not run (0 devices): device-tester draws strokes, `stop.sh` before the 30 s pack, relaunches: library shows "Recovered unsaved changes." and the note has every stroke; save dot bottom-left is green when idle. Blocks nothing else.

## USER-CHECK (human verification; non-blocking)
- P03-REVIEW (D-017, your "no thin ring visible"): install the debug app, open a document, hold the Focus Pen just above the page -> a thin dark ring (light edge) follows the tip, at least pen-dot size; the system hover dot is gone over the canvas; the ring disappears when the pen touches or leaves. If still no ring, keep the app open and write "no ring": Claude reads `hoverEvents` from debug `state` (0 = hover never reaches the canvas). [Checked, ring is there]
- P04-T03: open a note, try row 2 (pen kinds, widths, long-press a width or color, + add color, pen settings, eraser modes, clear page) -> controls respond, ink uses the choices, choices survive closing and reopening the app. [Checked, really good, pressure is considered, eraser modes there but eraser ring disappears when pressing, needs to be visible when erasing always, reopening the app keeps progress]
- P04-T04: drag the toolbar grip (dots at the right end of row 1, or the bottom of a side rail) to the left and right edges and release it mid-canvas to float; double-tap the floating grip -> moves feel smooth, docks snap with a spring, the placement survives closing and reopening the app (separately per orientation). [Checked]
- P04-T10: open the library placeholder and a note on the tablet -> toolbar, options row, popovers, colors and type read as Notewise; differences go to docs/plan/FEEDBACK.md.
- P04-T06: toolbar Pages (drawer), Overview (grid) and Add page buttons; long-press and drag a panel row to reorder; row menu, page settings -> panels and sheet read well, order survives reopening.
- P04-T07: library "New note" -> sheet with title, paper size/orientation, fixed/infinite, template gallery previews, spacing, paper color; Create opens the note; the next sheet starts with your last choices.
- P04-T08: draw, then watch the small dot at the editor's bottom-left (green idle, orange while saving); force-quit mid-edit and reopen -> "Recovered unsaved changes." snackbar on the library.

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
- D-022 P03-REVIEW: an erase gesture right after a pen lift cannot erase strokes still in the dry handoff (snapshot = committed document); include pending strokes (P07 or P11).
- D-024 P04-T03: highlighter "always straight" is stored and toggled in the options row, but the canvas does not snap yet; honor it with the highlighter straight-line snap (P07-T02).
- D-025 P04-T04: handedness is modeled and stored (`ToolbarDocks.withHandedness`, A-029) but has no toggle; add it to the settings Toolbar section (10-editor-ui.md#settings) when feature:settings is built.
- D-026 P04-T05: Alt+9 (Ruler toggle) is not bound and the help sheet lacks later groups (text, selection, Ctrl+N/O/F/W/S/\); each feature task adds its group to `ShortcutRegistry.DEFAULT_GROUPS` (P05, P06, P07, P10).
- D-027 P04-T06: duplicates drop text frames and sticky notes until P06 flow cloning; thumbnails load through the session LRU (30) and can evict canvas pages in long docs; toolbar "More" menu, expand-to-infinite, move-to-another-document not built; floating toolbar has no page buttons (docked only); delete refuses the last page.
- D-028 P04-T08: stored thumbs (`thumbs/<page>.webp`, cover) are written 2 s after edits (ThumbnailGenerator) but the page panel still renders live and the library does not read the cover yet (P05); thumbs pending when the editor closes within 2 s are skipped.

## Handoff (<= 5 lines, overwritten each session)
- P04-T08 code done (storage/editor/library tests, detekt, lint, Roborazzi, assembleDebug green); T05-T08 device checks open (Blocked, no tablet).
- Save state: `DocumentSession.saveState` (EntryAutosaver.state + `packFinished`), `SaveIndicator` (editor bottom-left), `EditorViewModel.retrySave`; recovery: `LibraryEntryViewModel.recoveryNotice` + snackbar; thumbs: `ThumbnailGenerator` (D-028).
- Next: T09 (editor instrumented tests; device-only, will block if no tablet, then P04 REVIEW). Known nit: FolderCard back page overlaps the folder name (P05).
- FEEDBACK.md turns into tasks at every REVIEW. `FolioIcons.kt` is generated (A-027): `./gradlew :tools:icongen:run`.
