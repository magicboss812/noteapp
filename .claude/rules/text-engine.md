---
paths:
  - "core/text/**"
  - "feature/editor/**/text/**"
  - "feature/editor/**/canvas/**"
---
# Text engine rules
- Grid invariant: every line box height = k * U (integer k >= 1) and each baseline lies on the rule at the bottom of its line box, raised by the flow's lift (A-054): baselineY = frame.topPt + sum of line-box heights up to and including that line - baselineLiftUnits * U. A box's user-set vertical offset (A-057) shifts all its baselines alike. Tests assert it for every style.
- Font size is derived: size such that cap height = ratio * U (ratio per style) from `FontMetricsCache`. The user picks S/M/L ratios, never raw sp.
- Fixed line box via `TextStyle(lineHeight, LineHeightStyle(Alignment.Bottom, Trim.None))` laid out at the grid-pitch reference scale r = round(4*U)/U px per pt, then shifted by the measured `k*U*r - firstLineBaseline` (A-006). Never rely on font ascent/descent for placement.
- Inline math: `MathRenderer` box scaled to fit (ascent <= 0.78 U, depth <= 0.30 U, min scale 0.55). Its placeholder never exceeds the line box.
- Headings, block math, tables, rules, code blocks occupy integer line counts.
- Blocks are the unit of parsing, layout caching, and editing. Exactly one live text field exists (the focused block); it is the IME connection only, edited text is drawn from the paginated page layout (A-057).
- Parsing: commonmark-java + GFM tables/strikethrough/task lists + Folio inline extensions (`==highlight==`, `$math$`, `[[doc link]]`). Parse per block.
- Disable system stylus handwriting on editor fields; a device test asserts the pen draws ink over a focused block.
- Budgets: keystroke-to-frame p95 < 16 ms on a 20k-word flow; tap-to-caret < 50 ms. Trace `text:*`.
- Text boxes (A-057, spec 10-editor-ui.md#text-boxes): only the Edit button enters Edit; Back gesture or Escape steps Edit -> Preview -> none. No gesture selects text outside Edit; a press-and-hold in Preview moves the box.
- Box text never leaves the page: borders stay inside the page text zone (auto width stops growing there), lines past the page bottom continue on the next page, also while editing.
- Box chrome (outlines, grip, knobs, toolbar) is overlay only, the same screen size at every zoom, 48 dp hit targets; never in tiles, thumbnails or export.
