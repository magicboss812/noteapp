# P11 Polish and hardening
Goal: settings, dark UI, motion pass, complete shortcuts, baseline profile, release build, robustness, final performance audit.
Exit: all budgets met in the release build; USER-CHECK list empty; tag `p11-done` and `v1.0.0`.

### P11-T01 Settings
Model: sonnet high
Implements: R-UI-02, R-PAGE-03
Read: 10-editor-ui.md#settings
Do: settings screens for every item in 10-editor-ui.md#settings; DataStore; restore defaults.
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
Do: implement the full table; help sheet lists them; conflicts with text editing resolved (text field first, then editor).
Accept:
- [ ] unit: registry covers the table
- [ ] device: 5 representative combos via `input.sh combo`
Verify: tests + device-tester

### P11-T05 Baseline profile, benchmark build, release build
Model: sonnet xhigh
Implements: R-PERF-01
Read: 12-performance.md#measurement, .claude/rules/gradle.md
Do: `benchmark` build type and `:benchmark` macrobenchmark module (startup, open note, draw, pan); Baseline Profile generation on the tablet; release build with R8 full mode and shrinkResources; signing from `keystore.properties` if present (user creates the keystore; add a Blocked item with the exact keytool command if missing).
Accept:
- [ ] device: macrobenchmarks run; cold start <= 700 ms (benchmark build)
- [ ] release APK builds; size recorded in perf.md
Verify: `instrumented.sh :benchmark connectedBenchmarkAndroidTest`

### P11-T06 Robustness
Model: sonnet xhigh
Implements: R-FILE-01, R-FILE-02
Read: 04-file-format.md#conflicts, #versioning
Do: corrupted file handling (open read-only with message, never crash the library), low storage (pack fails gracefully, working copy kept, banner), external modifications while open, very large docs (500 pages), permission revoked while running, process death during every lifecycle state.
Accept:
- [ ] unit/instrumented: fault-injection suite green
Verify: `./gradlew qa` + `instrumented.sh :core:storage`

### P11-T07 Final audit and handover
Model: opus high
Implements: R-PERF-*
Read: 12-performance.md#budgets
Do: full perf table in perf.md (release/benchmark build); accessibility pass (TalkBack labels, 44 dp targets); update README "Using Folio" section with a short feature list and how the user installs the release build themselves (never via Claude).
Accept:
- [ ] all budgets met or accepted by the user (USER-CHECK)
- [ ] USER-CHECK list empty
Verify: device-tester + `./gradlew qa`
