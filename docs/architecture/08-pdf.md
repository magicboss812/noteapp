# 08 PDF

## Import
- Entry points: library "New > Import PDF" (system document picker), share/open intents for `application/pdf` (ACTION_SEND, ACTION_VIEW), drag of a PDF from the file picker in split view (later).
- Steps (io dispatcher, progress UI for > 10 MB): copy bytes into the new document's assets (`assets/<sha256>.pdf`), open with `PdfRenderer` (password-protected: prompt, `LoadParams` with password; wrong password -> retry), read page count and sizes, create one page per PDF page with `PdfBackground(asset, index)` and `PageSpec.Fixed` (if it matches A3/A4/A5 within 1 pt, any orientation) or `PageSpec.Custom(w, h)`. Page rotation from the PDF is applied to the page size.
- Title = file name without extension. The original file is never referenced after import.
- Limits: designed for <= 100 pages; larger PDFs import but show a one-time note that performance may degrade. Hard limit 2000 pages.

## Rendering
- `PdfRasterizer` per open document: owns one `PdfRenderer` (not thread-safe) on the single-thread `pdf` dispatcher; opens one page at a time, closes it in `finally`.
- Two passes per page: (1) low-resolution preview (page fit at 0.5x screen density) cached per page for fast scrolling and thumbnails; (2) background tiles at the current zoom bucket via `Page.render(bitmap, destClip, matrix, RENDER_MODE_FOR_DISPLAY)` with the tile's transform.
- Background tiles use the background cache (05-canvas-rendering.md#tiles). Prefetch previews for the next and previous 2 pages. Cancel requests for pages scrolled away.
- Paper color is not drawn under PDF pages (the PDF's own background shows). Dark UI never alters PDF rendering.

## Export
`PdfExporter` chooses a strategy per page; the output is one PDF with pages in document order.
- **Folio pages (no PDF background):** `android.graphics.pdf.PdfDocument`, page size = page size in pt (infinite: see below), content drawn by `PageRenderer` with target EXPORT_VECTOR (paper, template as vectors, ink as outline paths, real text with embedded fonts, math as vectors, images at native resolution).
- **PDF-backed pages (ADR-006):** keep the original vector page and merge annotations on top. Default strategy: render the annotation layer of the page into a temporary one-page PDF with PdfDocument (transparent background, no template, no paper), then overlay it onto the original page with PdfBox-Android `LayerUtility` (import the overlay page as a form XObject, append to the original page content). Pages without annotations are copied untouched. Implemented by `PdfBoxOverlayMerger` (core:pdf, P01-S5): `LayerUtility.importPageAsForm` + `drawForm` in an APPEND content stream with reset context; BouncyCastle is excluded (100 pages with 10 overlays merged in <= 0.25 s on the Pad 7).
- **Fallbacks (per ADR-006 evidence):** (a) platform `android.graphics.pdf.component` stamp annotations with path/image/text objects + `PdfRenderer.write`, only on devices whose API level provides them; (b) raster: original page rendered at 200 dpi as an image, annotations as vectors on top.
- Mixed documents (PDF pages + Folio pages): assemble with PdfBox (import pages from the Folio-pages PDF and the merged PDF pages into one document) in page order.
- Metadata: title, creator "Folio", creation date. Progress per page, cancelable; output written atomically.

## Infinite export
- Export region = union of all object bounds on the page (strokes incl. width, shapes, frames' used lines, images, stickies, attachments) expanded by the margin setting (default 10 mm, 0-50 mm).
- Empty infinite page -> its origin page size and position.
- PDF page size limit 14400 pt per side: if the region is taller, split into consecutive pages of equal height (<= 14400 pt) at the same width; if wider, scale the page down to fit 14400 pt width (vector, no quality loss).
- Template is drawn only inside the export region, aligned to the page grid (same as on screen).

## PNG export
- Per page, dpi 150/200 (default)/300: pixel size = size in pt x dpi / 72. Max 16384 px per side; beyond that the dpi is reduced for that page and the export reports the effective dpi.
- Infinite pages use the infinite export region. Background: paper color (or transparent option for Folio pages only).
- Multiple pages: `<title>/<title>-p001.png`, ... in the export destination. Single page: `<title>-p<NNN>.png`.
- Rendering via `PageRenderer` target EXPORT_RASTER into a software bitmap in horizontal strips (<= 4096 px tall) to bound memory, then streamed to the PNG encoder.
