# P03 Canvas and ink
Goal: fast page canvas with templates and tiles; low-latency pen strokes with all brushes; erasers; persistence through the session.
Exit: a 1500-stroke page meets the pan/zoom and ink budgets on the tablet; tag `p03-done`.

### P03-T01 Viewport math
Implements: R-INK-07, R-CORE-02
Read: 05-canvas-rendering.md#viewport
Files: core/render/.../viewport/**
Do:
1. `Viewport` (scale, offset, mode Stack | Canvas(pageId)), `PageStackLayout` (vertical, 16 dp gap, centered, fit-width), zoom range 0.2x..8x of fit-width, zoom buckets 2^(k/2), pt<->px, visible pages, focal-point zoom.
Accept:
- [ ] unit: conversions; bucket choice; visible range with 200 pages; focal point stays under the pinch center within 0.5 px
Verify: `./gradlew :core:render:testDebugUnitTest`

### P03-T02 Canvas host and gestures
Implements: R-INK-06, R-INK-07
Read: 05-canvas-rendering.md#layers, 06-ink-input.md#input-routing
Files: feature/editor/.../canvas/**, core/render/.../DisplayModeHelper.kt
Do:
1. `CanvasHostView` (FrameLayout): BackgroundTileLayer, ContentTileLayer, overlay slot, InProgressStrokesView on top. Hosted by `AndroidView`.
2. Finger gestures only: one-finger pan with fling (OverScroller), two-finger pinch zoom + pan. No other gestures.
3. `DisplayModeHelper`: request the highest refresh mode at current resolution while attached.
4. Debug commands: `open`, `zoom-anim a,b,ms`, `scroll-page n`.
Accept:
- [ ] device: 20 blank pages, `zoom-anim` + scripted pans -> jank <= 1%
- [ ] device: `diag.sh refresh` shows the max mode (record actual value in device.md)
Verify: device-tester

### P03-T03 Templates
Implements: R-PAGE-01, R-PAGE-02
Read: 05-canvas-rendering.md#templates, 07-text-engine.md#grid-unit
Files: core/render/.../template/**
Do:
1. `TemplateRenderer` for every kind in 05-canvas-rendering.md#templates with spacing presets, vector drawing aligned to the page grid origin, infinite repetition, custom PNG/PDF template assets.
2. `GridUnit.of(template)` per 07-text-engine.md#grid-unit.
Accept:
- [ ] screenshot: every template at A4 portrait, A4 landscape, A5 portrait
- [ ] unit: grid unit per template matches spec
Verify: `./gradlew :core:render:verifyRoborazziDebug :core:render:testDebugUnitTest`

### P03-T04 Tile caches
Implements: R-PERF-04
Read: 05-canvas-rendering.md#tiles, decisions.md#adr-003-committed-content-rendering
Files: core/render/.../tiles/**
Do: implement the strategy chosen in ADR-003: tile keys, BitmapPool, background vs content caches, bucket re-render after 100 ms idle (visible first, center-out), bounds invalidation via spatial index, memory budget, eviction.
Accept:
- [ ] unit: tile key math, invalidation sets, LRU budget
- [ ] device: seeded 1500-stroke page: pan/zoom jank <= 1%; `render:settle` p95 <= 150 ms (`perf-dump`)
Verify: `./gradlew :core:render:testDebugUnitTest` + device-tester

### P03-T05 Brush catalog
Implements: R-INK-01, R-INK-02
Read: 06-ink-input.md#brushes, #colors
Files: core/ink/.../brush/**
Do: `BrushCatalog` v1 for BALLPOINT, FOUNTAIN, PENCIL, MARKER, HIGHLIGHTER; size presets; default palettes; pressure curve (gamma); tilt behaviors only when `StylusCapabilities.tilt`.
Accept:
- [ ] screenshot: each brush draws the same synthetic pressure ramp stroke
- [ ] unit: BrushSpec round trip; version pinning (v1 spec renders with v1 family)
Verify: `./gradlew :core:ink:verifyRoborazziDebug :core:ink:testDebugUnitTest`

### P03-T06 Wet ink and input routing
Implements: R-INK-06, R-INK-08
Read: 06-ink-input.md#input-routing, #wet-ink, #palm-rejection
Files: core/ink/.../input/**, feature/editor/.../canvas/**
Do: `InputRouter`, InProgressStrokesView integration, pressure curve, palm rules, `StylusCapabilities` detection.
Accept:
- [ ] unit: routing table with synthetic MotionEvents (Robolectric)
- [ ] device: 50 `stylus-swipe` -> 50 strokes; 20 finger `swipe` -> 0 strokes, page panned
- [ ] device: `ink:onTouch` p95 <= 1 ms
Verify: `./gradlew :core:ink:testDebugUnitTest` + device-tester

### P03-T07 Dry handoff and persistence
Implements: R-INK-08, R-FILE-02
Read: 05-canvas-rendering.md#dry-handoff, 03-document-model.md#sessions
Do: finished strokes -> AddObjects via session -> tile update -> remove wet strokes after the frame showing them; autosave; `seed-strokes n` debug command.
Accept:
- [ ] device: 20 strokes, `stop.sh`, relaunch -> all 20 present
- [ ] device: 3 screenshots right after stroke end all show the stroke
- [ ] device: `ink:commit` main-thread p95 <= 2 ms
- [ ] user: "Write a page of notes quickly. Any flicker, gap, or double line when lifting the pen?"
Verify: device-tester

### P03-T08 Erasers
Implements: R-INK-03
Read: 06-ink-input.md#erasers
Files: core/ink/.../erase/**
Do: stroke eraser (geometry intersection), partial eraser (input split; fragments < 2 inputs or < 1 pt dropped; z-order kept), sizes, highlighter-only option; one gesture = one command.
Accept:
- [ ] unit: split correctness on synthetic strokes; undo restores exactly
- [ ] device: erase across 10 strokes in one gesture -> one undo restores all
Verify: `./gradlew :core:ink:testDebugUnitTest` + device-tester

### P03-T09 Stylus extras (after P01-S7 USER-CHECK)
Implements: R-INK-02
Read: 06-ink-input.md#stylus-capabilities, docs/notes/device.md#stylus
Do: hover cursor ring (if hover reported), stylus button hold = temporary eraser (if button events arrive), tilt shading for pencil (if tilt reported). Capability-gated; hide settings when unsupported.
Accept:
- [ ] unit: capability gating logic
- [ ] user: "Hover shows a size ring; holding the pen button erases (only if the probe found them)."
Verify: `./gradlew :core:ink:testDebugUnitTest`

### P03-T10 Performance pass
Implements: R-PERF-03, R-PERF-04
Read: 12-performance.md#budgets
Do: measure all ink and render budgets; write docs/notes/perf.md table (metric, budget, measured, date, commit). Fix misses or record Blocked with evidence.
Accept:
- [ ] device: every P03 budget met or explicitly recorded as a known gap with an AMENDMENTS entry
Verify: device-tester
