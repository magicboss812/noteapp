# P11 Polish and hardening
Goal: settings, dark UI, motion pass, complete shortcuts, baseline profile, release build, robustness, final performance audit.
Exit: all budgets met in the release build; USER-CHECK list empty; FEEDBACK `Open` and STATUS `Deferred` empty (each converted to a task and done, or accepted by the user); tag `p11-done` and `v1.0.0`.

### P11-T01 Settings
Model: sonnet high
Implements: R-UI-02, R-PAGE-03
Read: 10-editor-ui.md#settings
Do: settings screens for every item in 10-editor-ui.md#settings; DataStore; restore defaults; Toolbar section handedness toggle over the stored `ToolbarDocks.withHandedness` (D-025, A-029).
Accept:
- [ ] screenshot: settings screens
- [ ] unit: settings round trip and defaults
Verify: tests + screenshots

### P11-T02 Dark UI
Model: sonnet high
Implements: R-PAGE-03
Read: 11-design-system.md#colors
Do: dark tokens everywhere; canvas surroundings dark; paper and ink colors unchanged; theme setting (system/light/dark).
Accept:
- [ ] screenshot: every screen dark; page paper remains white in dark mode
Verify: screenshots

### P11-T03 Motion pass
Model: sonnet high
Implements: R-UI-01
Read: 11-design-system.md#motion
Do: audit every transition against the motion spec; add missing ones (tool switch, options row, sheets, page add/delete, selection handles, toolbar docking); nothing blocks input during animation.
Accept:
- [ ] user: "Use the app for 10 minutes. Any animation that feels slow, janky, or missing?"
Verify: device-tester smoke

### P11-T04 Keyboard shortcuts complete
Model: sonnet high
Implements: R-UI-05
Read: 10-editor-ui.md#keyboard-shortcuts
Do: implement the full table (including Alt+9 Ruler toggle); help sheet lists them, with every group in `ShortcutRegistry.DEFAULT_GROUPS` (text, selection, Ctrl+N/O/F/W/S/\, D-026); conflicts with text editing resolved (text field first, then editor).
Accept:
- [ ] unit: registry covers the table
- [ ] device: 5 representative combos via `input.sh combo`
Verify: tests + device-tester

### P11-T05 Baseline profile, benchmark build, release build
Model: sonnet xhigh
Implements: R-PERF-01
Read: 12-performance.md#measurement, .claude/rules/gradle.md
Do: `benchmark` build type and `:benchmark` macrobenchmark module (startup, open note, draw, pan); Baseline Profile generation on the tablet; release build with R8 full mode and shrinkResources; signing from `keystore.properties` if present (user creates the keystore; add a Blocked item with the exact keytool command if missing). The benchmark build needs its own DebugHooksModule (`src/benchmark`, binds NoOpDebugHooks, D-001). Re-measure `ink:commit`; if p95 > 2 ms convert inputs (`StrokeBuilder.inputsOf`, `InkStroke.of`) off main (D-015, A-026).
Accept:
- [ ] device: macrobenchmarks run; cold start <= 700 ms (benchmark build)
- [ ] device: `ink:commit` p95 <= 2 ms in the benchmark build (D-015)
- [ ] release APK builds; size recorded in perf.md
Verify: `instrumented.sh :benchmark connectedBenchmarkAndroidTest`

### P11-T06 Robustness
Model: sonnet xhigh
Implements: R-FILE-01, R-FILE-02
Read: 04-file-format.md#conflicts, #versioning
Do: corrupted file handling (open read-only with message, never crash the library), low storage (pack fails gracefully, working copy kept, banner), external modifications while open, very large docs (500 pages), permission revoked while running, process death during every lifecycle state. Unpack: one fsync pass and a size-ratio zip-bomb bound instead of fsync per entry and a flat 8 GB cap (D-009). Forward compatibility (04#versioning): session packs keep unknown manifest keys (raw JSON), unknown proto fields and unknown object kinds (opaque) (D-010). `LibraryWatcher` event test (D-010).
Accept:
- [ ] unit/instrumented: fault-injection suite green
- [ ] unit: unknown manifest keys, proto fields and object kinds survive open + pack byte-equal; zip bomb rejected; watcher emits create/modify/delete (D-009, D-010)
Verify: `./gradlew qa` + `instrumented.sh :core:storage`

### P11-T08 Dry ink detail at high zoom
Model: sonnet xhigh
Implements: R-INK-01, R-PERF-04
Read: 05-canvas-rendering.md#tiles, 06-ink-input.md#brushes
Do: dry strokes look slightly blocky when zoomed in (user note, D-018): find whether mesh resolution or tile bucket scale causes it; raise detail at high buckets (or a quality setting in P11-T01) within the tile render budget.
Accept:
- [ ] screenshot: one stroke at zoom 1, 4 and max: edges smooth at every zoom (golden)
- [ ] device: tile render p95 within 12-performance.md budget after the change
- [ ] user: "Zoom into handwriting. Do strokes still look blocky?"
Verify: screenshots + device-tester

### P11-T09 Ink and tile hot-path allocations
Model: sonnet xhigh
Implements: R-PERF-03, R-PERF-04
Read: 12-performance.md#budgets, .claude/rules/rendering.md
Do: pan-time `requestTiles` allocates lists, `RectPt`, `VisiblePage` and boxed `Pair` per tile and may log evictions; `DryHandoff.check` allocates and `isDrawn` scans all page objects per pending stroke (D-020). Reuse scratch objects, id-set lookup, no logging on the hot path.
Accept:
- [ ] unit: allocation-free `requestTiles` and `DryHandoff.check` in steady state (allocation counter test)
- [ ] device: pan gfxinfo janky <= 1% with 1500 strokes
Verify: tests + device-tester

### P11-T10 Page management completion
Model: sonnet high
Implements: R-PAGE-01, R-PAGE-02
Read: 10-editor-ui.md#pages
Do: what P04-T06/T08 left out (D-027, D-028): toolbar "More" menu, move page to another document, page buttons on the floating toolbar, the page indicator chip ("3 / 12", 10-editor-ui.md#pages); the page panel shows stored `thumbs/<page>.webp` instead of loading pages through the session LRU (no canvas-page eviction in long docs); thumbs still pending when the editor closes are written before the session closes. Deleting the last page stays refused.
Accept:
- [ ] unit: move page between documents (undoable in the source); pending thumbs flushed on close
- [ ] screenshot: More menu; floating toolbar with page buttons; page indicator chip
- [ ] device: 100-page note, open the page panel and scroll: canvas pages are not reloaded
Verify: tests + screenshots + device-tester

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
