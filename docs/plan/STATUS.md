# STATUS
<!-- Maintained by Claude. <= 60 lines (hook-enforced). Format: docs/plan/PLAN.md "STATUS format". -->
phase: P07
next: P07-T01
updated: 2026-11-08

## Completed
- P00 done 2026-09-27 (tag p00-done): AGP 9.4.1/Kotlin 2.4.20/Gradle 9.8.0, compileSdk 37.2, minSdk 35 (device SDK 36); qa green; instrumented smoke 1/1 on Pad 7; cold launch 786 ms; A-001..A-004.
- P01 done 2026-09-28 (tag p01-done): ADR-002..008 Accepted: ink front buffer (onTouch p95 0.28 ms), bitmap tiles (jank 0.49%, p95 gap -> P03-T10), library IO (46 MB pack 1.4 s), PdfBox merge (100 pp 0.24 s), jlatexmath (p95 0.8 ms), grid-pitch text (0.000 px, 20 fonts); A-005..A-008; qa green.
- P02 done 2026-09-29 (tag p02-done): model + commands (200-command undo property test), folio.v1 codec (1000x120 inputs 709 KB), container + goldens, crash-safe packer (100 injected failures), recovery, Room index (500 docs 149 ms), repositories, sessions (LRU 30); REVIEW fixed 6 blocking findings; A-009..A-016; qa green.
- P03 done 2026-09-30 (tag p03-done): viewport + page stack, templates, tiles (bucketed, base tiles, pan-time requests), wet ink + dry handoff (handoff p95 31 ms), eraser, stylus caps + hover ring; debug 1500 strokes: onTouch p95 0.33 ms, settle p95 119 ms, janky 0.24%, commit 2.02 ms (D-015); REVIEW fixed 1 blocking finding (commits survive a detach); A-017..A-026; qa green.
- P04 done 2026-10-02 (tag p04-done): editor shell with Notewise design system, toolbar (docks, grip, float), tools + options row, shortcuts, undo pill, page management, new-note sheet, autosave dot, recovery snackbar, stored thumbnails, stylus instrumented tests; device checks T05..T09 PASS 2026-10-23 (T09 after fix: CanvasHost collectors on the main dispatcher); REVIEW fixed 1 blocking finding (page loads dirtied the copy via thumbnails); A-027..A-031; qa green; USER-CHECKs answered 2026-10-23 (T10 chrome -> A-037).
- P05 done 2026-10-23 (tag p05-done): library home grid/list, selection + batch ops, nested folders, tags/favorites, FTS search (500 docs < 100 ms), bin with retention purge job, transitions, screenshot suite; REVIEW fixed 2 blocking findings (same-folder move / same-name folder rename added " (2)"); importFolio -> P09-T06, leftovers -> P11-T11; A-032..A-036; qa green; USER-CHECKs answered 2026-10-23.
- P06 done 2026-11-08 (tag p06-done): Markdown blocks, fonts + import, grid line boxes (20 fonts 0 violations on device), flows + reflow, live field, math, tables, links/tasks, selection + clipboard, baseline lift, free text boxes; `text:key` p95 17-21 ms debug (D-043); REVIEW fixed 2 blocking findings (stale flow edits after undo, read mode kept text focus); intake -> P11-T01/T09/T12, P07-T02; A-037..A-056; qa green.

## Current phase progress
- P07: (none yet)

## Blocked (needs user; stops dependent tasks)
- D-040 (P06-T16, .claude edits denied headless): in `.claude/rules/text-engine.md` line 7 replace "each baseline lies on the rule at the bottom of its line box: baselineY = frame.topPt + sum of line-box heights up to and including that line." with "each baseline lies on the rule at the bottom of its line box, raised by the flow's lift (A-054): baselineY = frame.topPt + sum of line-box heights up to and including that line - baselineLiftUnits * U." (or let an interactive session do it).
- P06 REVIEW (.claude edits denied headless): in `.claude/rules/ink-and-input.md` replace "-> after the frame that shows it, `removeFinishedStrokes(ids)`. Never remove earlier." with "-> once the tiles under the stroke are fresh, invalidate and call `removeFinishedStrokes(ids)` in the same UI-thread run loop (androidx.ink contract, A-046). Never remove before the tiles are drawn."

## USER-CHECK (human verification; non-blocking)
- 2026-11-07 P06-T07, T15, T08, T09 run on the tablet by device-tester: T07 PASS, T15 PASS (1-frame blink not provable without screen recording), T08 FAIL (header not bold, remove column off by one, trailing cell space dropped), T09 FAIL (stylus tap in read mode inert); defects -> FEEDBACK Open, fixed in T19. T07 instrumented run on the tablet after T19 (abd5e92): GridInvariantInstrumentedTest 20 fonts, 0 violations, formulas as expected; SizeDerivationInstrumentedTest PASS.
- P06-T10: Type a page of notes with the keyboard mixing headings, lists, math -> text never jumps off the lines or lags; also pick a font and a free size in the Text panel (new text takes them) and try Import font with a .ttf.
- P06-T18: Tap into text and out again a few times, also low on the page -> nothing flashes or hides behind the keyboard.
- P06-T14: Select across blocks with Shift+arrows and the finger handles (also over a page break), copy, paste into another note -> headings and lists kept, highlight follows; device-tester PASS 2026-11-08.
- P06-T16: Set the distance above lines in the Text panel, then type new text -> it sits where you want above the rules, also while typing (it applies to text you create next; existing text keeps its distance).
- P06-T17: Make a text box in the margin with the Text tool, drag its top grip with a finger and with the pen, pull a side knob -> box lands on the lines (also on the next page), text rewraps, one undo each; device-tester 2026-11-08: finger move/next page/resize/undo PASS, pen grip FAIL (D-041).

## Deferred (id: reason)
- D-001, D-015, D-031 -> P11-T05; D-009, D-010, D-030 -> P11-T06; D-011, D-019 -> P08-T02; D-018 -> P11-T08; D-020 -> P11-T09; D-022 -> P07-T09; D-025 -> P11-T01; D-026 -> P11-T04; D-027 -> P10-T01, P11-T10; D-028 -> P11-T10; D-033 -> P11-T05; D-035 -> P11-T01; D-037 -> P11-T05; D-038 -> P11-T05; D-039 -> P11-T06; D-043 -> P11-T05; D-014, D-034 -> P11-T01; D-024 -> P07-T02; D-041, D-042 -> P11-T12.
- D-038 P06-T14b: a focused live field draws about 2 frames/s while idle (EditText caret blink); since P06-T11 25-36 ms each, layout 0-1 ms, GPU p50 18 ms; 0 frames when not editing.
- D-043 P06-T11: debug build, 20k-word note: `text:key` p95 16.8-21.0 ms paced (was 37.4), budget < 16 ms; the rest is the frame pipeline (about 18 ms GPU per light frame), not main-thread text work (A-056).
- D-039 P06-T14b: after a model-driven text change (Ctrl+Z of a replace) Gboard's suggestion strip keeps the old word; the field may need `restartInput` then.
- D-035 P06-T10a: "Type into page body" is always on; the switch comes with the settings screen (A-050).
- D-037 P06-T18d: the first Text tool creation after an open takes 68-83 ms tap to caret (fresh process 207-264 ms; first focus 57.5 ms), first-run cost of the debug build; later ones meet 50 ms (A-052).
- D-033 P06-T06a: the editor finds a flow's frame pages by loading every page once per open (A-043); a frame-page index in the manifest/index would drop that pass for big PDFs (P11-T05).

## Handoff (<= 5 lines, overwritten each session)
- P06 closed (tag p06-done). Block edits carry their base flow text (`EditFlow.base`) and are refused once an undo or another edit changed the flow; entering read mode blurs the text, grip/selection handles are inert in NAVIGATE.
- NEXT P07-T01 (shape tool and shape objects). Read the P07 header once. Untracked orphan `core/text/src/test/screenshots/FontSpecimen_compact.png` (test removed in P06-T03) left unstaged: stage explicit paths, not `git add -A`.
- Text editing map: 07-text-engine.md#live-field-as-built; `TextEditor` -> `EditorSession.editText` -> `EditFlow` + reflow; `LiveTextField` + `TextFieldHost` in the canvas overlay.
- Device: HyperOS steals stylus over a focused editor (A-045). Debug `seed-text n[,page]`, `open lined:N`; the first stylus tap after a debug command is dropped. Test APK installs can be refused (INSTALL_FAILED_USER_RESTRICTED): check the result XML. Tool row y 1963 px, mode pill y 171 px in landscape.
- Two Blocked lines are .claude rule edits only (no task waits on them).
