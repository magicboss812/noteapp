# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P06
next: P06-T09
updated: 2026-10-24

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.
- P03 done 2026-09-30 (tag p03-done): viewport + page stack, templates, tiles (bucketed, base tiles, pan-time requests), wet ink + dry handoff (handoff p95 31 ms), eraser, stylus caps + hover ring; debug 1500 strokes: onTouch p95 0.33 ms, settle p95 119 ms, janky 0.24%, commit 2.02 ms (D-015); REVIEW fixed 1 blocking finding (commits survive a detach); A-017..A-026; qa green.
- P04 done 2026-10-02 (tag p04-done): editor shell with Notewise design system, toolbar (docks, grip, float), tools + options row, shortcuts, undo pill, page management, new-note sheet, autosave dot, recovery snackbar, stored thumbnails, stylus instrumented tests; device checks T05..T09 PASS 2026-10-23 (T09 after fix: CanvasHost collectors on the main dispatcher); REVIEW fixed 1 blocking finding (page loads dirtied the copy via thumbnails); A-027..A-031; qa green; USER-CHECKs answered 2026-10-23 (T10 chrome -> A-037).
- P05 done 2026-10-23 (tag p05-done): library home grid/list, selection + batch ops, nested folders, tags/favorites, FTS search (500 docs < 100 ms), bin with retention purge job, transitions, screenshot suite; REVIEW fixed 2 blocking findings (same-folder move / same-name folder rename added " (2)"); importFolio -> P09-T06, leftovers -> P11-T11; A-032..A-036; qa green; USER-CHECKs answered 2026-10-23.

## Current phase progress
- P06: T12, T13, T01, T02, T03, T04, T05, T06 (a-g), T15, T07 (blocked: device run), T08 (a-b)

## Blocked (needs user; stops dependent tasks)
- P06-T07 device: test APK refused 2026-10-24 (`INSTALL_FAILED_USER_RESTRICTED`, Gradle still SUCCESS, no tests ran). User: keep the tablet unlocked and tap Allow on the "Install via USB" prompt (or turn on "USB debugging (Security settings)"), then the next session runs `bash scripts/device/instrumented.sh :core:text` -> GridInvariantInstrumentedTest 20 fonts, 0 violations, Formulas 136. Robolectric run of the same suite passes.

## USER-CHECK (human verification; non-blocking)
- P06-T07: in a note type `Area $\pi r^2$ and $\frac{a}{b}$`, a `$$` block with `\int_0^1 x^2 dx` and `$\notacommand$`, then tap elsewhere -> formulas sit on the lines, the bad one is red source; tapping it shows its source with an error popup.
- P06-T15: draw several strokes (pen and highlighter) and lift the pen; then focus a text block (debug `seed-text 300`), zoom in and out while editing -> no stroke blinks after pen-up; the edited text scales with the page during the zoom.
- P06-T08: in a note type `| a | b |` + Enter, fill cells with Tab, add/remove rows and columns and set alignment from the table buttons, on lined and grid paper -> borders on rules, header bold, every cell's text on a line, only the edited cell's source shows, the Markdown stays normalized GFM.

## Deferred (id: reason)
- D-001, D-015, D-031 -> P11-T05; D-009, D-010, D-030 -> P11-T06; D-011, D-019 -> P08-T02; D-018 -> P11-T08; D-020 -> P11-T09; D-022 -> P07-T09; D-025 -> P11-T01; D-026 -> P11-T04; D-027 -> P10-T01, P11-T10; D-028 -> P11-T10; D-033 -> P11-T05.
- D-033 P06-T06a: the editor finds a flow's frame pages by loading every page once per open (A-043); a frame-page index in the manifest/index would drop that pass for big PDFs (P11-T05).
- D-014 P03-T09: tilt shading has no off switch (A-025): dry rendering applies tilt whenever stored inputs carry tilt; the P11-T01 setting needs a stored per-stroke choice (or dropping tilt at commit) so wet and dry ink agree; the pen panel gets its tilt row then (P06-T13).
- D-024 P04-T03: highlighter "always straight" is stored and toggled in the highlighter panel, but the canvas does not snap yet; honor it with the highlighter straight-line snap (P07-T02).
- D-034 P06-T07: math blocks are always centered; 07-text-engine.md#math names a left alignment setting (A-047).

## Handoff (<= 5 lines, overwritten each session)
- P06-T08 done: tables laid out, painted and edited cell by cell (`CellField`, `FieldEdit.Cell`, table buttons in `TextFormatRow`); 07-text-engine.md#tables-as-built, A-048. Nothing half-done.
- Text editing map: 07-text-engine.md#live-field-as-built; `TextEditor` -> `EditorSession.editText` -> `EditFlow` + reflow; `LiveTextField` + `TextFieldHost` in the canvas overlay; formatting bar = row 2 (`TextFormatRow`).
- Device: HyperOS steals stylus over a focused editor (A-045). Debug `seed-text n[,page]` seeds a body flow (use `open lined:N` with an unused N). Test APK installs need the user's Allow on the tablet.
- Next: P06-T09 Lists, checklists, code, quotes, links (then T10, T18, T14, T16, T17, T11). Models per A-045. Tool row y 1963 px, mode pill y 171 px in landscape.
