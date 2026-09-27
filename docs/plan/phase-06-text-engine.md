# P06 Text engine
Goal: typed text as a first-class citizen: instant creation, Markdown blocks snapped to the page grid, flows that continue across pages, fonts, LaTeX, tables, links, fast on very long text.
Exit: grid invariant tests pass for 20 fonts and all block types incl. inline math; text budgets met on the tablet; tag `p06-done`.

### P06-T01 Markdown block model and parser
Implements: R-TXT-01, R-TXT-02
Read: 07-text-engine.md#blocks, #markdown-dialect
Files: core/text/.../markdown/**
Do: split flow markdown into blocks (stable runtime ids), parse each block with commonmark-java + GFM tables/strikethrough/task lists + Folio inline extensions (`==highlight==`, `$math$`, `$$block$$`, `[[doc link]]`), source ranges for styling, plain-text extraction for search.
Accept:
- [ ] unit: parse + serialize round trip is lossless for a 60-case corpus (testdata/format/markdown/)
- [ ] unit: editing one block re-parses only that block
Verify: `./gradlew :core:text:testDebugUnitTest`

### P06-T02 Font system
Implements: R-TXT-08
Read: 07-text-engine.md#fonts, #font-normalization
Do: `FontRegistry` (20 bundled families + imported .ttf/.otf copied to app files and listed in the library `.fonts/` folder), `FontMetricsCache` (cap height, x-height, ascent, descent per typeface), size derivation per style, fallback chain.
Accept:
- [ ] unit: size derivation hits target cap height within 1% for all bundled fonts (instrumented if Robolectric metrics are unreliable)
Verify: module tests (+ `instrumented.sh :core:text`)

### P06-T03 Grid line-box layout
Implements: R-TXT-03, R-TXT-05
Read: 07-text-engine.md#line-box, #block-styles, #grid-unit
Do: `BlockLayout` per block (lines, heights in U multiples, baseline correction), cache keyed by (block hash, width, style key, U). Promote the P01-S3 test into a regression suite for all block styles.
Accept:
- [ ] device: instrumented grid invariant test, 20 fonts x all block styles x zoom 1/2/4: |baseline - rule| <= 0.5 px
Verify: `bash scripts/device/instrumented.sh :core:text`

### P06-T04 Flows and frames
Implements: R-TXT-07
Read: 07-text-engine.md#flows-and-frames
Do: `FlowLayout` distributing block lines across frames (split at line boundaries; tables at rows; math/rules never split), body frames from template zones, auto-continue creating the next page (same spec) when the last frame overflows, infinite frames, free text boxes (auto-grow in U steps), sticky note frames.
Accept:
- [ ] unit: distribution cases (split paragraph, table rows, overflow creates page, deleting text removes empty auto pages only if they have no other objects)
Verify: module tests

### P06-T05 Rendering text in tiles
Implements: R-TXT-05
Read: 07-text-engine.md#rendering, 05-canvas-rendering.md#tiles
Do: unfocused blocks drawn by PageRenderer from cached layouts; invalidation limited to changed blocks and shifted lines.
Accept:
- [ ] screenshot: all block styles on lined, grid, dotted templates
Verify: `./gradlew :core:render:verifyRoborazziDebug`

### P06-T06 Focused editing
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
Implements: R-TXT-02, R-TXT-04
Read: 07-text-engine.md#math, decisions.md#adr-007-latex-rendering
Do: chosen MathRenderer adapter; inline math placeholder scaled to fit; block math spanning integer lines; tap on formula edits source inline; invalid LaTeX shows source in red with error tooltip.
Accept:
- [ ] device: grid invariant holds with inline math in every font (extend T03 suite)
- [ ] screenshot: math corpus page
Verify: instrumented + screenshots

### P06-T08 Tables
Implements: R-TXT-02
Read: 07-text-engine.md#tables
Do: GFM table layout on the grid (row height = max cell lines x U, borders on grid lines), cell editing with Tab/Shift+Tab, add/remove rows/columns from a small toolbar, column alignment.
Accept:
- [ ] unit: table layout math; markdown round trip after edits
- [ ] screenshot: tables on lined and grid templates
Verify: tests + screenshots

### P06-T09 Lists, checklists, code, quotes, links
Implements: R-TXT-02, R-ORG-02, R-MED-04
Read: 07-text-engine.md#block-styles, 09-storage-library.md#links
Do: nested lists with grid-column indentation, tap-to-toggle checkboxes, code blocks (monospace, no highlighting in v1), quotes/callouts, URL links (open externally via intent), `[[` doc link picker inserting `folio://doc/<uuid>` links with titles, broken links styled.
Accept:
- [ ] unit: link resolution through the index; backlinks computed
- [ ] screenshot: all block styles
Verify: tests + screenshots

### P06-T10 Text tool and instant creation
Implements: R-CORE-01, R-TXT-07
Read: 10-editor-ui.md#text-tool, 07-text-engine.md#editing
Do: Text tool tap -> caret in the body flow if inside the body zone, else a free text box snapped to the grid, focused in the same frame; typing on a hardware keyboard with nothing focused appends to the current page body; sticky note tool creates a sticky with focus.
Accept:
- [ ] device: tap-to-caret <= 50 ms p95 for both paths
- [ ] user: "Type a page of notes with the keyboard mixing headings, lists, math. Does text ever jump off the lines or lag?"
Verify: device-tester

### P06-T11 Long text performance
Implements: R-TXT-05, R-PERF-05
Read: 12-performance.md#budgets
Do: seed a 20k-word flow (`seed-text 20000`), measure keystroke-to-frame, scrolling jank, memory; optimize until budgets hold.
Accept:
- [ ] device: keystroke p95 < 16 ms; scroll jank <= 1%; numbers in docs/notes/perf.md
Verify: device-tester
