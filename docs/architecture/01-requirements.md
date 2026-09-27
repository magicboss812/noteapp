# 01 Requirements
Source: the user's answers to the requirements questionnaire (2026-09-26). IDs are referenced by tasks (`Implements:`). Changes only with user approval (AMENDMENTS.md).

## Device and project (R-DEV)
- R-DEV-01 Target device: Xiaomi Pad 7 (8 GB RAM, 128 GB), current HyperOS, 11.2" 3200x2136 at up to 144 Hz, Xiaomi Focus Pen, Bluetooth keyboard. Portrait and landscape.
- R-DEV-02 Personal use, sideloaded, English UI only, strictly offline, no deadline. Other Android 15+ tablets may work but are not a goal.
- R-DEV-03 Built by Claude Code on Windows 11 and Arch Linux. The user only steers and tests; no Kotlin knowledge assumed.
- R-DEV-04 Claude Code may test on the tablet over ADB but must touch nothing except its own debug app and debug folder.

## Core concept (R-CORE)
- R-CORE-01 Deeply integrated hybrid: typed text is first class next to ink on the same page. Creating text is instant (no slow block setup), text never needs manual fitting to page limits.
- R-CORE-02 Page modes: fixed pages and infinite canvas; a fixed page (e.g. A4) can be expanded into an infinite canvas.
- R-CORE-03 Use cases: lecture/class notes, math and physics derivations, PDF annotation (worksheets, slides, papers), study sheets, brainstorming, journaling, planning.

## Ink (R-INK)
- R-INK-01 Pens: ballpoint, fountain pen (pressure width), pencil (textured), marker, highlighter. Presets for width and color, custom colors.
- R-INK-02 Pressure controls width (and pencil opacity). Tilt is used only if the Focus Pen reports it; otherwise width only.
- R-INK-03 Eraser: whole-stroke and partial.
- R-INK-04 Lasso: select strokes, shapes, text boxes, images; move, resize, rotate, recolor, copy, cut, paste, duplicate, delete, z-order. No ink-to-text conversion.
- R-INK-05 Shapes: shape tool plus snapping when the pen is held at the end of a stroke (line, arrow, rectangle, ellipse/circle, triangle, polygon). Ruler.
- R-INK-06 Only the pen draws. Fingers never draw. Palm rejection.
- R-INK-07 Gestures: one-finger pan, two-finger pinch zoom. No other gestures.
- R-INK-08 Pen latency as low as technically possible (front-buffered wet ink, highest refresh rate).
- R-INK-09 No handwriting recognition.

## Text (R-TXT)
- R-TXT-01 Storage format for text: Markdown.
- R-TXT-02 Elements: headings, bold/italic/strikethrough/highlight, bullet and numbered lists, checklists, quotes/callouts, code blocks, tables, inline and block LaTeX, links.
- R-TXT-03 Every text line snaps to the page template lines regardless of font.
- R-TXT-04 Inline LaTeX never changes line spacing or offsets following lines.
- R-TXT-05 Very long text stays fast (editing and scrolling).
- R-TXT-06 Line spacing: global per page (the template grid). Per-paragraph spacing is backlog (B-03).
- R-TXT-07 Both free-placed text boxes and a flowing page body that continues onto following pages automatically.
- R-TXT-08 Fonts: bundled Google Fonts selection plus import of custom font files.

## Pages (R-PAGE)
- R-PAGE-01 Templates: blank, lined (narrow/college/wide), grid, dotted, Cornell, graph with axes, music staff, planner (daily, weekly), and custom templates from PNG or PDF.
- R-PAGE-02 Sizes A3, A4, A5; portrait and landscape; per-page override of size, orientation, template, paper color.
- R-PAGE-03 Dark mode changes UI only. Paper and ink colors stay unchanged unless the user changes the paper color.
- R-PAGE-04 Infinite canvas export contains only the used area (all objects) plus a border margin.

## Media (R-MED)
- R-MED-01 Import and annotate PDFs, target up to about 100 pages.
- R-MED-02 Images from gallery and camera; crop, rotate, resize. No document scanning.
- R-MED-03 No audio recording.
- R-MED-04 Web links and file attachments inside notes.

## Organization (R-ORG)
- R-ORG-01 Nested folders, documents with pages, tags.
- R-ORG-02 Links between notes with backlinks.
- R-ORG-03 Search over titles and typed text only.
- R-ORG-04 Library with grid view, favorites, sort by recent (default), bin with restore.

## Files (R-FILE)
- R-FILE-01 User-visible library folder so files can be copied to other platforms.
- R-FILE-02 One self-contained file per document in a custom format holding text, strokes, shapes, objects, and embedded images that other people can see (open container).
- R-FILE-03 Manual export only: PDF, PNG, native `.folio`. No sync in v1.
- R-FILE-04 Other devices: read-only via exported PDF for now; a desktop client may come later, so the format stays platform-neutral and documented.
- R-FILE-05 Undo/redo up to 50 steps.
- R-FILE-06 No app lock.

## UI (R-UI)
- R-UI-01 Modern look heavily inspired by Notewise (reference screenshots in docs/design/reference), smooth animations.
- R-UI-02 Toolbar can dock top, left, right, or float and be moved; handedness setting.
- R-UI-03 Split screen: two own notes side by side, and a PDF next to a note.
- R-UI-04 Portrait and landscape with adapted layouts.
- R-UI-05 Bluetooth keyboard shortcuts.

## Performance (R-PERF) (numbers in 12-performance.md#budgets)
- R-PERF-01 Fast cold start and navigation.
- R-PERF-02 Fast document open and lazy page loading.
- R-PERF-03 Minimal ink latency and main-thread cost per input event.
- R-PERF-04 Smooth pan/zoom at 144 Hz on dense pages.
- R-PERF-05 Fast typing and layout on very long text.
- R-PERF-06 Smooth 100-page PDFs.

## Non-goals (v1)
Sync, handwriting recognition, audio, video, laser pointer, collaboration, app lock, cloud features, phone layouts. See docs/plan/BACKLOG.md.

## Answers interpreted by the planner (confirm or amend)
- Q23 "maybe global first": one grid unit per page drives all text line spacing.
- Q33 "yes" to organization: nested folders + multi-page documents + tags; no sections inside documents.
- Q51 unanswered: multi-module Gradle project (see ADR-012).
- Tools seen in the reference screenshot but not requested (video, laser pointer) are excluded.
