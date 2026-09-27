# 12 Performance

## Budgets
Measured on the Xiaomi Pad 7. Final numbers in the benchmark build (P11); earlier phases measure debug builds for relative checks and note it.
| ID | Metric | Budget | Source |
|---|---|---|---|
| R-PERF-01 | Cold start to interactive library | <= 700 ms | Macrobenchmark StartupTimingMetric |
| R-PERF-02 | Open a 20-page note to first page rendered | <= 400 ms | PerfMonitor `editor:open` |
| R-PERF-03 | Main-thread time per pen MotionEvent | <= 1 ms p95 | PerfMonitor `ink:onTouch` |
| R-PERF-03 | Stroke commit on main thread | <= 2 ms p95 | `ink:commit` |
| R-PERF-03 | Wet ink path | front-buffered where the device supports it; no visible gap at dry handoff | P01-S1 + USER-CHECK |
| R-PERF-04 | Pan/zoom on a 1500-stroke page | <= 1% janky frames, p95 frame <= 7 ms at 144 Hz | gfxinfo |
| R-PERF-04 | Tile settle after zoom | <= 150 ms p95 for visible tiles | `render:settle` |
| R-PERF-05 | Keystroke to frame, 20k-word flow | <= 16 ms p95 | `text:edit` |
| R-PERF-05 | Tap to visible caret | <= 50 ms p95 | `text:focus` |
| R-PERF-05 | Scroll through 20k-word flow | <= 1% janky frames | gfxinfo |
| R-PERF-06 | 100-page PDF: import to first page visible | <= 2 s | `pdf:import` |
| R-PERF-06 | 100-page PDF scroll | <= 1% janky frames | gfxinfo |
| - | Entry autosave (one page) | <= 50 ms, never main thread | `io:save` |
| - | Pack 50 MB document | <= 1.5 s, background | `io:pack` |
| - | Library scroll, 500 notes | <= 1% janky frames | gfxinfo |
| - | Search, 500 notes | <= 100 ms | JVM test + `search:query` |
| - | Editor memory with 100-page PDF | <= 900 MB PSS | `diag.sh meminfo` |

## Threading
| Dispatcher | Threads | Work |
|---|---|---|
| main | 1 | UI, input routing, forwarding to ink, focused text field |
| ink (library-owned) | internal | wet stroke rendering (androidx.ink / graphics-core) |
| render | 2 | tile rendering, thumbnails, PNG strips |
| pdf | 1 | PdfRenderer access (not thread-safe) |
| text | 2 | block layout of unfocused blocks, math layout |
| io | Dispatchers.IO | files, Room, packing, scanning |
Rules: no blocking IO or layout of more than one block on main; hot paths allocation-free; coroutine work cancellable when pages leave the viewport.

## Memory
- `android:largeHeap="true"`. Tile caches <= 25% of `largeMemoryClass` (content + background), BitmapPool reuse, LRU by bytes.
- Decoded pages LRU 30 per session; decoded image bitmaps sized to the zoom bucket; PDF previews cached at low resolution only.
- `onTrimMemory(TRIM_MEMORY_UI_HIDDEN)` drops non-visible tiles and previews; `RUNNING_LOW` drops everything not visible.

## Startup
- No work in `Application.onCreate` except Hilt and PerfMonitor. Index incremental scan starts after the first library frame. Fonts load lazily except Inter/Fraunces.
- Baseline profile covering startup, library scroll, opening a note, drawing, and typing (P11).
- R8 full mode in release; Compose stability configuration; no reflection-based serialization.

## Measurement
- `PerfMonitor` (core:common): `PerfMonitor.trace("section") { ... }` records durations into a ring buffer per section (last 1000) and also emits trace sections through a `TraceSink` (:app: platform `android.os.Trace`, visible in Perfetto; A-003). Release builds keep only the tracing call (`PerfMonitor.enabled = BuildConfig.DEBUG`).
- `debugcmd.sh perf-reset` / `perf-dump` return p50/p95/max/count per section as JSON.
- Frame stats: `gfxinfo.sh reset` before and `gfxinfo.sh` after a scripted interaction (debug commands such as `zoom-anim`, `scroll-page`, `seed-strokes`, `seed-text` make interactions repeatable).
- Macrobenchmarks (P11, `:benchmark`): startup (cold/warm), open note, draw 50 strokes (synthetic stylus), pan/zoom, type 200 chars; FrameTimingMetric + custom trace metrics.
- Every perf task writes a row per metric to docs/notes/perf.md: date, commit, build type, metric, value, budget.
