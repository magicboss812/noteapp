# 10 Editor and app UI
Visual language: 11-design-system.md. Reference: docs/design/reference/editor-tools-reference.png.

## Screen structure
Routes: `onboarding` (storage permission), `library` (home), `editor/{docId}` (one or two panes), `settings/*`, `search` (overlay over library), sheets as modal bottom sheets (portrait) or side sheets/popovers (landscape).
Editor layout (landscape, toolbar docked top):
```
┌───────────────────────────────────────────────────────────────────────────┐
│ [Home][Pages][Grid][+Page][⋯]   [Lasso Pen Highlighter Eraser Shape Text   │ row 1 (60 dp)
│                                  Table Image Sticky Ruler Attach]  [Split] │
│ [Undo][Redo]        [ contextual tool options pill ]                       │ row 2 (44 dp)
├───────────────────────────────────────────────────────────────────────────┤
│                         canvas (page stack or canvas mode)                 │
│                                                   save-state dot, page x/y │
└───────────────────────────────────────────────────────────────────────────┘
```
Row 2 floats over the canvas (no opaque bar), exactly like the reference. Portrait: row 1 splits into two scrollable groups if needed; the tools group scrolls horizontally with fade edges.

## Toolbar
- Row 1 left pill (document): Home (back to library with shared-element transition), Page panel (drawer), Page grid overview (full-screen), Add page (after current), More (rename, page settings, expand to infinite canvas, open beside, export, backlinks, document info).
- Row 1 tools pill: Lasso, Pen, Highlighter, Eraser, Shape, Text, Table, Image, Sticky note, Ruler (toggle), Attachment. Each pill is a 44 dp stadium; every cell is a 36 dp circle inside a 44 dp touch target (A-030). Selected tool = 36 dp accentContainerStrong circle with an onAccentContainerStrong icon. A divider separates the Lasso from the other tools. Tapping the selected pen again opens its options popover.
- Row 1 right: Split view toggle.
- Row 2 left pill: Undo, Redo (disabled state when unavailable; long-press Undo shows the last 5 actions by name).
- Row 2 center pill: tool options (see Tool options).
- Tools are also reachable by keyboard (Alt+1..9, 0) and remember their last options.

## Tool options
| Tool | Row 2 content |
|---|---|
| Pen | brush kind (ballpoint, fountain, pencil, marker), 3 width presets (line glyphs of increasing thickness), 5 color dots, add color (+), settings (pressure curve slider, width slider, tilt shading toggle if supported) |
| Highlighter | 3 widths, 5 colors, add color, "always straight" toggle |
| Eraser | mode (stroke / partial), 3 sizes, highlighter-only toggle, clear page |
| Lasso | freeform / rectangle, filter chips (ink, shapes, text, images) |
| Shape | kind (line, arrow, rectangle, ellipse, triangle, polygon), stroke color, width, dashed, fill (none / tint / solid) |
| Text | font family, size S/M/L, bold, italic, highlight, heading 1-3, bullets, numbers, checklist, math, code, link, alignment |
| Table | rows x columns picker (up to 8 x 8) |
| Image | Gallery, Camera |
| Sticky | 5 colors |
| Ruler | reset angle, hide |
| Attachment | pick file |
Color dot: 22 dp with a 1 dp border; the selected color and width preset get a 4 dp accent dot below them, eraser sizes get a 2 dp accent ring instead (A-030). Groups are split by 2 x 26 dp dividers. Long-press a color or width preset to edit it. Custom colors: HSV square + hue bar + hex field + recent colors.

## Toolbar docking
- Modes: TOP (default), LEFT, RIGHT, FLOATING. Left/right: rows become vertical rails (row 1 outer, row 2 inner); options open as a flyout next to the rail.
- Floating: a compact pill (tools only + a grip handle); drag anywhere; releasing within 48 dp of an edge snaps (spring) to that dock mode; double-tap grip collapses to a single "current tool" button.
- Handedness (settings): left-handed default dock = RIGHT for side docking, popovers open toward the canvas center.
- Persisted per orientation (DataStore). Dock changes animate with FolioMotion spring.
- Grip (A-029): a 44 dp "Move toolbar" grip ends row 1 (top dock: right end; side rails: bottom) and leads the floating pill. Dragging a docked toolbar dims it to 40% while a floating preview pill follows the finger; the release decides the mode. There is no bottom dock: releases near the bottom edge float.
- Floating pill (A-029): grip + tools pill; the tool options row hangs below it (no Home: system back leaves the editor). Collapsed, it shows the grip and the current tool; tapping the tool expands it. Its position is stored as fractions of the free window area, so it stays on screen across sizes.
- Side rails: the options rail sits between the tools rail and the canvas; popovers open as flyouts next to it (top-aligned with the rails). Top dock: below row 2, centered. Floating: centered on the canvas.
- Handedness picks which side "side docking" means (right-handed: LEFT, left-handed: RIGHT); switching hands moves side-docked placements to the other side. The settings toggle lands with the settings screen (D-025).

## Text tool
- Pen tap with Text tool inside a page body zone: caret at the nearest line/offset of the body flow (creates the body flow if none; blank lines are filled so the caret lands on the tapped line).
- Pen tap outside the body zone (margins, cue column, infinite canvas outside the body): new free text box at the tap, snapped to the grid, focused immediately.
- Finger tap on existing text with Text tool: caret there. Pen or finger tap on text with other tools: nothing (lasso selects boxes).
- Hardware keyboard typing with nothing focused: appends to the current page's body flow (setting "Type into page body", default on).
- Escape or tapping empty canvas with another tool unfocuses. Focus never needs more than one tap.

## Pages
- Page panel (left drawer, 280 dp): thumbnails with page numbers; drag to reorder; per-page menu: duplicate, delete, insert blank before/after, page settings, expand to infinite, move to another document (later).
- Page grid overview: full-screen grid of thumbnails (4 columns landscape, 3 portrait), multi-select for delete/duplicate/move.
- Page settings sheet: size (A3, A4, A5; PDF pages show their size read-only), orientation, template (gallery with previews), spacing preset, paper color (white, cream, light gray, light green, dark), margin line on/off. Apply to: this page / all pages / new pages.
- Add page: after the current page with the current page's spec and background (PDF pages: the document's new-page defaults from "Apply to: new pages", blank A4 lined by default).
- Page indicator chip (bottom right): "3 / 12"; tap opens a go-to-page field.

## New note
Sheet from the library "New > Note" (and Ctrl+N): title field (default "Untitled YYYY-MM-DD"), paper size, orientation, page type (Fixed / Infinite canvas), template gallery with live previews (template x spacing), paper color. "Create" opens the editor with the first page ready and the last used tool. The last choices become the next defaults.

## Split view
- Two panes: side by side in landscape (divider vertical), stacked in portrait (divider horizontal). Divider draggable between 30% and 70%, double-tap resets to 50%.
- Active pane: 2 dp accent outline; the toolbar (single, shared) acts on the active pane; tapping or drawing in a pane activates it.
- Each pane: own session (or read-only view for the same document), own viewport, undo stack, tool state memory.
- Open: toolbar Split toggle opens a document picker for the second pane; library card menu "Open beside"; closing: pane header "x" or toggle.

## Export dialog
Sheet: format (PDF, PNG, Folio file), pages (all, current, range, selection from page grid), PNG dpi (150/200/300), infinite export margin (mm), include page backgrounds (templates) on/off, destination (library `Exports/` default, or "Choose location..." via system create-document, or Share). Shows the estimated size. Progress with cancel.

## Keyboard shortcuts
| Keys | Action |
|---|---|
| Ctrl+Z / Ctrl+Shift+Z / Ctrl+Y | undo / redo / redo |
| Ctrl+N / Ctrl+O / Ctrl+F / Ctrl+W | new note / library / search / close editor |
| Ctrl+S | pack now (shows "Saved") |
| Ctrl+B / Ctrl+I / Ctrl+Shift+X / Ctrl+E / Ctrl+Shift+H | bold / italic / strikethrough / inline code / highlight |
| Ctrl+K / Ctrl+M / Ctrl+Shift+M | link / inline math / block math |
| Ctrl+1 / 2 / 3 / Ctrl+0 | heading 1-3 / paragraph |
| Ctrl+Shift+7 / 8 / 9, Ctrl+Enter | numbered / bullet / checklist, toggle checkbox |
| Tab / Shift+Tab | indent / outdent (lists), next / previous cell (tables) |
| Alt+1..9, Alt+0 | Pen, Highlighter, Eraser, Lasso, Text, Shape, Image, Sticky, Ruler, Table |
| Ctrl+C / X / V / D, Delete, Ctrl+A | copy / cut / paste / duplicate selection, delete, select all (text block or page objects) |
| Ctrl+Plus / Ctrl+Minus / Ctrl+0 on canvas | zoom in / out / fit width |
| PageUp / PageDown, Ctrl+Home / Ctrl+End | previous / next page, first / last page |
| Ctrl+\ | toggle split view |
| Esc | leave text editing / clear selection / close sheet |
| Ctrl+/ | shortcut help sheet |
Text-editing shortcuts apply when a text field is focused; otherwise canvas shortcuts apply.

## Settings
- Library: library folder (read-only path in v1), bin retention, rebuild index, greeting name.
- New pages: default size, orientation, template, spacing, paper color.
- Pen and input: pressure curve, tilt shading (if supported), hover cursor (if supported), pen button action (if supported), highlighter always straight, shape recognition on/off and hold time.
- Toolbar: dock position, handedness, compact floating toolbar.
- Text: default font, default size S/M/L, paragraph gap (0/1 line), type into page body, auto-continue to new pages, block math alignment.
- Export: default PNG dpi, infinite export margin, include templates.
- Appearance: theme (system/light/dark), reduce motion.
- About: version, licenses (fonts, icons, libraries), debug build info.
