# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P04
next: P04 REVIEW
updated: 2026-10-02

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.
- P03 done 2026-09-30 (tag p03-done): viewport + page stack, templates, tiles (bucketed, base tiles, pan-time requests), wet ink + dry handoff (handoff p95 31 ms), eraser, stylus caps + hover ring; debug 1500 strokes: onTouch p95 0.33 ms, settle p95 119 ms, janky 0.24%, commit 2.02 ms (D-015); REVIEW fixed 1 blocking finding (commits survive a detach); A-017..A-026; qa green.

## Current phase progress
- P04: T01, T02, T03, T04, T10, T05 (device check pending), T06 (device check pending), T07 (device check pending), T08 (device check pending), T09 (device check pending); REVIEW: reviewer done, fixes committed, intake done

## Blocked (needs user; stops dependent tasks)
- P02-T06: storage onboarding never seen on the tablet (you revoked All files access): device-tester confirms onboarding shows at the next device session. Blocks nothing else.
- P04-T05 device check not run (no tablet: connect.sh found 0 devices): connect the Pad 7 (Wireless debugging on, unlocked), then have device-tester run: Ctrl+Z undoes the last stroke (`input.sh combo CTRL_LEFT Z`), Ctrl+Y redoes, Alt+3 eraser / Alt+1 pen, Ctrl+/ help sheet + Esc, PageDown/PageUp, Ctrl+= / Ctrl+0 zoom. Blocks nothing else.
- P04-T06 device check not run (same: 0 devices): device-tester opens a 4+ page note, `debugcmd.sh reorder-page 2,1`, reads `pageOrder` in `state`, reopens the doc and checks the order held; smoke: panel, overview, add, duplicate, delete + Ctrl+Z, settings sheet apply. Blocks nothing else.
- P04-T07 device check not run (0 devices): device-tester taps "New note" on the library, edits the title (or keeps it), taps Create; the editor opens and the `.folio` appears in Folio-Debug (`scripts/device` file listing); reopen the sheet and check the last choices are the defaults. Blocks nothing else.
- P04-T09 device check not run (0 devices): connect the Pad 7, then `bash scripts/device/instrumented.sh :feature:editor` (device-tester): StylusEditingTest 2/2 (5 strokes, undo 2, redo 1, reopen = 4; eraser sweep + undo). Test APK install may need HyperOS "USB debugging (Security settings)". Blocks nothing else.
- P04-T08 device check not run (0 devices): device-tester draws strokes, `stop.sh` before the 30 s pack, relaunches: library shows "Recovered unsaved changes." and the note has every stroke; save dot bottom-left is green when idle; scrolling a long note without editing must not trigger the snackbar after a force-quit. Blocks nothing else.
- P04-REVIEW (D-005, D-016): rule edits were denied headless; apply interactively. `.claude/rules/text-engine.md` line 9 -> "- Fixed line box via `TextStyle(lineHeight, LineHeightStyle(Alignment.Bottom, Trim.None))` laid out at the grid-pitch reference scale r = round(4*U)/U px per pt, then shifted by the measured `k*U*r - firstLineBaseline` (A-006). Never rely on font ascent/descent for placement." `.claude/rules/rendering.md` line 5 -> "- During pan/zoom gestures transform existing tiles; only newly uncovered tiles at the current bucket are requested (throttled). Re-render at the new bucket after 100 ms idle: visible tiles first, center-out. Base tiles (low bucket) of visible pages are never evicted by the budget (A-026)." Blocks nothing.

## USER-CHECK (human verification; non-blocking)
- P04-T10: open the library placeholder and a note on the tablet -> toolbar, options row, popovers, colors and type read as Notewise; differences go to docs/plan/FEEDBACK.md.
- P04-T06: toolbar Pages (drawer), Overview (grid) and Add page buttons; long-press and drag a panel row to reorder; row menu, page settings -> panels and sheet read well, order survives reopening.
- P04-T07: library "New note" -> sheet with title, paper size/orientation, fixed/infinite, template gallery previews, spacing, paper color; Create opens the note; the next sheet starts with your last choices.
- P04-T08: draw, then watch the small dot at the editor's bottom-left (green idle, orange while saving); force-quit mid-edit and reopen -> "Recovered unsaved changes." snackbar on the library.

## Deferred (id: reason)
- D-001, D-015 -> P11-T05; D-009, D-010 -> P11-T06; D-011, D-019 -> P08-T02; D-018 -> P11-T08; D-020 -> P11-T09; D-022 -> P07-T09; D-025 -> P11-T01; D-026 -> P11-T04; D-027 -> P06-T04, P07-T05, P10-T01, P11-T10; D-028 -> P05-T02, P11-T10; D-005, D-016 -> Blocked P04-REVIEW.
- D-002 P01-T08: spike route spike-fonts + core:text spike/ (remove in P06-T03; spike-ink/spike-stylus went in P03-T09); USER-CHECKs answered (A-008).
- D-004 P01-REVIEW: `GridParagraph.draw` allocates a Compose canvas wrapper per call; cache it or mark HOT PATH before P06-T05 paints text into tiles.
- D-006 P01-S3 (user feedback): decide BACKLOG B-12 (free font size tied to the rules, top/middle/bottom placement in the line box) in P06-T02 before building size styles.
- D-007 P02-REVIEW: repositories do not refuse open documents (favorite/rename/move/delete of an open doc); add an open-documents check with a typed error in P05-T01.
- D-014 P03-T09: tilt shading has no off switch (A-025): dry rendering applies tilt whenever stored inputs carry tilt; the P11-T01 setting needs a stored per-stroke choice (or dropping tilt at commit) so wet and dry ink agree.
- D-024 P04-T03: highlighter "always straight" is stored and toggled in the options row, but the canvas does not snap yet; honor it with the highlighter straight-line snap (P07-T02).

## Handoff (<= 5 lines, overwritten each session)
- P04 REVIEW: reviewer found 1 blocking (page loads dirtied the copy via thumbnails; fixed in dd40694) plus non-blocking fixes; intake of D-items and user feedback into P05..P11 tasks done.
- Next: full `./gradlew qa`, tag p04-done, collapse P04, next P05-T01.
- FEEDBACK.md turns into tasks at every REVIEW. `FolioIcons.kt` is generated (A-027): `./gradlew :tools:icongen:run`.
