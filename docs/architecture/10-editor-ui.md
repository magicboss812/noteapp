# 10 Editor and app UI
Visual language: 11-design-system.md. Reference: docs/design/reference/editor-tools-reference.png.

## Screen structure
Routes: `onboarding` (storage permission), `library` (home), `editor/{docId}` (one or two panes), `settings/*`, `search` (overlay over library), sheets as modal bottom sheets (portrait) or side sheets/popovers (landscape).
Editor layout (landscape, toolbox docked bottom, the default; A-037):
```
┌───────────────────────────────────────────────────────────────────────────┐
│ [Home Pages Grid +Page]                          [Read Split ⋯]           │ nav pill, mode pill
│ [Undo Redo]                                                               │ edit pill
│                  canvas (page stack or canvas mode) on bg.canvas          │
│                  pages pass under every pill, no app bar                  │
│                       [ row 2: tool options        ]                      │ 8 dp above row 1
│ [3/12 | 100%] •       [ Lasso | Pen Highl. ... Attach | ^ grip ]          │ row 1, 25 dp above bottom
└───────────────────────────────────────────────────────────────────────────┘
```
No opaque bar anywhere: every control is a separate floating pill over the canvas (docs/design/notewise/DESIGN.md section 8). The bottom-left info pill shows the page fraction and zoom; the autosave dot sits next to it. Portrait: same pills; the tools group scrolls horizontally with fade edges when it does not fit.

## Toolbar
- Nav pill (top-left, document): Home (back to library, A-035 transition), Page panel (drawer), Page grid overview (full-screen), Add page (after current).
- Edit pill (8 dp below the nav pill): Undo, Redo (disabled state when unavailable; long-press Undo shows the last 5 actions by name).
- Mode pill (top-right): Read mode (see Read mode), Split view toggle, More (rename, page settings, expand to infinite canvas, open beside, export, backlinks, document info).
- Row 1 tools pill: Lasso, Pen, Highlighter, Eraser, Shape, Text, Table, Image, Sticky note, Ruler (toggle), Attachment, then a collapse chevron (hides or shows row 2, flips, persisted) and the grip. Each pill is a 44 dp stadium; every cell is a 36 dp circle inside a 44 dp touch target (A-030). Selected tool = 36 dp accentContainerStrong circle with an onAccentContainerStrong icon. A divider separates the Lasso from the other tools. Tapping the selected tool again opens its tool settings panel.
- Row 2 pill: tool options (see Tool options), centered over row 1, width hugs content.
- Tools are also reachable by keyboard (Alt+1..9, 0) and remember their last options.

## Tool options
Row 2 holds only mode/kind | widths | colors | details (sliders icon); the rest lives in the tool settings panel (A-037), opened by tapping the selected tool again or the details button. Panel: 340 dp wide, centered title + close chip, stroke preview, kind cards, slider block, 44 dp setting rows, colors last, destructive text in `danger` (DESIGN.md section 7). It anchors next to row 2 (bottom/top dock), as a flyout (side rails) or centered (floating); no scrim, one outside tap closes. Tools without a panel row below keep their whole content in row 2. Tapping the selected width or color in row 2 again opens its width or color popover (long-press too). The tilt shading row appears once tilt can be switched off per stroke (D-014); lasso options are stored now and applied by the lasso (P07-T03).
| Tool | Row 2 content | Settings panel |
|---|---|---|
| Pen | 3 width presets (line glyphs of increasing thickness), 5 color dots, add color (+), details | brush kind cards (ballpoint, fountain, pencil, marker), width slider, pressure curve slider, tilt shading toggle if supported, colors |
| Highlighter | 3 widths, 5 colors, add color, details | width slider, "always straight" toggle, colors |
| Eraser | mode (stroke / partial), 3 sizes, details | size slider (2-32 pt), highlighter-only toggle, Clear page (danger, asks first) |
| Lasso | freeform / rectangle, details | filter chips (ink, shapes, text, images) |
| Shape | kind (line, arrow, rectangle, ellipse, triangle, polygon), stroke color, width, dashed, fill (none / tint / solid) |
| Text | font family, size S/M/L, bold, italic, highlight, heading 1-3, bullets, numbers, checklist, math, code, link, alignment |
| Table | rows x columns picker (up to 8 x 8) |
| Image | Gallery, Camera |
| Sticky | 5 colors |
| Ruler | reset angle, hide |
| Attachment | pick file |
Color dot: 22 dp with a 1 dp border; the selected color and width preset get a 4 dp accent dot below them, eraser sizes get a 2 dp accent ring instead (A-030). Groups are split by 2 x 26 dp dividers. Long-press a color or width preset to edit it. Custom colors: HSV square + hue bar + hex field + recent colors.

## Toolbar docking
- Modes: BOTTOM (default, A-037: centered, 25 dp above the bottom edge, slides to ~12 dp above the IME), TOP, LEFT, RIGHT, FLOATING. Bottom/top: row 2 sits on the canvas side of row 1, 8 dp apart. Left/right: rows become vertical rails (row 1 outer, row 2 inner); options open as a flyout next to the rail. A placement stored with the old TOP default moves to BOTTOM once.
- Floating: a compact pill (tools only + a grip handle); drag anywhere; releasing within 48 dp of an edge snaps (spring) to that dock mode; double-tap grip collapses to a single "current tool" button.
- Handedness (settings): left-handed default dock = RIGHT for side docking, popovers open toward the canvas center.
- Persisted per orientation (DataStore). Dock changes animate with FolioMotion spring.
- Grip (A-029): a 44 dp "Move toolbar" grip ends row 1 (top dock: right end; side rails: bottom) and leads the floating pill. Dragging a docked toolbar dims it to 40% while a floating preview pill follows the finger; the release decides the mode. Releases near the bottom edge dock BOTTOM (A-037 replaces A-029's "no bottom dock").
- Floating pill (A-029): grip + tools pill; the tool options row hangs below it (no Home: system back leaves the editor). Collapsed, it shows the grip and the current tool; tapping the tool expands it. Its position is stored as fractions of the free window area, so it stays on screen across sizes.
- Side rails: the options rail sits between the tools rail and the canvas; popovers open as flyouts next to it (top-aligned with the rails). Bottom dock: above row 2, centered; top dock: below row 2, centered. Floating: centered on the canvas.
- Handedness picks which side "side docking" means (right-handed: LEFT, left-handed: RIGHT); switching hands moves side-docked placements to the other side. The settings toggle lands with the settings screen (D-025).
- The nav, edit, mode and info pills stay in their corners in every dock mode; a side rail on the left pushes the nav and edit pills right of it.

## Read mode
- Entered from the mode pill (A-037). The toolbox, nav, edit and info pills slide out with FolioMotion, system bars hide (immersive); only a small exit pill stays top-right.
- Input: pen and finger only pan, zoom and tap links; no ink, eraser, lasso or text focus. Hover ring off.
- Leaves with the exit pill or Back; survives rotation, not closing and reopening the note. Reduce motion: plain fades.

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
- Page indicator: the page fraction ("3 / 12") in the bottom-left info pill (A-037); tap opens a go-to-page field.

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
