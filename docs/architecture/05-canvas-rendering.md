# 05 Canvas and rendering

## Viewport
- `Viewport` state: `scale` (px per pt), `offsetPx` (document origin in view px), `mode` = `Stack` (fixed pages in a vertical column) or `Canvas(pageId)` (one infinite page, free 2D).
- `PageStackLayout`: pages in one vertical column, centered horizontally, 16 dp gap, 24 dp side padding. Page tops are prefix sums; lookups by binary search. Infinite pages appear in the stack as a card sized to their content bounds (min: origin size) with a "canvas" badge; tapping enters Canvas mode.
- Fit-width scale = (viewWidth - 48 dp) / widest page width. Zoom range 0.2x..8x of fit-width. Default on open: fit-width, first page top (or last position, stored per document in DataStore).
- Zoom buckets for tile resolution: `bucketScale = 2^(round(2*log2(scale))/2)` (steps of sqrt 2). Tiles render at the bucket at or above the current scale.
- Focal-point zoom keeps the document point under the pinch center fixed. Pan is clamped in Stack mode (half a screen of overscroll), unclamped in Canvas mode.
- All pt <-> px conversions live in `Viewport` (`toViewPx`, `toPagePt`, `visibleRectPt(pageId)`).

## Layers
`CanvasHostView` (FrameLayout, hosted by an `AndroidView`) stacks, bottom to top:
1. **BackgroundTileLayer** (custom View): paper color, template, PDF raster. Separate cache: annotation edits never invalidate it.
2. **ContentTileLayer** (custom View): committed objects (ink, shapes, text frames except the focused block, images, stickies, attachments) in z-order.
3. **Overlay** (Compose via ComposeView): focused text field, selection box and handles, lasso path, shape preview, ruler, hover cursor, page labels.
4. **InProgressStrokesView** (androidx.ink): wet ink in the front buffer. Always on top.
Compose chrome (toolbar, sheets) sits outside the host. The host receives only `CanvasController` (02-modules.md#editor-state).
Outside-page surroundings: light theme `#E9ECF1`, dark theme `#0B0D11`. Pages get a 1 dp border and a soft shadow (11-design-system.md#elevation).

## Templates
All templates are vector-drawn, aligned to the grid origin (x0 = 0 or left margin, y0 = marginTopPt), repeat infinitely on infinite pages, and use line color `#C9D3E0` by default (dots `#B8C0CC`, margin line `#F2A6A6`).
| Kind | Default geometry | Grid unit U |
|---|---|---|
| BLANK | nothing drawn | 7.0 mm (19.84 pt) |
| LINED narrow / college / wide | horizontal rules every 6.0 / 7.1 / 8.7 mm; top margin 25 mm; red margin line at 25 mm from left (option) | rule spacing |
| GRID | 5 mm squares (options 4, 5, 7 mm) | cell size |
| DOTTED | dots every 5 mm (options 4, 5, 7 mm), 1.2 pt diameter | dot spacing |
| CORNELL | college rules; cue column 63.5 mm (left), summary area 50.8 mm (bottom) separated by 1 pt lines; three text zones | 7.1 mm |
| GRAPH_AXES | 5 mm grid, every 5th line darker, x/y axes through the page center | 5 mm |
| MUSIC_STAFF | staves of 5 lines 2 mm apart, 10 staves per A4 portrait, scaled for other sizes | 7.0 mm (text only in margins) |
| PLANNER_DAILY | date header, hour rows 06:00-22:00 with labels in a 15 mm gutter, notes column | 7.1 mm |
| PLANNER_WEEKLY | week header, 7 day boxes (2 columns + notes box), lined interiors | 7.1 mm |
| CUSTOM | PNG or PDF page asset scaled to the page; user sets U (default 7.1 mm) | user value |
Spacing presets are selectable per page (page settings sheet). Text zones (body frames) per template are defined in 07-text-engine.md#flows-and-frames.

## Tiles
- Tile = 512x512 px at a zoom bucket. Key = (pageId, bucketIndex, tx, ty). Two caches: background and content.
- Rendering on the `render` dispatcher (2 threads). A tile render calls `PageRenderer.draw(canvas, page, tileRectPt, bucketScale, SCREEN)` with only objects returned by the spatial index for the tile rect.
- Strategy (ADR-003, Accepted by spike P01-S2): software ARGB_8888 bitmaps from `BitmapPool`, rendered with `CanvasStrokeRenderer` on a software canvas (works; 512 px tile with 1500 strokes on the page: p50 2..4 ms, p95 4..8 ms, worst 17 ms). The layer views draw tiles with a matrix derived from the Viewport, so pan/zoom never re-renders during a gesture.
- After gesture idle (100 ms): request tiles for the new bucket, visible first, center-out, keeping old-bucket tiles on screen until replacements are ready (no blank frames). Prefetch one tile ring around the viewport.
- Invalidation: a command produces changed bounds per page -> tiles intersecting those bounds (all buckets) are marked stale; visible stale tiles re-render immediately, others lazily. New strokes on top of the z-order can be painted incrementally onto existing content tiles instead of a full tile re-render.
- Memory: content + background caches <= 25% of `largeMemoryClass`; LRU eviction by bytes, visible tiles pinned. `onTrimMemory` drops non-visible tiles.

## Page renderer
`PageRenderer.draw(canvas, page, regionPt, scale, target)` where target is SCREEN, EXPORT_RASTER, or EXPORT_VECTOR.
- Order: paper (not for SCREEN content layer), template (background layer only on SCREEN), PDF page (background layer or export path), objects in z-order.
- Ink: SCREEN/RASTER use `CanvasStrokeRenderer` (androidx.ink). VECTOR draws filled outline Paths built from the stroke mesh outlines (PDF canvases do not support mesh drawing).
- Text: cached block layouts painted per frame (07-text-engine.md#rendering); VECTOR keeps real text (fonts embedded by PdfDocument).
- Images: downsampled decode for SCREEN (sized to bucket), full resolution for export.
- Highlighter: drawn like other strokes with its alpha; no multiply blend (keeps output identical across targets).
- Deterministic: the same inputs produce the same pixels across targets (goldens rely on it).

## Dry handoff
1. `InProgressStrokesView.onStrokesFinished(strokes)` delivers finished strokes (main thread).
2. The session executes `AddObjects` (converted `InkStroke`s). The content layer paints those strokes into the affected visible tiles synchronously on the render thread (incremental paint) and invalidates the layer.
3. A `ViewTreeObserver.registerFrameCommitCallback` registered right after that invalidate fires once the frame drawing the stroke is submitted; then `removeFinishedStrokes(ids)`. (A `Choreographer` frame callback runs before that frame's draw pass, which is too early; A-005.)
4. If tile paint fails or is pending (tile not yet rendered), the wet stroke stays until the tile containing it is drawn.
Result: never a frame without the stroke, never a double-dark overlap longer than one frame.

## Canvas mode
- For infinite pages. Free 2D pan and zoom; no clamping; tiles addressed in page space (negative indices allowed).
- Content bounds tracked incrementally (union on add; recomputed lazily on remove).
- A position chip (bottom-left) shows zoom % and a mini map of content bounds vs viewport; tap = zoom to fit content.
- Leaving Canvas mode returns to the stack at the card of that page.
