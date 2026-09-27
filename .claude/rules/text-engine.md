---
paths:
  - "core/text/**"
  - "feature/editor/**/text/**"
---
# Text engine rules
- Grid invariant: every line box height = k * U (integer k >= 1) and each baseline lies on the rule at the bottom of its line box: baselineY = frame.topPt + sum of line-box heights up to and including that line. Tests assert it for every style.
- Font size is derived: size such that cap height = ratio * U (ratio per style) from `FontMetricsCache`. The user picks S/M/L ratios, never raw sp.
- Fixed line box via `TextStyle(lineHeight, LineHeightStyle(Alignment.Bottom, Trim.None))` plus per-(font, size) baseline correction. Never rely on font ascent/descent for placement.
- Inline math: `MathRenderer` box scaled to fit (ascent <= 0.78 U, depth <= 0.30 U, min scale 0.55). Its placeholder never exceeds the line box.
- Headings, block math, tables, rules, code blocks occupy integer line counts.
- Blocks are the unit of parsing, layout caching, and editing. Exactly one live text field exists (the focused block).
- Parsing: commonmark-java + GFM tables/strikethrough/task lists + Folio inline extensions (`==highlight==`, `$math$`, `[[doc link]]`). Parse per block.
- Disable system stylus handwriting on editor fields; a device test asserts the pen draws ink over a focused block.
- Budgets: keystroke-to-frame p95 < 16 ms on a 20k-word flow; tap-to-caret < 50 ms. Trace `text:*`.
