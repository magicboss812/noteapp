# 05 Canvas and rendering

## Viewport
- `Viewport` state: `scale` (px per pt), `offsetPx` (document origin in view px), `mode` = `Stack` (fixed pages in a vertical column) or `Canvas(pageId)` (one infinite page, free 2D).
- `PageStackLayout`: pages in one vertical column, centered horizontally, 16 dp gap, 24 dp side padding. Page tops are prefix sums; lookups by binary search. Infinite pages appear in the stack as a card sized to their content bounds (min: origin size) with a "canvas" badge; tapping enters Canvas mode.
- Fit-width scale = (viewWidth - 48 dp) / widest page width. Zoom range 0.2x..8x of fit-width. Default on open: fit-width, first page top (or last position, stored per document in DataStore).
- Stack space is pt at zoom 1: the 16 dp gap and 24 dp side padding hold at fit-width and scale with the pages (A-017). Canvas mode fits the infinite page's origin width.
- Zoom buckets for tile resolution: `bucketScale = 2^(ceil(2*log2(scale))/2)` (steps of sqrt 2). Tiles render at the bucket at or above the current scale.
- Focal-point zoom keeps the document point under the pinch center fixed. Pan is clamped in Stack mode (vertically half a screen of overscroll; horizontally centered while the column is narrower than the view, else clamped to its edges), unclamped in Canvas mode. View offsets are doubles (float loses 0.25 px deep in long stacks).
- All pt <-> px conversions live in `Viewport` (`toViewPx`, `toPagePt`, `visibleRectPt(pageId)`; per-frame drawing uses the non-allocating `docToViewX/Y` with `PageStackLayout.cardLeftPt/cardTopPt`).

## Layers
`CanvasHostView` (FrameLayout, hosted by an `AndroidView`) stacks, bottom to top:
1. **BackgroundTileLayer** (custom View): paper color, template, PDF raster. Separate cache: annotation edits never invalidate it.
2. **ContentTileLayer** (custom View): committed objects (ink, shapes, text frames except the focused block, images, stickies, attachments) in z-order.
3. **Overlay** (Compose via ComposeView): focused text field, selection box and handles, lasso path, shape preview, ruler, page labels. Above it the hover cursor ring is a plain View (`HoverRingView`), since it moves with every hover event (A-025).
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
Rendering details (A-018, core/render `template/`): `TemplatePresets` holds the default template per kind (top margin 25 mm for lined/blank/Cornell/music/planners, 12 mm side margins for music/planners, lattices from the page corner). `TemplateRenderer` draws rules 0.5 pt, separators 1 pt, margin line 0.75 pt (all at least 1 device px), every ruled line on a GridUnit rule. Lined rules end at the last rule above a 12 mm bottom margin. GRAPH_AXES axes lie on the grid lines nearest the page center, darker lines every 5th line counted from the axes. Planner labels are English, color `#8A94A6`. Lattices (lined, grid, dotted, graph) extend unbounded on infinite pages; the other kinds repeat their origin frame. CUSTOM images come from a `TemplateAssets` source (decoded PNG or rasterized PDF page), scaled to the page frame.

## Tiles
- Tile = 512x512 px at a zoom bucket. Key = (pageId, bucketIndex, tx, ty). Two caches: background and content.
- Rendering on the `render` dispatcher (2 threads). A tile render calls `PageRenderer.draw(canvas, page, tileRectPt, bucketScale, SCREEN)` with only objects returned by the spatial index for the tile rect.
- Strategy (ADR-003, Accepted by spike P01-S2): software ARGB_8888 bitmaps from `BitmapPool`, rendered with `CanvasStrokeRenderer` on a software canvas (works; 512 px tile with 1500 strokes on the page: p50 2..4 ms, p95 4..8 ms, worst 17 ms). The layer views draw tiles with a matrix derived from the Viewport, so pan/zoom never re-renders during a gesture.
- After gesture idle (100 ms): request tiles for the new bucket, visible first, center-out, keeping old-bucket tiles on screen until replacements are ready (no blank frames). Prefetch one tile ring around the viewport.
- Invalidation: a command produces changed bounds per page -> tiles intersecting those bounds (all buckets) are marked stale; visible stale tiles re-render immediately, others lazily. New strokes on top of the z-order can be painted incrementally onto existing content tiles instead of a full tile re-render.
- Memory: content + background caches <= 25% of `largeMemoryClass`; LRU eviction by bytes, visible tiles pinned. `onTrimMemory` drops non-visible tiles.
- Implementation (A-020, core/render `tiles/`): `TileGrid` (key math, ranges, center-out), `TileCache` (per layer, LRU by bytes; pinned = drawn in the latest frame or rendered since, so the budget is soft while more than it is on screen; stale entries keep drawing until replaced), `BitmapPool` (8 idle bitmaps), `TileLayer` (one per layer, half the budget each: request, render on the render dispatcher, draw). Tiles with nothing to draw are cached as empty entries without a bitmap: background tiles of BLANK pages without PDF (the layer paints paper and card itself), content tiles whose rect holds no object. The host requests the visible tiles of both layers before either layer's prefetch ring; ring tiles are requested only while the budget has room. Once the content layer has nothing pending, it prebuilds the ink meshes of every object on the visible pages on the render dispatcher (cancelled by the next request), so strokes panned into view later do not mesh inside a tile render. Evictions are logged once per request in debug builds. Where the current bucket has no tile yet, the layer draws the nearest other bucket's tiles clipped to that slot. Changed bounds come from diffing page bodies (`PageContent.changedBounds`: removed, added, replaced objects, z-order swaps), padded by one bucket pixel. `PageContent` is the immutable per-body snapshot with a `UniformGridIndex` of z-indices and per-object mesh slots carried over for unchanged objects.

## Page renderer
`PageRenderer.draw(canvas, page, regionPt, scale, target)` where target is SCREEN, EXPORT_RASTER, or EXPORT_VECTOR. As built (A-020): `draw(canvas, pageRef, content: PageContent?, regionPt, scale, target)` draws the region with its top-left at the canvas origin; SCREEN is split into `SCREEN_BACKGROUND` (paper, template, PDF) and `SCREEN_CONTENT` (objects, transparent); EXPORT_VECTOR arrives with P09. Ink goes through an `InkPainter` (androidx.ink `CanvasStrokeRenderer`, meshes from core:ink `StrokeBuilder` cached in `PageContent`), so JVM tests can substitute a painter.
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
As built (A-023, feature:editor `DryHandoff`): step 2 re-renders the stale tiles under the stroke instead of painting incrementally; the wet copy leaves once `TileLayer.isDrawn` reports every current-bucket content tile under the stroke's on-screen part fresh. `CanvasController.commitStrokes` runs the command off the main thread. The wet layer is initialized eagerly once attached (lazy init on the first stroke sometimes never created the front-buffer surface after a cold start). Sections: `ink:commit` (main-thread conversion and hand-off of the command), `ink:handoff` (finished callback until removal).

## Canvas mode
- For infinite pages. Free 2D pan and zoom; no clamping; tiles addressed in page space (negative indices allowed).
- Content bounds tracked incrementally (union on add; recomputed lazily on remove).
- A position chip (bottom-left) shows zoom % and a mini map of content bounds vs viewport; tap = zoom to fit content.
- Leaving Canvas mode returns to the stack at the card of that page.
