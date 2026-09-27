# 00 Overview

## Product
Folio is a native Android note app for one device (Xiaomi Pad 7 + Focus Pen + Bluetooth keyboard). Handwriting and typed Markdown text are equal citizens on the same page. Pages are fixed paper (A3/A4/A5) or infinite canvases, and a fixed page can expand into an infinite one. Everything works offline. Documents are single `.folio` files in a user-visible folder.

## Principles
1. **Input latency is sacred.** Pen events go straight from the canvas view to androidx.ink. No Compose recomposition, no allocation, no IO on that path.
2. **Everything snaps to the page grid.** Text line boxes are integer multiples of the template grid unit U, and baselines sit on rule lines, regardless of font or inline math.
3. **Files are the truth.** `.folio` files in the library folder are authoritative. The Room index, thumbnails and caches are disposable and rebuildable.
4. **Never lose data.** App-private working copy with 1 s autosave, atomic packing into the user file, crash recovery, conflict copies.
5. **One renderer.** The same `PageRenderer` draws screen tiles, PNG export and PDF export.
6. **Offline by construction.** No INTERNET permission. Fonts, icons and math fonts are bundled.
7. **Measured, not guessed.** Risky choices go through spikes on the real tablet (phase P01) before code depends on them.

## System view
```
 Compose UI (library, editor chrome, sheets)            feature:* modules
        |  UiState / intents
 EditorSession ---- UndoManager ---- Selection/Tool state      (feature:editor)
        |  EditCommand -> new immutable Document (StateFlow)
 core:model  <----  core:format (.folio codec)  <---- core:storage (working copy, packer, index)
        |
 CanvasHostView (AndroidView)
   ├─ BackgroundTileLayer   <- core:render (template) + core:pdf (PDF raster)
   ├─ ContentTileLayer      <- core:render PageRenderer (ink via core:ink, text via core:text)
   ├─ Overlay (Compose)     <- focused text field, selection, lasso, ruler
   └─ InProgressStrokesView <- core:ink InputRouter (wet ink, front buffer)
```

## Main flows
- **Pen stroke:** MotionEvent -> InputRouter -> InProgressStrokesView (wet) -> onStrokesFinished -> `AddObjects` command -> Document StateFlow -> content tiles updated for the stroke bounds -> after that frame, wet stroke removed -> autosave entry write after 1 s idle.
- **Typing:** key/IME -> focused block text field -> `EditFlow` command (coalesced) -> block re-parse + re-layout -> flow redistribution -> tiles of affected lines invalidated.
- **Open:** library card -> DocumentSession opens or reuses working copy -> manifest + visible pages decoded -> first tiles rendered (background, then content).
- **Save:** entry autosave (1 s) -> pack on close/onStop/30 s -> atomic rename -> index update.

## Glossary
| Term | Meaning |
|---|---|
| Document | One `.folio` file: metadata, pages, flows, assets |
| Page | Fixed (paper size) or infinite; has background (paper color, template, optional PDF page) and z-ordered objects |
| Object | Stroke, shape, flow frame, image, sticky note, attachment |
| Flow | A Markdown text body. Its lines are distributed over one or more frames |
| Frame | A rectangle on a page that shows part of a flow (page body, free text box, sticky note) |
| Block | One Markdown block of a flow (paragraph, heading, list item, table, math block, ...) |
| U (grid unit) | Vertical spacing of the page template in points; line boxes are k*U |
| Tile | 512 px bitmap of rendered page content at one zoom bucket |
| Pane | One editor instance; split view has two |
| Working copy | Unpacked document in app-private storage used while editing |
| Pack | Writing the working copy atomically into the `.folio` file |

## Document map
| File | Topic |
|---|---|
| 01-requirements.md | Requirement IDs (R-*) from the user's answers |
| 02-modules.md | Modules, dependencies, build logic, editor state |
| 03-document-model.md | Types, units, commands, undo, sessions |
| 04-file-format.md | `.folio` container, protobuf schema, write protocol |
| 05-canvas-rendering.md | Viewport, layers, templates, tiles, dry handoff, canvas mode |
| 06-ink-input.md | Input routing, wet ink, brushes, erasers, shapes, lasso, ruler, stylus capabilities |
| 07-text-engine.md | Grid unit, line box, fonts, blocks, flows, editing, math, tables |
| 08-pdf.md | PDF import, rendering, export, PNG export |
| 09-storage-library.md | Library folder, permission, index, search, bin, links, thumbnails |
| 10-editor-ui.md | Screens, toolbar, tool options, pages, split view, shortcuts, settings |
| 11-design-system.md | Tokens, typography, icons, components, motion, reference mapping |
| 12-performance.md | Budgets, threading, memory, measurement |
| decisions.md | ADRs |
