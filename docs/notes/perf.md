# Performance log
Budgets: docs/architecture/12-performance.md#budgets. Append one row per measurement; summarize old rows when the file grows past 150 lines.

| Date | Commit | Build | Metric | Value | Budget | Notes |
|---|---|---|---|---|---|---|
| 2026-09-27 | fb65ea5 | debug | cold launch TotalTime (am start -W), placeholder screen | 786 ms / 679 ms | none (P00) | launch.sh, 2 runs, first after install |
| 2026-09-27 | P01-S1 | debug | `ink:onTouch` p95, spike-ink, 50 stylus swipes | 0.28 ms (worst run 0.29, max 0.66) | <= 1 ms | 5 runs across 3 handoff modes; display at 120 Hz |
| 2026-09-27 | P01-S1 | debug | `ink:handoff` p50 / p95, commit mode | 6.4 / 8.8 ms | 1 frame | 3 runs; one 66 ms outlier; janky frames 0.4-1.6% |
