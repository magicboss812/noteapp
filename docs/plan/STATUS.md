# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P05
next: P05-T03
updated: 2026-10-23

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.
- P03 done 2026-09-30 (tag p03-done): viewport + page stack, templates, tiles (bucketed, base tiles, pan-time requests), wet ink + dry handoff (handoff p95 31 ms), eraser, stylus caps + hover ring; debug 1500 strokes: onTouch p95 0.33 ms, settle p95 119 ms, janky 0.24%, commit 2.02 ms (D-015); REVIEW fixed 1 blocking finding (commits survive a detach); A-017..A-026; qa green.
- P04 done 2026-10-02 (tag p04-done): editor shell with Notewise design system, toolbar (docks, grip, float), tools + options row, shortcuts, undo pill, page management, new-note sheet, autosave dot, recovery snackbar, stored thumbnails, stylus instrumented tests; device checks T05..T09 PASS 2026-10-23 (T09 after fix: CanvasHost collectors on the main dispatcher); REVIEW fixed 1 blocking finding (page loads dirtied the copy via thumbnails); A-027..A-031; qa green.

## Current phase progress
- P05: T01, T02

## Blocked (needs user; stops dependent tasks)
- (none)

## USER-CHECK (human verification; non-blocking)
- P05-T02: library home -> long-press a card to select several, use Move/Favorite/Delete in the bar; card menu Rename/Move/Duplicate/Favorite/Delete; with a note open in the editor its menu actions show "is open in the editor" -> covers (after a first save) and text excerpts read well.
- P04-T10: open the library placeholder and a note on the tablet -> toolbar, options row, popovers, colors and type read as Notewise; differences go to docs/plan/FEEDBACK.md.
- P04-T06: toolbar Pages (drawer), Overview (grid) and Add page buttons; long-press and drag a panel row to reorder; row menu, page settings -> panels and sheet read well, order survives reopening.
- P04-T07: library "New note" -> sheet with title, paper size/orientation, fixed/infinite, template gallery previews, spacing, paper color; Create opens the note; the next sheet starts with your last choices.
- P04-T08: draw, then watch the small dot at the editor's bottom-left (green idle, orange while saving); force-quit mid-edit and reopen -> "Recovered unsaved changes." snackbar on the library.

## Deferred (id: reason)
- D-001, D-015 -> P11-T05; D-009, D-010 -> P11-T06; D-011, D-019 -> P08-T02; D-018 -> P11-T08; D-020 -> P11-T09; D-022 -> P07-T09; D-025 -> P11-T01; D-026 -> P11-T04; D-027 -> P06-T04, P07-T05, P10-T01, P11-T10; D-028 -> P11-T10.
- D-002 P01-T08: spike route spike-fonts + core:text spike/ (remove in P06-T03; spike-ink/spike-stylus went in P03-T09); USER-CHECKs answered (A-008).
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-006 P01-S3 (user feedback): decide BACKLOG B-12 (free font size tied to the rules, top/middle/bottom placement in the line box) in P06-T02 before building size styles.
- D-014 P03-T09: tilt shading has no off switch (A-025): dry rendering applies tilt whenever stored inputs carry tilt; the P11-T01 setting needs a stored per-stroke choice (or dropping tilt at commit) so wet and dry ink agree.
- D-024 P04-T03: highlighter "always straight" is stored and toggled in the options row, but the canvas does not snap yet; honor it with the highlighter straight-line snap (P07-T02).

## Handoff (<= 5 lines, overwritten each session)
- P05-T02 done: NoteCard previews (stored cover, else excerpt), overflow menu, multi-select bar (move/favorite/delete), FolderCard sheet fix; D-007 guard (`OpenDocuments`, typed `DocumentOpenException`) in core:storage, also covers folder rename/move/delete.
- Menu Tags and Export rows are disabled placeholders (T04 tag editor, P09 export). FolderCard is not on the home grid yet (T03); its golden is in the component catalog and NoteCards_mixedRow.
- Next: P05-T03 (folders). FEEDBACK Open: autosave dot above panels (converted at REVIEW). `FolioIcons.kt` is generated (A-027).
