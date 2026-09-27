# 07 Text engine
Typed text is Markdown, laid out on the page grid so that every line sits on the template's rules, for every font, with inline LaTeX, at every zoom level. Module `core:text` (layout, parsing, fonts, math) + `feature:editor` (editing UI).

## Grid unit
- U (points) comes from the page template (05-canvas-rendering.md#templates): rule spacing, grid cell, dot spacing, or the blank/custom default.
- Grid origin: y0 = template `marginTopPt`; rule lines at y0 + n*U. Horizontal grid columns (grid/dotted/graph templates) at x0 + m*U; lined templates have no column snapping except the margin line.
- `GridUnit.of(template)` is the single source for U and the grid origin. Changing the template spacing re-flows text on that page (line counts change, content never overlaps).

## Line box
- Every text line occupies a line box of height k*U (k = 1 for body text, 2 for H1/H2). Line boxes are stacked without gaps; paragraph gaps are whole empty lines.
- The baseline of a line is the rule at the bottom of its line box. Descenders extend below the rule into the next box (like handwriting on ruled paper).
- Implementation: Compose text with `TextStyle(lineHeight = k*U, lineHeightStyle = LineHeightStyle(Alignment.Bottom, Trim.None))`. The renderer shifts the block by `baselineCorrection = k*U*r - firstLineBaseline`, measured from the layout result (in reference px), so baselines land exactly on rules. No placement decision uses the font's ascent or descent (A-006: a descent-based shift missed rules by up to 10 px).
- Layout happens at a fixed reference scale r = round(4*U)/U px per pt (about 4; chosen so one grid unit is a whole number of px, because Compose rounds line heights up to whole px and every non-integer pitch drifts per line) with linear (unhinted, subpixel) glyph metrics, so line breaks do not change with zoom. Drawing scales the canvas by scale / r.
- Invariant (tested): for every line, |baselineY - (y0 + n*U)| <= 0.5 px at zoom 1, 2, 4, and every line box height is an integer multiple of U.

## Font normalization
- Fonts differ in em-box proportions. Size is therefore derived, not chosen in sp: `sizePx = targetCapHeight / capRatio(typeface)` where `capRatio` = cap height ("H" bounds) / size, measured once per typeface and cached in `FontMetricsCache`.
- Target cap heights (fraction of U): body S 0.38, M 0.45 (default), L 0.52; H1 0.95 (2U box); H2 0.75 (2U box); H3 0.55 bold (1U box); code 0.40 (monospace); table cells = body; sticky notes = body.
- The user picks S/M/L per flow and a font family per flow. Mixed fonts inside a flow: code spans use the monospace family, math uses the math renderer; all share the line box.

## Fonts
Bundled (OFL, downloaded from github.com/google/fonts in P01-S3, licenses shipped in assets/licenses): regular, italic, bold, bold italic where available; variable fonts where Google publishes them.
| Group | Families |
|---|---|
| Sans | Inter, IBM Plex Sans, Source Sans 3, Nunito, Lexend, Atkinson Hyperlegible Next (or Atkinson Hyperlegible), Noto Sans |
| Serif | Source Serif 4, Lora, Merriweather, EB Garamond, Crimson Pro, Literata |
| Mono | JetBrains Mono, IBM Plex Mono |
| Handwriting | Caveat, Patrick Hand, Kalam, Architects Daughter, Shadows Into Light |
Default text font: Inter. Custom fonts: import `.ttf/.otf` via the system file picker; copied to app storage and mirrored into the library folder `.fonts/` so other devices can install them; documents reference fonts by family name with fallback to Inter. UI fonts (Inter, Fraunces) live separately in core:designsystem.

## Blocks
A flow's Markdown is split into blocks. Block = the unit of parsing, layout caching, invalidation, and focused editing. Runtime block ids are stable within a session (not stored).
| Block | Line boxes |
|---|---|
| Paragraph | n x 1U (wrapped lines); empty paragraph = 1U |
| Heading 1 / 2 | n x 2U |
| Heading 3 | n x 1U, bold |
| Bullet / numbered / task item | n x 1U, indent = level x indent step |
| Quote / callout | n x 1U, left bar, callout icon line for `> [!NOTE]` style |
| Code block | n x 1U, monospace, muted background, no wrapping beyond frame (soft wrap on) |
| Math block | ceil(height / U) x 1U, unsplittable |
| Table | rows x (max cell lines x U), splittable between rows |
| Horizontal rule | 1U, line drawn at the box center |
Indent step: 1 grid column on grid/dotted templates, 6 mm on lined/blank.

## Markdown dialect
- CommonMark + GFM tables, strikethrough (`~~`), task lists (`- [ ]`, `- [x]`), GFM alerts for callouts (`> [!NOTE]`, `[!TIP]`, `[!WARNING]`).
- Folio inline extensions: `==highlight==`, inline math `$...$` (not preceded by a digit, to keep "$5" literal; `\$` escapes), block math `$$` on its own lines, links to notes stored as `[Title](folio://doc/<uuid>)` (the `[[` shortcut in the editor inserts them).
- Enter creates a new block; Shift+Enter inserts a hard line break (stored as trailing backslash).
- Serialization must be lossless for everything Folio produces (round-trip tests). Foreign Markdown opens as-is; unknown syntax renders as text.

## Block styles
- Bold, italic, strikethrough, inline code (monospace, muted background), highlight (yellow background band inside the line box), links (accent color, underline), inline math.
- Lists: bullets per level (disc, circle, dash); numbers per level (1., a., i.); task checkboxes 0.7U square, tap toggles.
- Headings: display weight of the chosen family; H1/H2 have 2U boxes so their baselines sit on every second rule.
- Alignment per flow: start (default), center, justified (paragraphs only).

## Flows and frames
- **Body flow:** one per document, created on first typed text in a page body. Its frames are the BODY frames of pages in document order.
- **Body frame rect** = the template's text zone: lined/blank: left margin (25 mm margin line + 2 mm, or 12 mm without margin line) to width - 12 mm, top y0, bottom = last rule above height - 12 mm. Grid/dotted/graph: 12 mm margins snapped to grid columns. Cornell: notes column (cue column and summary are separate BOX frames created on tap). Planners: notes areas. Infinite pages: origin width, unbounded height.
- **Distribution:** block line boxes are placed sequentially into frames. Paragraphs, lists, quotes, code split at line boundaries; tables between rows; math blocks, rules, and headings never split (a heading is also kept with the next line). Paragraph gap setting inserts 0 or 1 empty line between blocks.
- **Auto-continue:** when the last frame overflows on a fixed page, a new page with the same spec/background is inserted directly after that page and gets a BODY frame (a "continuation page"). If text shrinks, continuation pages that end up with no text and no other objects are removed. Both are part of the same undo step as the edit.
- **Free text boxes (BOX):** own flow per box. Top snaps to a rule line; left snaps to a grid column (grid templates) or is free (lined); width default 60% of body width, min 20 mm; height grows in whole U steps. Resizable and movable via selection.
- **Sticky notes:** own flow; inner padding 0.5U; lines at U relative to the sticky's own top (no visible rules); rotation applies to the whole sticky.

## Rendering
- `BlockLayout` = reference-scale layout of one block for a given width + style key + U: line ranges, line box heights, placeholder rects (math, checkboxes), baseline correction. Cached (LRU, key = block text hash + width + style + U).
- `FlowLayout` = distribution result: per frame, the list of (block, first line, last line, y offset in U).
- Unfocused blocks are painted by `PageRenderer` into content tiles from `BlockLayout`s (Compose `Paragraph.paint` on the tile canvas with scale), then math and checkboxes are drawn at their placeholder rects.
- Invalidation after an edit: the edited block; if its line count changed, every following line in that frame and later frames (tile invalidation by rect).
- Layout of blocks for newly visible pages runs on the `text` dispatcher, visible-first; never on the main thread except for the focused block.

## Editing
- Exactly one live text field exists: for the focused block. It sits in the canvas overlay at the block's screen rect, uses the same TextStyle with font size scaled by the viewport (not a graphics-layer scale), and system stylus handwriting disabled.
- While focused, the block is excluded from tiles; the field draws it. On unfocus the block's layout is recomputed and tiles updated.
- The focused block shows Markdown markers dimmed (40% alpha) via styling only (no hidden characters, so caret mapping stays trivial). Unfocused blocks render without markers.
- Caret placement: tap (pen with Text tool, or finger tap on a text block when the Text tool is active) maps to (block, offset) via BlockLayout; the field receives focus in the same frame. Budget: tap to visible caret <= 50 ms.
- Navigation: arrows cross block boundaries (keeping the x position); Enter splits the block (new list item continues the list); Backspace at block start merges with the previous block; Tab/Shift+Tab indent list items.
- Shortcuts at block start: `# `, `## `, `### `, `- `, `* `, `1. `, `[] ` / `- [ ] `, `> `, ` ``` `, `$$`, `---`, `| a | b |` + Enter (table).
- Selection across blocks: Shift+arrows or drag handles; copy/cut/paste as Markdown (plain text fallback for foreign paste).
- Formatting bar (docked above the on-screen keyboard or at the toolbar options row with a hardware keyboard): bold, italic, highlight, code, math, link, heading level, list types, checklist, table, undo, redo.
- Each edit is an `EditFlow` command; typing coalesces per block within 1 s.

## Math
- `MathRenderer` interface (ADR-007): `layout(latex, fontSizePx, argb, display): Outcome<MathLayout>` with `MathLayout.box = MathBox(widthPx, ascentPx, depthPx)` and `draw(canvas, x, baselineY)`; vector drawing only (works on PDF canvases). Renderer: jlatexmath-android with true TeX metrics (`setTrueValues`), ascent and depth padded by 0.04 em because ink exceeds the TeX box by up to 1.9 px at 50 px (A-007).
- Inline math: laid out at the body font size, then scaled uniformly by s = min(1, 0.78U / ascent, 0.30U / depth), s >= 0.55. The text layout gets an `AboveBaseline` placeholder of width w*s and height ascent*s (never taller than the line box); the renderer draws the formula at that position with its baseline on the line baseline, so depth hangs below like descenders. If s would drop below 0.55, render at 0.55 and show a subtle dotted underline hint "tall formula: use block math".
- Block math: font size 1.15 x body; scale to fit the frame width (min 0.6); occupies ceil((ascent + depth + 0.5U) / U) lines; formula vertically centered in that span, horizontally centered (setting: left).
- Editing: tapping a formula in the focused block shows its source; invalid LaTeX renders the source in red monospace with the parser error on long-press.
- Caches: MathBox per (latex, size, color).

## Tables
- GFM tables with alignment row. Column widths: proportional to the max unwrapped content width, min 12 mm, sum = frame width minus indent.
- Row height = max wrapped line count of its cells x U. Cell horizontal padding 0.4U. Text baselines on rules inside cells.
- Borders: horizontal borders on rule lines (row boundaries), vertical borders at column edges, 0.75 pt, line color of the template darkened 20%. Header row bold with a 4% tint.
- Editing: tap a cell -> cell field; Tab / Shift+Tab move between cells; Enter in the last row adds a row; a mini toolbar adds/removes rows/columns and sets alignment. Stored back as normalized GFM (padded columns).
