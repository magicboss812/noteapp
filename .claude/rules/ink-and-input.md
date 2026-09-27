---
paths:
  - "core/ink/**"
  - "feature/editor/**/canvas/**"
  - "feature/editor/**/input/**"
  - "feature/editor/**/tools/**"
---
# Ink and input rules
- Wet ink only through androidx.ink `InProgressStrokesView`. Never render in-progress strokes yourself.
- `InputRouter` classifies pointers: stylus/eraser tool types -> active tool; finger -> 1-finger pan, 2-finger pinch zoom; `FLAG_CANCELED` and finger touches within 300 ms of stylus down/hover are dropped (palm). Fingers never draw.
- Feed complete MotionEvents (with historical samples) to ink, or StrokeInput batches when a tool modifies input (ruler snapping).
- `BrushSpec` <-> `BrushFamily` only via `BrushCatalog` with explicit versions. A visual change = new brush version; old strokes keep theirs.
- Dry handoff: `onStrokesFinished` -> `AddObjects` command -> tile update -> after the frame that shows it, `removeFinishedStrokes(ids)`. Never remove earlier.
- Persist our `StrokeInputs` (x, y, t, pressure, tilt, orientation). Build `StrokeInputBatch` on load.
- Partial eraser splits inputs, not meshes (keeps strokes serializable). One erase gesture = one undo entry.
- Tilt, hover, and stylus buttons come from runtime `StylusCapabilities`. Never assume them.
- `onTouchEvent` work <= 1 ms p95, zero allocation per event. Trace sections `ink:*` via PerfMonitor.
