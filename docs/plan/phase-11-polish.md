# P11 Polish and hardening
Goal: settings, dark UI, motion pass, complete shortcuts, baseline profile, release build, robustness, final performance audit.
Exit: all budgets met in the release build; USER-CHECK list empty; FEEDBACK `Open` and STATUS `Deferred` empty (each converted to a task and done, or accepted by the user); tag `p11-done` and `v1.0.0`.

### P11-T01 Settings
Model: opus medium
Implements: R-UI-02, R-PAGE-03
Read: 10-editor-ui.md#settings
Do: settings screens for every item in 10-editor-ui.md#settings; DataStore; restore defaults; Toolbar section handedness toggle over the stored `ToolbarDocks.withHandedness` (D-025, A-029). Tilt shading switch (D-014, A-025): store the choice per stroke (or drop tilt at commit) so wet and dry ink agree, and show the pen panel's tilt row (10-editor-ui.md). Math block alignment setting, centered or left (07-text-engine.md#math, D-034, A-047).
Accept:
- [ ] screenshot: settings screens
- [ ] unit: settings round trip and defaults
- [ ] unit: with tilt shading off, a stroke with tilt inputs renders dry exactly like its wet ink (no tilt); a left-aligned math block lays out at the frame's left edge (D-014, D-034)
Verify: tests + screenshots

### P11-T02 Dark UI
Model: opus medium
Implements: R-PAGE-03
Read: 11-design-system.md#colors
Do: dark tokens everywhere; canvas surroundings dark; paper and ink colors unchanged; theme setting (system/light/dark).
Accept:
- [ ] screenshot: every screen dark; page paper remains white in dark mode
Verify: screenshots

### P11-T03 Motion pass
Model: opus medium
Implements: R-UI-01
Read: 11-design-system.md#motion
Do: audit every transition against the motion spec; add missing ones (tool switch, options row, sheets, page add/delete, selection handles, toolbar docking); nothing blocks input during animation. User feedback 2026-10-23 (P04/P05 USER-CHECKs): library -> editor open and close (tap, back button, predictive back) feel rough: smooth the A-035 scale + fade (no flash of the window background, no jump when the editor's first tiles arrive); sidebar collapse/expand, card delete and duplicate reflow; page panel drawer, overview, page reorder and the new-note sheet need smoother enter/exit. Keep the A-035 approach (the user did not ask for a card morph).
Accept:
- [ ] device: gfxinfo janky <= 1% over 5 open/close cycles from the library and 5 sidebar toggles
- [ ] user: "Use the app for 10 minutes. Any animation that feels slow, janky, or missing?"
Verify: device-tester smoke

### P11-T04 Keyboard shortcuts complete
Model: opus medium
Implements: R-UI-05
Read: 10-editor-ui.md#keyboard-shortcuts
Do: implement the full table (including Alt+9 Ruler toggle); help sheet lists them, with every group in `ShortcutRegistry.DEFAULT_GROUPS` (text, selection, Ctrl+N/O/F/W/S/\, D-026); conflicts with text editing resolved (text field first, then editor).
Accept:
- [ ] unit: registry covers the table
- [ ] device: 5 representative combos via `input.sh combo`
Verify: tests + device-tester

### P11-T05 Baseline profile, benchmark build, release build
Model: opus medium
Implements: R-PERF-01
Read: 12-performance.md#measurement, .claude/rules/gradle.md
Do: `benchmark` build type and `:benchmark` macrobenchmark module (startup, open note, draw, pan); Baseline Profile generation on the tablet; release build with R8 full mode and shrinkResources; signing from `keystore.properties` if present (user creates the keystore; add a Blocked item with the exact keytool command if missing). The benchmark build needs its own DebugHooksModule (`src/benchmark`, binds NoOpDebugHooks, D-001). Re-measure `ink:commit`; if p95 > 2 ms convert inputs (`StrokeBuilder.inputsOf`, `InkStroke.of`) off main (D-015, A-026).
Accept:
- [ ] device: macrobenchmarks run; cold start <= 700 ms (benchmark build)
- [ ] device: read mode enter/exit (5 cycles, 1500 strokes) and tool switches <= 1% janky in the benchmark build; if not, cut the first-frame cost of the toggle (D-031, A-038)
- [ ] device: `ink:commit` p95 <= 2 ms in the benchmark build (D-015)
- [ ] device: in the benchmark build, the first Text tool creation and the first focus tap after opening a note take <= 50 ms tap to caret (`text:caret`), measured in a fresh process and in a long-lived one; if not, cut the first-run cost (first draw of the formatting bar, input-method bind) (D-037, A-052)
- [ ] device: in the benchmark build, `text:key` p95 < 16 ms typing on a 20k-word flow (`seed-text 20000`, paced and burst); if not, cut the per-frame cost of light frames (caret blink, keystroke; GPU about 18 ms in the debug build) (D-043, A-056)
- [ ] release APK builds; size recorded in perf.md
Verify: `instrumented.sh :benchmark connectedBenchmarkAndroidTest`

### P11-T06 Robustness
Model: opus high
Implements: R-FILE-01, R-FILE-02
Read: 04-file-format.md#conflicts, #versioning
Do: corrupted file handling (open read-only with message, never crash the library), low storage (pack fails gracefully, working copy kept, banner), external modifications while open, very large docs (500 pages), permission revoked while running, process death during every lifecycle state. Unpack: one fsync pass and a size-ratio zip-bomb bound instead of fsync per entry and a flat 8 GB cap (D-009). Forward compatibility (04#versioning): session packs keep unknown manifest keys (raw JSON), unknown proto fields and unknown object kinds (opaque) (D-010). `LibraryWatcher` event test (D-010). False external-change conflicts (D-030): on the tablet the packer reported "changed externally (2080 B, base 2080 B)" right after `seed-strokes` and packed a "(conflict ...)" copy; the size matched, so the mtime check (A-022 quirk) misfires: compare content (hash) before declaring a conflict.
Accept:
- [ ] unit/instrumented: fault-injection suite green
- [ ] unit: unknown manifest keys, proto fields and object kinds survive open + pack byte-equal; zip bomb rejected; watcher emits create/modify/delete (D-009, D-010); a working copy whose file only got a new mtime (same bytes) packs in place, no conflict copy (D-030)
Verify: `./gradlew qa` + `instrumented.sh :core:storage`

### P11-T08 Dry ink detail at high zoom
Model: opus high
Implements: R-INK-01, R-PERF-04
Read: 05-canvas-rendering.md#tiles, 06-ink-input.md#brushes
Do: dry strokes look slightly blocky when zoomed in (user note, D-018): find whether mesh resolution or tile bucket scale causes it; raise detail at high buckets (or a quality setting in P11-T01) within the tile render budget.
Accept:
- [ ] screenshot: one stroke at zoom 1, 4 and max: edges smooth at every zoom (golden)
- [ ] device: tile render p95 within 12-performance.md budget after the change
- [ ] user: "Zoom into handwriting. Do strokes still look blocky?"
Verify: screenshots + device-tester

### P11-T09 Ink and tile hot-path allocations
Model: opus high
Implements: R-PERF-03, R-PERF-04
Read: 12-performance.md#budgets, .claude/rules/rendering.md
Do: pan-time `requestTiles` allocates lists, `RectPt`, `VisiblePage` and boxed `Pair` per tile and may log evictions; `DryHandoff.check` allocates and `isDrawn` scans all page objects per pending stroke (D-020). Reuse scratch objects, id-set lookup, no logging on the hot path. User report 2026-11-07: a long pen line hid for a split second at pen-up on an almost empty page at 100 % (not reproduced; `ink-probe` saw no app frame lose a stroke): after the handoff changes, draw 20 long strokes on an empty page and watch for it; if it shows, check androidx.ink's front-buffer layer release against the dry tile commit.
Accept:
- [ ] unit: allocation-free `requestTiles` and `DryHandoff.check` in steady state (allocation counter test)
- [ ] device: pan gfxinfo janky <= 1% with 1500 strokes
Verify: tests + device-tester

### P11-T10 Page management completion
Model: opus medium
Implements: R-PAGE-01, R-PAGE-02
Read: 10-editor-ui.md#pages
Do: what P04-T06/T08 left out (D-027, D-028): mode pill "More" menu, move page to another document, page buttons on the floating toolbar, the go-to-page field on the info pill's page fraction ("3 / 12", 10-editor-ui.md#pages); the page panel shows stored `thumbs/<page>.webp` instead of loading pages through the session LRU (no canvas-page eviction in long docs); thumbs still pending when the editor closes are written before the session closes. Deleting the last page stays refused. The autosave dot (bottom-left) sits below the pages panel and the overview, not on top of them (user feedback 2026-10-23).
Accept:
- [ ] unit: move page between documents (undoable in the source); pending thumbs flushed on close
- [ ] screenshot: More menu; floating toolbar with page buttons; go-to-page field; pages panel and overview open with the autosave dot hidden behind them
- [ ] device: 100-page note, open the page panel and scroll: canvas pages are not reloaded
Verify: tests + screenshots + device-tester

### P11-T11 Library robustness and polish follow-ups
Model: opus medium
Implements: R-ORG-01, R-ORG-04, R-FILE-01
Read: 09-storage-library.md#repositories, #trash, #search, 11-design-system.md#library-screen
Do: P05 REVIEW leftovers. `DocumentSessions.refuseIfOpen` reads the open set outside the sessions lock (a note opened mid-move can pack to the old path): hold the lock for guarded operations or refuse `open()` on paths being changed; a session that is releasing counts as closed, so "is open in the editor" does not show for ~1 s after back. One `Mutex` around bin restore, delete forever, empty and purge. Search: snippet from the body column so title-only hits do not repeat the title; `titleHit` folds accents like unicode61 (NFD). `LibraryEntryViewModel.allDocuments()` uses one all-documents query instead of N+1 folder flows. Bin tab: "days left" refreshes on resume (or a daily tick); fix the wrong "lazy" comment in `LibraryBinViewModel`; hide the inert sort, view and select controls on the Bin tab. Portrait card grid: center or stretch so the right margin is not ragged. User feedback 2026-10-23: a folder or tag chip in search with an empty query lists every matching note (today a tag only filters once a word is typed); in selection mode "Select all" turns into "Deselect all" once everything is selected, and tapping a card still toggles it; the card "..." gets more inset from the card's top-right border.
Accept:
- [ ] unit: open during a guarded move is refused or waits; released session is not reported open; concurrent purge + restore on one entry leaves a consistent bin
- [ ] unit: title-only hit snippet does not repeat the title; "cafe" ranks "Café" as a title hit; LibrarySearchViewModel filters, saves the query before close and resets on close; `bin-purge` job queued with KEEP
- [ ] unit: search with an empty query and a tag (or folder) filter returns every note with that tag; select all then deselect all clears the selection
- [ ] screenshot: Bin tab without sort/view/select; 12-notes grid portrait with even margins; selection bar with "Deselect all"; card overflow inset
Verify: `./gradlew :core:storage:testDebugUnitTest :feature:library:testDebugUnitTest :feature:library:verifyRoborazziDebug`

### P11-T12 Text editing follow-ups
Model: opus high
Implements: R-TXT-02, R-TXT-07
Read: 07-text-engine.md#live-field-as-built, #math-as-built, 10-editor-ui.md#text-tool
Do: P06 device findings. The math error popup anchors at the bad formula, not at the far right of the page (x 2400-3000 px instead of 460-1000 px). Opening another note never leaves the soft keyboard up with nothing focused; Esc closes it like Back. The task checkbox glyph keeps a gap before its text (today "☐task"). Free text box grip with the pen (D-041, A-045, A-055): HyperOS takes a pen down on a focused box's grip as handwriting; move the grip outside the field's bounds or blur the field on stylus hover over the grip while keeping the handles. At 25 % zoom a finger drag on the grip moves the box instead of panning the page (D-042). P06 REVIEW leftovers: a rejected box drop (`boxCommand` null or execute refused) leaves `BoxHandles`' preview outline at the drop spot (clear it and redraw from the document); a font that arrives after open (FontLibrary sync, Import font) clears the layouter cache but `EditorText.refresh` keeps the fallback layouts until the next edit (add the registry generation to `LayoutInputs`); `CustomFontStore.installed()` reads and parses every font file per `registry()` call (cache by name and size, drop on import or delete).
Accept:
- [ ] unit: math error popup position equals the formula's on-screen bounds; checkbox prefix width includes the gap; grip hit wins over pan at zoom 0.25
- [ ] unit: rejected box drop clears the preview; a font registered after open re-lays out the flows using it; `installed()` parses each font file once
- [ ] screenshot: task line with a checked and an unchecked box (gap visible)
- [ ] device: math error popup next to the formula; open note A, focus text, open note B: no keyboard; pen drag on a focused box's grip moves it (no handwriting toast); finger grip drag at 25 % moves the box
Verify: `./gradlew :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P11-T07 Final audit and handover
Model: opus high
Implements: R-PERF-*
Read: 12-performance.md#budgets
Do: full perf table in perf.md (release/benchmark build); accessibility pass (TalkBack labels, 44 dp targets); update README "Using Folio" section with a short feature list and how the user installs the release build themselves (never via Claude).
Accept:
- [ ] all budgets met or accepted by the user (USER-CHECK)
- [ ] USER-CHECK list empty
- [ ] FEEDBACK `Open` and STATUS `Deferred` empty: each item done, or accepted by the user as is (USER-CHECK)
Verify: device-tester + `./gradlew qa`
