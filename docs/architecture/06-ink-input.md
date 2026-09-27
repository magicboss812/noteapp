# 06 Ink and input

## Input routing
`InputRouter` (core:ink) receives every MotionEvent from `CanvasHostView.dispatchTouchEvent` / `onGenericMotionEvent`.
| Pointer | Action |
|---|---|
| TOOL_TYPE_STYLUS | active tool (pen, highlighter, eraser, lasso, shape, text tap, image placement, ...) |
| TOOL_TYPE_ERASER (if ever reported) | eraser regardless of active tool |
| TOOL_TYPE_FINGER, 1 pointer | pan (with fling) |
| TOOL_TYPE_FINGER, 2 pointers | pinch zoom + pan |
| Finger on ruler / selection box | move/rotate that object instead of panning |
| Mouse (keyboard trackpad) | pan with drag, zoom with Ctrl+scroll |
Finger taps never create content. Finger long-press does nothing (no gestures beyond pan/zoom).
Routing decisions are made on ACTION_DOWN per pointer and kept for the whole gesture.

## Palm rejection
- Stylus down or hovering (hover events within the last 300 ms) -> all finger pointers are ignored until 300 ms after stylus up/hover exit.
- Events with `FLAG_CANCELED` or ACTION_CANCEL end the finger gesture without effect; a canceled stylus stroke is discarded (`InProgressStrokesView.cancelStroke`).
- Finger touches with touch major > 40 mm (large contact) are ignored.

## Wet ink
- androidx.ink `InProgressStrokesView` renders in-progress strokes with front-buffered rendering and motion prediction (ADR-002).
- Pen tools call `startStroke(event, pointerId, brush)`, `addToStroke(event, pointerId)`, `finishStroke(...)`; tools that modify inputs (ruler snapping) feed `StrokeInput` batches instead.
- The main thread does only routing and forwarding (budget 1 ms p95 per event, zero allocation: reuse objects).
- `DisplayModeHelper` requests the highest refresh rate while the editor is visible.
- Dry handoff: 05-canvas-rendering.md#dry-handoff.

## Brushes
`BrushCatalog` maps `BrushSpec(kind, argb, sizePt, version, pressureGamma)` to an androidx.ink `BrushFamily` + `Brush`. Version 1 definitions:
| Kind | Base | Behavior |
|---|---|---|
| BALLPOINT | pressure pen family | light pressure response (width 85-100%), no speed thinning |
| FOUNTAIN | pressure pen family, customized | strong pressure response (width 35-120%), slight direction-dependent width (nib 30 deg) |
| PENCIL | custom family with texture layer | pressure -> opacity 35-90% and width 90-110%; with tilt: side shading widens and lightens |
| MARKER | marker family | constant width, opaque, round tip |
| HIGHLIGHTER | highlighter family, self-overlap discarded | chisel tip, 35% alpha, straight-line snap on hold |
Width presets per kind (pt): ballpoint 0.6/0.9/1.3, fountain 0.8/1.2/1.8, pencil 0.8/1.2/2.0, marker 1.5/2.5/4.0, highlighter 8/12/18. Custom widths 0.3-30 pt.
Pressure curve: `p' = p^gamma`, gamma 0.5-2.0 (settings). Brush families are built once and cached. A visual change to a brush means a new version; old strokes keep rendering with their stored version.

## Colors
Default palette (editable per tool, persisted): ink black `#1A1A1A`, blue `#2563EB`, red `#DC2626`, green `#16A34A`, purple `#7C3AED`. Highlighter: yellow `#FDE047`, green `#86EFAC`, pink `#F9A8D4`, blue `#93C5FD`, orange `#FDBA74`. Custom colors via an HSV picker with hex input; up to 12 favorites per tool.

## Erasers
- STROKE mode: eraser path segments (radius = eraser size) tested against stroke geometry with androidx.ink geometry intersection; hits are removed. Shapes are hit-tested by their outline.
- PARTIAL mode: for each hit stroke, input points within the eraser radius (measured to the stroke centerline, plus half the stroke width) are removed; remaining runs become new strokes with the same brush and z position. Runs with < 2 inputs or < 1 pt length are dropped. Implemented on `StrokeInputs`, so results are serializable (the experimental mesh eraser in ink 1.1.0-alpha is not used).
- Options: size presets (4/10/24 pt), "highlighter only", "clear page" (confirmed, undoable).
- The whole gesture becomes one `ReplaceObjects` command (one undo step). Preview: erased parts disappear live via a temporary mask on affected tiles.
- Text, images, stickies, attachments are not erased (lasso + delete instead).

## Shapes
Shape tool: pen drag from start to end creates the chosen kind (line, arrow, rectangle, ellipse, triangle, polygon via taps + double-tap to close). Shift-like constraint: holding the stylus button (if available) or enabling "constrain" snaps to 15 deg angles and equal sides. Style: stroke color, width, dashed, fill (none or color at 25% or 100%).
Shapes are editable when selected: move, resize (8 handles), rotate, and for line/arrow/polygon drag individual points.

## Shape recognition
- Trigger: while drawing with pen/highlighter, the pen stays within 4 dp for 450 ms at the stroke's end (still touching).
- Recognizer (pure Kotlin, core:ink): resample to 64 points -> corner detection (ShortStraw) -> candidate fits: line (straightness > 0.98), arrow (line + 2 short segments at the end), triangle (3 corners, closed), rectangle (4 corners, near-right angles; snaps to axis if within 8 deg), polygon (5-8 corners, closed), ellipse/circle (least-squares fit, normalized residual < 0.08; circle if axis ratio > 0.9).
- On success the wet stroke is canceled and a shape preview appears; continuing to move the pen (still down) scales/rotates the shape around its anchor; lifting commits a `Shape` (one undo step). No confident fit -> the stroke stays ink.
- Highlighter: always a straight line after the hold.

## Lasso
- Modes: freeform (dashed path drawn with the pen) and rectangle.
- Membership: strokes with >= 60% of their input points inside the polygon; shapes/images/stickies/frames/attachments whose bounds center is inside. Filter toggles in the options row (ink, shapes, text, images).
- Selection UI: bounding box, 8 resize handles, rotate handle, action bar above the box: Cut, Copy, Duplicate, Delete, Color (strokes/shapes), Arrange (forward, backward, front, back).
- Move: drag inside the box with pen or finger. Resize keeps aspect with corner handles, free with edge handles. Rotation shows the angle, 15 deg detents.
- Clipboard: in-app clipboard holds objects + referenced assets (paste works across documents); a PNG of the selection is also put on the system clipboard via FileProvider.
- Paste position: center of viewport, or at the last pen tap.

## Ruler
- Toggle from the toolbar. A semi-transparent 18 cm ruler with mm/cm ticks and an angle readout.
- One finger on the ruler moves it; two fingers on the ruler rotate it (15 deg detents with light haptic). This is an exception to the gesture rule only while fingers touch the ruler.
- Pen strokes that start within 12 dp of the ruler edge are projected onto the edge line (inputs fed as StrokeInput batches); they end where the pen lifts.
- State is per pane and not persisted.

## Stylus capabilities
`StylusCapabilities` is detected at runtime (InputDevice motion ranges + observed events) and refined by the P01-S7 probe results in docs/notes/device.md#stylus:
- `pressure` (expected yes, 8192 levels per Xiaomi specs), `tilt`, `orientation`, `hover`, `primaryButton`, `sampleRateHz` (expected 240 Hz).
- P01-S7: both pen digitizers declare PRESSURE, TILT (0..pi/2), ORIENTATION and DISTANCE ranges; what the Focus Pen really sends is confirmed by the physical USER-CHECK. Until then tilt, orientation and hover count as available only after a real event with a non-zero value; buttons count as unavailable (device.md#stylus).
- Features gated by capability: tilt shading (pencil), hover cursor ring, button-hold temporary eraser. Unsupported features are hidden in settings, not shown disabled.
- Xiaomi binds the Focus Pen buttons to system functions (screenshot, spotlight, writing); assume they never reach the app unless the probe proves otherwise.
- System stylus handwriting (Android handwriting / HyperOS Scribe) must be disabled for editor text fields.
