# P06 Text engine
Goal: typed text as a first-class citizen: instant creation, Markdown blocks snapped to the page grid, flows that continue across pages, fonts, LaTeX, tables, links, fast on very long text.
Exit: grid invariant tests pass for 20 fonts and all block types incl. inline math; text budgets met on the tablet; tag `p06-done`.

Order: P06-T12 and P06-T13 (editor chrome redesign, user feedback 2026-10-23, A-037) run first, so the text options and context bars of this phase land on the new chrome.

### P06-T12 Editor chrome without an app bar
Model: opus high
Implements: R-UI-01, R-UI-02, R-UI-04
Read: 10-editor-ui.md#screen-structure, #toolbar, #toolbar-docking, #read-mode; docs/design/notewise/DESIGN.md sections 7 (toolbar pill, rows, editor top pills), 8 (Editor), 9; shot G3-editor-collapsed.jpg only if the text leaves a question open
Do: P04-T10 USER-CHECK feedback (A-037). Remove the opaque top bar: pages fill the window on `bg.canvas` and pass under floating pills: nav pill top-left (Home, Pages, Overview, Add page), edit pill 8 dp below it (Undo, Redo), mode pill top-right (Read mode, Split view, More), bottom-left info pill (page fraction | zoom %, the autosave dot sits next to it). Toolbox = row 2 over row 1, centered, 8 dp apart; new BOTTOM dock (default, 25 dp above the bottom edge, slides above the IME); TOP/LEFT/RIGHT/FLOATING keep their behavior with pill styling, releases near the bottom edge snap to BOTTOM (A-029 had no bottom dock); a stored placement still equal to the old TOP default moves to BOTTOM once. Row 1 ends with a collapse chevron that hides row 2 (animated, chevron flips, persisted). Read mode: chrome slides out with FolioMotion, system bars hide, only an exit pill top-right stays; pen and finger only pan, zoom and tap links (no ink, eraser or lasso); Back or the exit pill leaves it; survives rotation, not reopening. Pills consume input only inside their bounds. Update the editor goldens.
Accept:
- [ ] screenshot: editor landscape + portrait with the bottom dock and no top bar; row 2 collapsed; top, left, right and floating docks; read mode
- [ ] unit: dock model with BOTTOM (snap from a drag release, per-orientation persistence, one-time TOP -> BOTTOM move); read mode routes pen and touch to navigation only
- [ ] device: read mode enter/exit with 1500 strokes: gfxinfo janky <= 1%; a pen stroke in read mode pans and adds no stroke; toolbox and pills stay on screen in portrait
- [ ] user: "Open a note: does the chrome read like Notewise (floating pills, no top bar)? Hide row 2 with the chevron and enter/leave read mode: smooth?"
Verify: `./gradlew :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P06-T13 Tool settings panels and a slim options row
Model: sonnet high
Implements: R-UI-01, R-INK-01, R-INK-02, R-INK-03
Read: 10-editor-ui.md#tool-options; docs/design/notewise/DESIGN.md section 7 (row 2, popover, tool settings panel, slider, switch, segmented buttons); shot G4-pen-settings.jpg only if needed
Do: P04-T10 USER-CHECK feedback (A-037). Row 2 keeps only mode/kind | widths | colors | details (sliders icon) per tool; everything else moves to a 340 dp tool settings panel (centered title + close chip, stroke preview, kind cards, slider block, 44 dp setting rows, colors last, destructive text in `danger`): pen kind, width slider, pressure curve, tilt shading; highlighter always straight; eraser highlighter-only and Clear page; lasso filter chips. Tapping the selected tool again or the details button opens it, anchored per dock (BOTTOM/TOP: next to row 2, rails: flyout, floating: centered), no scrim, one outside tap closes. Width and color popovers still open from the selected width or color. Later tools (shape, text, table) follow the same row/panel split.
Accept:
- [ ] screenshot: settings panel for pen, highlighter, eraser, lasso; slim row 2 per tool; panel anchored for the bottom and a side dock
- [ ] unit: tapping the selected tool toggles its panel; panel changes persist per tool (ToolOptionsStore round trip)
- [ ] user: "Tap the pen twice: does the panel hold the pen settings like Notewise, and does row 2 look lighter?"
Verify: `./gradlew :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug`

### P06-T01 Markdown block model and parser
Model: sonnet high
Implements: R-TXT-01, R-TXT-02
Read: 07-text-engine.md#blocks, #markdown-dialect
Files: core/text/.../markdown/**
Do: split flow markdown into blocks (stable runtime ids), parse each block with commonmark-java + GFM tables/strikethrough/task lists + Folio inline extensions (`==highlight==`, `$math$`, `$$block$$`, `[[doc link]]`), source ranges for styling, plain-text extraction for search.
Accept:
- [ ] unit: parse + serialize round trip is lossless for a 60-case corpus (testdata/format/markdown/)
- [ ] unit: editing one block re-parses only that block
Verify: `./gradlew :core:text:testDebugUnitTest`

### P06-T02 Font system
Model: opus high
Implements: R-TXT-08
Read: 07-text-engine.md#fonts, #font-normalization
Do: `FontRegistry` (20 bundled families + imported .ttf/.otf copied to app files and listed in the library `.fonts/` folder), `FontMetricsCache` (cap height, x-height, ascent, descent per typeface), size derivation per style, fallback chain.
Accept:
- [ ] unit: size derivation hits target cap height within 1% for all bundled fonts (instrumented if Robolectric metrics are unreliable)
Verify: module tests (+ `instrumented.sh :core:text`)

### P06-T03 Grid line-box layout
Model: opus high
Implements: R-TXT-03, R-TXT-05
Read: 07-text-engine.md#line-box, #block-styles, #grid-unit
Do: `BlockLayout` per block (lines, heights in U multiples, baseline correction), cache keyed by (block hash, width, style key, U). Promote the P01-S3 test into a regression suite for all block styles (rendered baselines per zoom, not only pt error x scale), then delete core:text `spike/` and the `spike-fonts` route (A-008, STATUS D-002).
Accept:
- [ ] device: instrumented grid invariant test, 20 fonts x all block styles x zoom 1/2/4: |baseline - rule| <= 0.5 px
Verify: `bash scripts/device/instrumented.sh :core:text`

### P06-T04 Flows and frames
Model: opus high
Implements: R-TXT-07
Read: 07-text-engine.md#flows-and-frames
Do: `FlowLayout` distributing block lines across frames (split at line boundaries; tables at rows; math/rules never split), body frames from template zones, auto-continue creating the next page (same spec) when the last frame overflows, infinite frames, free text boxes (auto-grow in U steps), sticky note frames. Page duplicate (P04-T06 `duplicatePages`) clones text frames and their flows (D-027). Page lookup for search: given a query, return the first page whose flow lines contain a match, and the library opens the note there (D-029, A-033).
Accept:
- [ ] unit: distribution cases (split paragraph, table rows, overflow creates page, deleting text removes empty auto pages only if they have no other objects)
- [ ] unit: a duplicated page keeps its text frames in a new independent flow
- [ ] unit: search page lookup returns the first page with a match (and none for a miss)
Verify: module tests

### P06-T05 Rendering text in tiles
Model: sonnet xhigh
Implements: R-TXT-05
Read: 07-text-engine.md#rendering, 05-canvas-rendering.md#tiles
Do: unfocused blocks drawn by PageRenderer from cached layouts; invalidation limited to changed blocks and shifted lines.
Accept:
- [ ] screenshot: all block styles on lined, grid, dotted templates
Verify: `./gradlew :core:render:verifyRoborazziDebug`

### P06-T06 Focused editing
Model: sonnet xhigh
Implements: R-CORE-01, R-TXT-01
Read: 07-text-engine.md#editing
Files: feature/editor/.../text/**
Do: single live text field for the focused block (zoom-scaled font size, identical TextStyle to layout), tap-to-caret mapping, block navigation (arrows, Enter splits, Backspace merges), Markdown shortcuts at block start, dimmed markers in the focused block only, formatting bar above the keyboard (selection across blocks moved to P06-T14, A-045), system stylus handwriting disabled. Wire the P06-T04 flow engine into the session (D-032, A-041): every flow edit runs `FlowReflow.plan` on the edited document and executes `Batch(edit, plan.command)` as one undo step (flow pages loaded first); `duplicatePages` passes `FlowLayout.markdownOn` as `pageText`; a library search result opens the note at `FlowSearch.firstPage` (query carried in the editor route). Show the text (A-042): build `PageTexts.of(doc, layouter)` on the `text` dispatcher and pass each page's `PageText` to `TileLayer.setPage(ref, body, text)` and to the thumbnail renderer; the focused block's frame skips painting that block while its live field is shown.
Accept:
- [ ] unit: block split/merge commands and undo coalescing
- [ ] unit: typing past the last body frame adds a continuation page in the same undo step; a search query opens the editor on the page of its first match
- [ ] device: pen stroke over a focused block produces ink (no system handwriting)
- [ ] device: tap-to-caret p95 <= 50 ms (`text:focus`)
Verify: tests + device-tester

### P06-T15 USER-CHECK fixes: seamless dry handoff, field zoom
Model: opus high
Implements: R-INK-01, R-TXT-01
Read: 05-canvas-rendering.md#dry-handoff, 07-text-engine.md#live-field-as-built
Do: user feedback 2026-10-23 (P06-T13 and P06-T06 USER-CHECKs). (1) Every finished stroke vanished for a moment after pen-up and came back: the wet copy left one frame after the frame showing the tile (A-005), while androidx.ink hides a removed stroke with the next HWUI frame by itself; remove it in the same run loop as the invalidate instead (A-046). (2) While a block is edited, zooming kept the field at its screen size until editing ended: the field scales as a layer during the zoom and takes its real font size when the viewport settles.
Accept:
- [ ] unit: the wet stroke leaves in the same run loop as the invalidate, only once its tiles are drawn; the field scales with a zoom and is restyled at the settled zoom
- [ ] device: 30 strokes: handoff committed == removed, nothing missing or doubled; zooming while a block is edited keeps the field matched to the page
- [ ] user: "Draw a few strokes and lift the pen: does any stroke still blink? Zoom while typing: does the text follow?"
Verify: `./gradlew :feature:editor:testDebugUnitTest` + device-tester

### P06-T07 Math
Model: opus high
Implements: R-TXT-02, R-TXT-04
Read: 07-text-engine.md#math, decisions.md#adr-007-latex-rendering
Do: chosen MathRenderer adapter; inline math placeholder scaled to fit; block math spanning integer lines; tap on formula edits source inline; invalid LaTeX shows source in red with error tooltip.
Accept:
- [ ] device: grid invariant holds with inline math in every font (extend T03 suite)
- [ ] screenshot: math corpus page
Verify: instrumented + screenshots

### P06-T08 Tables
Model: opus high
Implements: R-TXT-02
Read: 07-text-engine.md#tables
Do: GFM table layout on the grid (row height = max cell lines x U, borders on grid lines), cell editing with Tab/Shift+Tab, add/remove rows/columns from a small toolbar, column alignment.
Accept:
- [ ] unit: table layout math; markdown round trip after edits
- [ ] screenshot: tables on lined and grid templates
Verify: tests + screenshots

### P06-T09 Lists, checklists, code, quotes, links
Model: opus medium
Implements: R-TXT-02, R-ORG-02, R-MED-04
Read: 07-text-engine.md#block-styles, 09-storage-library.md#links
Do: nested lists with grid-column indentation, tap-to-toggle checkboxes, code blocks (monospace, no highlighting in v1), quotes/callouts, URL links (open externally via intent), `[[` doc link picker inserting `folio://doc/<uuid>` links with titles, broken links styled.
Accept:
- [ ] unit: link resolution through the index; backlinks computed
- [ ] screenshot: all block styles
Verify: tests + screenshots

### P06-T10 Text tool and instant creation
Model: opus medium
Implements: R-CORE-01, R-TXT-07
Read: 10-editor-ui.md#text-tool, 07-text-engine.md#editing
Do: Text tool tap -> caret in the body flow if inside the body zone, else a free text box snapped to the grid, focused in the same frame; typing on a hardware keyboard with nothing focused appends to the current page body; sticky note tool creates a sticky with focus. Text tool row 2 + settings panel (P06-T13 split): font family picker grouped as in `FontGroup` (imported fonts listed, "Import font" opens the system picker for .ttf/.otf into `CustomFontStore`; `syncFromLibrary` on app start), size S/M/L plus a free size slider (A-039, box height shown in lines).
Accept:
- [ ] screenshot: text row 2 and text settings panel with the font picker and the size slider
- [ ] device: tap-to-caret <= 50 ms p95 for both paths
- [ ] user: "Type a page of notes with the keyboard mixing headings, lists, math. Does text ever jump off the lines or lag?"
Verify: device-tester

### P06-T18 Seamless focus: no ghost text, block in view, first input kept
Model: opus high
Implements: R-CORE-01, R-TXT-01, R-INK-01
Read: 07-text-engine.md#editing, #live-field-as-built; 05-canvas-rendering.md#tiles; 06-ink-input.md (input router)
Do: device findings 2026-10-23 (P06-T06g, P06-T15 runs). (1) Ghost text: for about 0.5 s after a block gets focus the page tile still draws it under the live field (doubled glyphs where the shown markers shift words). The page must stop drawing the block in the frame the field appears and draw it again in the frame the field leaves, like the dry handoff (A-046): e.g. paint the focused block's runs out of the visible tiles synchronously, or keep the field's text hidden until the tiles without the block are drawn, then show both in one frame; measure focus-to-clean time. (2) A focused block (and the caret line while typing) scrolls into view above the keyboard and the toolbox, which cover it today. (3) The first input after opening a note is dropped: the first finger tap (3 of 3 runs, no `text:focus` sample) and the first stylus stroke of a run (`ink:started` one short); find the cause (window or view focus, router state, first-event palm rule) and fix it with a failing test first.
Accept:
- [ ] unit: focus and blur switch the page's block and the field in the same frame (no frame with both or neither); the first event after attach reaches the router
- [ ] device: 20 focus/blur cycles: no frame with doubled text (frame dump or per-frame check), focus to clean frame <= 50 ms; focusing a block in the lower half with the soft keyboard keeps the caret line visible above the toolbox; after `open`, the first tap focuses and the first stroke draws
- [ ] user: "Tap into text and out again a few times, also low on the page: does anything flash or hide behind the keyboard?"
Verify: `./gradlew :feature:editor:testDebugUnitTest` + device-tester

### P06-T14 Selection across blocks
Model: opus high
Implements: R-TXT-01
Read: 07-text-engine.md#editing, #block-edit-logic-as-built
Do: split out of P06-T06 (A-045). A flow selection (`TextSelection` of two `Caret`s) that spans blocks: Shift+arrows past the field's first or last line or its ends, Ctrl+A (block, then flow), and drag handles at both ends (finger). Selected blocks outside the live field are highlighted by the page (selection rects from `FlowLayout` per line, drawn in the canvas overlay, not in tiles); the field shows its part. Copy/cut/paste/delete as Markdown through `BlockEditing.copy/delete/insert`, plain text fallback for foreign paste; typing replaces the selection; formatting bar inline actions apply per block where `Formatting` allows.
Accept:
- [ ] unit: Shift+Down from the last line extends into the next block; cut of a 3-block selection is one undo step and the clipboard holds its Markdown; paste of multi-block Markdown splits into blocks
- [ ] screenshot: a selection over three blocks with handles
- [ ] device: drag handles across a page boundary; copy/paste into another note keeps headings and lists
Verify: `./gradlew :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P06-T16 Baseline lift: text sits a chosen distance above the rules
Model: opus high
Implements: R-TXT-03, R-TXT-05
Read: 07-text-engine.md#line-box, #grid-unit, #fonts; 04-file-format.md (flow style file)
Do: user feedback 2026-10-23 (P06-T06 USER-CHECK): "text is perfectly above lines, tho I wish to change how much above the lines". A per-flow `baselineLiftUnits` (0 .. 0.35 U, default 0, steps of 0.05 U) raises every baseline by that fraction of U; line boxes stay integer multiples of U, so the grid stays intact (every baseline at rule - lift). Flow style file gains the field (absent = 0, no migration needed beyond the default). Text settings panel (P06-T10) gets a "Distance above lines" slider with a live preview; the live field and tiles use the same lift. Update the CLAUDE.md grid invariant, the text-engine rule and 07-text-engine.md#line-box to "baseline on a grid line, raised by the flow's lift" in the same commit (A-entry).
Accept:
- [ ] unit: invariant suite with lift 0, 0.15 and 0.35 U: |baseline - (rule - lift x U)| <= 0.5 px, box heights unchanged; flow style round trip with and without the field
- [ ] screenshot: the same paragraph at lift 0 and 0.25 U on lined paper; text settings panel with the slider
- [ ] user: "Set the distance above lines in the text settings: does the text sit where you want it, also while typing?"
Verify: `./gradlew :core:text:testDebugUnitTest :core:format:test :feature:editor:verifyRoborazziDebug`

### P06-T17 Free text boxes: move and resize on the grid
Model: opus medium
Implements: R-TXT-07, R-CORE-01
Read: 07-text-engine.md#flows-and-frames, 10-editor-ui.md#text-tool; shot G4-text-editing.jpg (box with a top grip and side handles)
Do: user feedback 2026-10-23 (P06-T06 USER-CHECK): text blocks that can be moved anywhere with the same strict spacing. A focused free text box (FrameRole.BOX, created by P06-T10) shows a top grip and left/right width handles like Notewise. Dragging the grip (finger or pen) moves the box; on release its top snaps to a rule and its left edge to a grid column (lined paper: to 1 mm), so every line stays on the grid. Width handles resize in grid columns and the box reflows (height grows in U steps). One undo step per move or resize. Body text stays in the body flow (no dragging single paragraphs out of it); the lasso moves boxes with the same snapping (P07-T03).
Accept:
- [ ] unit: move snaps top to a rule and left to a column; resize reflows and keeps line boxes on the grid; one undo step each
- [ ] screenshot: focused box with grip and handles
- [ ] device: drag a box across the page and onto the next page; baselines on rules after release
Verify: `./gradlew :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P06-T11 Long text performance
Model: opus high
Implements: R-TXT-05, R-PERF-05
Read: 12-performance.md#budgets
Do: seed a 20k-word flow (`seed-text 20000`), measure keystroke-to-frame, scrolling jank, memory; optimize until budgets hold.
Accept:
- [ ] device: keystroke p95 < 16 ms; scroll jank <= 1%; numbers in docs/notes/perf.md
Verify: device-tester
