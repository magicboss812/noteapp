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
Do: single live text field for the focused block (zoom-scaled font size, identical TextStyle to layout), tap-to-caret mapping, block navigation (arrows, Enter splits, Backspace merges), Markdown shortcuts at block start, dimmed markers in the focused block only, selection across blocks (copy/cut/paste/delete as markdown), formatting bar above the keyboard, system stylus handwriting disabled.
Accept:
- [ ] unit: block split/merge commands and undo coalescing
- [ ] device: pen stroke over a focused block produces ink (no system handwriting)
- [ ] device: tap-to-caret p95 <= 50 ms (`text:focus`)
Verify: tests + device-tester

### P06-T07 Math
Model: sonnet xhigh
Implements: R-TXT-02, R-TXT-04
Read: 07-text-engine.md#math, decisions.md#adr-007-latex-rendering
Do: chosen MathRenderer adapter; inline math placeholder scaled to fit; block math spanning integer lines; tap on formula edits source inline; invalid LaTeX shows source in red with error tooltip.
Accept:
- [ ] device: grid invariant holds with inline math in every font (extend T03 suite)
- [ ] screenshot: math corpus page
Verify: instrumented + screenshots

### P06-T08 Tables
Model: sonnet xhigh
Implements: R-TXT-02
Read: 07-text-engine.md#tables
Do: GFM table layout on the grid (row height = max cell lines x U, borders on grid lines), cell editing with Tab/Shift+Tab, add/remove rows/columns from a small toolbar, column alignment.
Accept:
- [ ] unit: table layout math; markdown round trip after edits
- [ ] screenshot: tables on lined and grid templates
Verify: tests + screenshots

### P06-T09 Lists, checklists, code, quotes, links
Model: sonnet xhigh
Implements: R-TXT-02, R-ORG-02, R-MED-04
Read: 07-text-engine.md#block-styles, 09-storage-library.md#links
Do: nested lists with grid-column indentation, tap-to-toggle checkboxes, code blocks (monospace, no highlighting in v1), quotes/callouts, URL links (open externally via intent), `[[` doc link picker inserting `folio://doc/<uuid>` links with titles, broken links styled.
Accept:
- [ ] unit: link resolution through the index; backlinks computed
- [ ] screenshot: all block styles
Verify: tests + screenshots

### P06-T10 Text tool and instant creation
Model: sonnet xhigh
Implements: R-CORE-01, R-TXT-07
Read: 10-editor-ui.md#text-tool, 07-text-engine.md#editing
Do: Text tool tap -> caret in the body flow if inside the body zone, else a free text box snapped to the grid, focused in the same frame; typing on a hardware keyboard with nothing focused appends to the current page body; sticky note tool creates a sticky with focus.
Accept:
- [ ] device: tap-to-caret <= 50 ms p95 for both paths
- [ ] user: "Type a page of notes with the keyboard mixing headings, lists, math. Does text ever jump off the lines or lag?"
Verify: device-tester

### P06-T11 Long text performance
Model: sonnet xhigh
Implements: R-TXT-05, R-PERF-05
Read: 12-performance.md#budgets
Do: seed a 20k-word flow (`seed-text 20000`), measure keystroke-to-frame, scrolling jank, memory; optimize until budgets hold.
Accept:
- [ ] device: keystroke p95 < 16 ms; scroll jank <= 1%; numbers in docs/notes/perf.md
Verify: device-tester
