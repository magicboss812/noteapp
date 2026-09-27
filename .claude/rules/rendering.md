---
paths:
  - "core/render/**"
  - "feature/editor/**/canvas/**"
---
# Rendering rules
- One renderer: `PageRenderer.draw(canvas, page, regionPt, scale, target)` serves screen tiles, PNG export and PDF export. Targets differ by flags only.
- Page space in points. Convert with `Viewport` helpers only.
- Tiles: 512 px square, key (pageId, zoomBucket, tx, ty). Background (paper, template, PDF raster) and content tiles are cached separately.
- During pan/zoom gestures only transform existing tiles. Re-render at the new bucket after 100 ms idle: visible tiles first, center-out.
- Invalidate by bounds via the page spatial index. "Invalidate all" only on page spec/background change.
- Tile rendering on the `render` dispatcher (2 threads). Bitmaps come from and return to `BitmapPool`.
- Tile cache memory <= 25% of `ActivityManager.largeMemoryClass`. Log evictions in debug builds.
- Editor requests the highest refresh-rate display mode while visible (`DisplayModeHelper`) and releases it when hidden.
- Renderer changes need Roborazzi goldens. Pan/zoom code changes need a device gfxinfo check.
