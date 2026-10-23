# P09 Export
Goal: manual export to PDF, PNG, and `.folio` with high fidelity, including PDF-backed pages and infinite canvases.
Exit: all exports verified by re-render tests; tag `p09-done`.

### P09-T01 Export render targets
Model: opus high
Implements: R-FILE-03
Read: 05-canvas-rendering.md#page-renderer, 08-pdf.md#export
Do: PageRenderer EXPORT_VECTOR (strokes as outline paths, text as real text with embedded fonts, math as vectors, images at native resolution) and EXPORT_RASTER; export excludes UI overlays and selection.
Accept:
- [ ] screenshot: export raster of a mixed page equals screen render within tolerance
Verify: screenshots

### P09-T02 PDF export for Folio pages
Model: opus medium
Implements: R-FILE-03
Read: 08-pdf.md#export
Do: `PdfExporter` strategy for non-PDF pages via android PdfDocument; page ranges; progress + cancel; output to `Documents/Folio(-Debug)/Exports/` or a user-chosen location (SAF create document).
Accept:
- [ ] unit/instrumented: reopen output with PdfRenderer; page count/sizes; pixel check at a known stroke
Verify: `instrumented.sh :core:pdf`

### P09-T03 PDF export for PDF-backed pages
Model: opus medium
Implements: R-FILE-03, R-MED-01
Read: decisions.md#adr-006-pdf-export
Do: overlay merge per ADR-006 (original vector page + annotation overlay); fallback strategy wired; mixed documents (PDF + blank pages) in one output.
Accept:
- [ ] instrumented: original text still vector (PdfRenderer text contents non-empty where supported, or file-size heuristic), overlay pixels present, page order correct
Verify: `instrumented.sh :core:pdf`

### P09-T04 Infinite canvas export
Model: opus medium
Implements: R-PAGE-04
Read: 08-pdf.md#infinite-export
Do: content bounds (all objects) + margin setting (default 10 mm), 14400 pt max side with vertical split, empty infinite page exports its original fixed size.
Accept:
- [ ] unit: bounds and split math
- [ ] instrumented: export size equals bounds + margin
Verify: tests

### P09-T05 PNG export
Model: opus medium
Implements: R-FILE-03
Read: 08-pdf.md#png-export
Do: per-page PNG at 150/200/300 dpi (default 200), max 16384 px side (scale down beyond), infinite pages cropped like PDF, multi-page export as numbered files in a folder.
Accept:
- [ ] unit: dpi to pixel math and limits
- [ ] device: export 3 pages; `pull.sh exports`; image sizes correct
Verify: tests + device-tester

### P09-T06 Export UI and .folio export
Model: opus medium
Implements: R-FILE-03
Read: 10-editor-ui.md#export-dialog
Do: export sheet (format, pages, dpi, margin for infinite, destination), share sheet via FileProvider, `.folio` export = packed copy (optionally without thumbnails). `.folio` import (A-036): `DocumentRepository.importFolio(uri)` copies a picked file into the current folder after validation (new docId when the id already exists in the library, name collision -> " (2)"), and enables the library New menu "Import .folio" entry.
Accept:
- [ ] unit: importFolio copies and indexes a valid file, gives a new docId on id collision, rejects a corrupted file with a typed error
- [ ] screenshot: export sheet
- [ ] device: each format produced from the editor menu
Verify: screenshots + device-tester

### P09-T07 Export fidelity suite
Model: opus medium
Implements: R-FILE-03
Do: golden documents (all object types, fonts, math, tables, PDF-backed) exported and compared (raster diff vs screen render; structural checks for PDF).
Accept:
- [ ] instrumented suite green
Verify: `instrumented.sh :core:pdf`
