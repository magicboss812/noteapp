# P08 PDF
Goal: import PDFs (up to ~100 pages) as documents with PDF-backed pages, render them fast at any zoom, annotate with every tool, insert blank pages.
Exit: 100-page PDF meets budgets on the tablet; tag `p08-done`.

### P08-T01 PDF import
Implements: R-MED-01
Read: 08-pdf.md#import
Do: import from library "New > Import PDF" and share intents (ACTION_SEND/VIEW for application/pdf); copy into assets (sha256), pages FIXED with PDF page sizes and `PdfBackground(assetId, pageIndex)`; password prompt (PdfRenderer LoadParams); title from file name; progress for large files.
Accept:
- [ ] unit: page spec creation from page sizes (mixed sizes and rotations)
- [ ] device: import fixture 100-page PDF; page count correct
Verify: tests + device-tester

### P08-T02 PDF background rendering
Implements: R-MED-01, R-PERF-06
Read: 08-pdf.md#rendering, 05-canvas-rendering.md#tiles
Do: `PdfRasterizer` per document on the pdf dispatcher; low-res page previews first, then bucket tiles into the background cache; prefetch next/previous pages; memory budget shared with tiles.
Accept:
- [ ] device: first page visible <= 2 s after import; scroll jank <= 1%; zoom to 4x shows sharp text after settle
Verify: device-tester

### P08-T03 Blank pages and page ops in PDF documents
Implements: R-MED-01, R-PAGE-02
Read: 10-editor-ui.md#pages
Do: insert blank pages (any template/size) between PDF pages; duplicate/delete/reorder work for PDF-backed pages; page panel shows PDF thumbnails.
Accept:
- [ ] unit: page ops keep correct pdf page indices
Verify: tests

### P08-T04 PDF performance and memory pass
Implements: R-PERF-06
Read: 12-performance.md#budgets
Do: measure import, open, scroll, zoom, memory with the 100-page fixture and with a scanned-image PDF; fix misses; record in perf.md.
Accept:
- [ ] device: budgets met or recorded gap with AMENDMENTS entry
Verify: device-tester
