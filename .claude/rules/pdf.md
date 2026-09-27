---
paths:
  - "core/pdf/**"
---
# PDF rules
- `PdfRenderer` is not thread-safe: one `PdfRasterizer` per document on the single-thread `pdf` dispatcher, one page open at a time, always closed in `finally`.
- PDF background tiles are cached apart from content tiles. Annotation edits never re-rasterize the PDF.
- Export uses `PdfExporter` with strategies per ADR-006 (PdfDocument for Folio pages, overlay merge for PDF-backed pages, raster fallback). Choose the strategy in one place.
- Infinite page export: content bounds + user margin; max 14400 pt per side; split vertically beyond that.
- Every export feature has a test that reopens the output with PdfRenderer and checks page count, page size, and pixels at a known annotation.
- Imported PDFs are copied into the document assets (sha256 name). Never reference external paths.
