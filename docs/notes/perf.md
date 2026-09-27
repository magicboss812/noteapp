# Performance log
Budgets: docs/architecture/12-performance.md#budgets. Append one row per measurement; summarize old rows when the file grows past 150 lines.

| Date | Commit | Build | Metric | Value | Budget | Notes |
|---|---|---|---|---|---|---|
| 2026-09-27 | fb65ea5 | debug | cold launch TotalTime (am start -W), placeholder screen | 786 ms / 679 ms | none (P00) | launch.sh, 2 runs, first after install |
| 2026-09-27 | P01-S1 | debug | `ink:onTouch` p95, spike-ink, 50 stylus swipes | 0.28 ms (worst run 0.29, max 0.66) | <= 1 ms | 5 runs across 3 handoff modes; display at 120 Hz |
| 2026-09-27 | P01-S1 | debug | `ink:handoff` p50 / p95, commit mode | 6.4 / 8.8 ms | 1 frame | 3 runs; one 66 ms outlier; janky frames 0.4-1.6% |
| 2026-09-27 | 19ea01f | debug | spike-tiles bitmap tiles, 1500 strokes: janky % / p95 frame, zoom-anim 1->3->1 | 0.49% / 15 ms (worst 18) | <= 1% / <= 7 ms at 144 Hz | display at 120 Hz; p95 misses the budget (P03-T10 owns it) |
| 2026-09-27 | 19ea01f | debug | same, 10 s finger pan at zoom 3 | 0.06% / 9 ms | <= 1% / <= 7 ms | p95 misses; RenderNode tiles also 9 ms |
| 2026-09-27 | 19ea01f | debug | tile settle after zoom, p50 (worst) | 97 ms (123 max) | <= 150 ms p95 | 3 runs |
| 2026-09-27 | c251d31 | debug instr. | jlatexmath layout+draw p50 / p95, 40 formulas at 50 px | 0.36 / 0.80 ms | <= 4 ms (ADR-007 rule) | RaTeX 1.49 / 5.09 ms |
| 2026-09-27 | 8e65e3b | debug instr. | grid text layout per paragraph, 20 fonts | 0.29 .. 0.58 ms | none yet (P06) | GRID_PITCH, U = 7.1 mm |
| 2026-09-28 | bdd4c90 | debug | 100-page PDF export, 10 overlays merged | 80 .. 241 ms | <= 10 s (ADR-006 rule) | first page 25..267 ms, tile 2.0..2.6 ms (simple generated pages) |
| 2026-09-28 | c64ad0c | debug | pack 46 MB `.folio` to shared storage | 1391 .. 1450 ms | <= 1.5 s | 4 runs; FUSE write dominates, little margin |
