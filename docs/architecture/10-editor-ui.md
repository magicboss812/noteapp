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
| Shape | kind (line, arrow, rectangle, ellipse, triangle, polygon), 3 widths, colors, add color, details (A-064) | kinds, width slider, dashed toggle, fill (none / tint / solid), constrain toggle, colors |
| Text | nothing focused (A-051): font family button (opens the panel), size S/M/L, details; a focused block: the formatting bar (bold, italic, highlight, heading 1-3, bullets, numbers, checklist, math, code, link, alignment) | size S/M/L, free size slider with the body line box in grid lines (A-039), font families by group, each named in its own face, "Import font" (.ttf/.otf) |
| Table | rows x columns picker (up to 8 x 8) |
| Image | Gallery, Camera (A-067: the Image tool is a lasso that selects images only; one selected image leads the action bar with Crop, which opens the full-screen crop and turn screen) |
| Sticky | 5 colors |
| Ruler | reset angle, hide (A-069: no options row; tap the ruler's angle readout to level it, the toolbar toggle or Alt+9 hides it) |
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
- As built (A-050, replaced in P07-T12 by A-059): a Text tool tap that hits no box outline (8 dp slop) makes a new auto width box at the tap, focused; there are no filled blank lines and no body frames added any more. Typing with nothing focused goes to the end of the last box on the page (the one whose outline reaches lowest), else into a new box on the first line of the page text zone; the first keys are their own undo step. An empty box the editor made is removed when the focus leaves it, without an undo step. "Type into page body" is always on until P11-T01 (D-035). No hover ring with the Text tool; dashed outlines of all boxes on screen instead.
- Superseded by #text-boxes (A-057, P07-T10 to T17) where they disagree: placement by tap, body text capturing the whole page, filled blank lines, box snapping.

## Text boxes
Target design from the user feedback of 2026-11-08 (A-057; reference shots in `docs/design/reference/`: `text-box-toolbar-reference.png`, `feedback-2026-11-08-*.png`). "Box" here means one text flow with its frames (a free box, or the body flow of older notes); a box holds Markdown blocks.

Model and placement (P07-T12)
- Every text flow is a box with a left and right border (page pt), its top on a line and its lines below. The body flow of an existing note is a box in fill width. Any box whose lines pass the bottom of the page's text zone continues on the next page between the same borders from the zone top (a continuation page is added after the last page, removed again when empty), also while it is edited. Infinite pages stay unbounded.
- Hit area of a box on a page: its borders by its used lines on that page (at least one line), plus 8 dp slop. Nothing else on the page belongs to a box.
- A Text tool tap (pen or finger) outside every box creates an empty box in Edit with the caret at the tap. Line: the one whose band holds the tap (baseline on the rule below the tap, raised by the lift). The first glyph starts at the tap x, so the left border sits one inner padding (0.5 U) left of it. The tap point is stored as the box's anchor and moves with the box.
- Auto width (a new box): the borders start tight around the caret (padding on both sides) and follow the typed text. The growing side depends on the alignment: Left and Justify grow to the right, Right grows to the left, Center grows both sides alike around the anchor. A border stops at the page text zone edge (Center: as soon as either side reaches it); from then on lines wrap. Deleting text shrinks the box again, down to the tight caret width. Auto width ends when the user drags a knob (the box keeps fixed custom borders) and is the custom set the fill width button returns to.
- Text never leaves the page: no border lies outside the page text zone, and lines past the page bottom continue on the next page (also while editing).
- An empty box is removed when it loses the selection; its creation and removal leave no undo step.
- With the Text tool active every box on screen shows a dashed outline like Notewise's: 1 dp, neutral gray, clearly visible on the paper but quieter than the selected outline (overlay only, never in tiles, thumbnails or export), so it is always clear whether a tap lands inside a box.
- Keyboard typing with nothing selected goes to the end of the last box on the current page, or makes a box on the first line of the page's text zone. Typing with a box in Preview goes to the end of that box (A-060).

Modes (P07-T13)
- Not selected. A tap on a box (Text tool with pen or finger; a finger with another tool outside read mode) selects it in Preview.
- Preview: solid accent outline, top grip, left and right width knobs, box toolbar. Keyboard hidden, no caret, no text selection; the field is not focused, so HyperOS never takes the pen (A-045). A drag that starts anywhere inside the box or on the grip moves the box, also after a press-and-hold.
- Edit: the same outline, knobs and toolbar (Edit button active), keyboard up, caret always visible and blinking (also in an empty box, at the line height of the box's style), text selection by touch and keys.
- Transitions: only the Edit button enters Edit (caret where the box was last tapped, else at the end of the text, A-060); a tap on a selected box does not (it may come later if the button gets tiresome, B-13). The Edit button in Edit, the system Back gesture or Escape go Edit -> Preview (with the soft keyboard up, Back closes it and the closing keyboard leaves Edit, A-060); Back or Escape in Preview ends the selection. A pen with an ink tool hovering over a box in Edit drops it to Preview (A-045). A tap with another tool outside every box ends the selection; a Text tool tap outside every box ends it and starts a new box there.
- Text selection exists only in Edit. Outside Edit no gesture selects words or letters: a long-press or press-and-hold on a box in Preview starts a move like any drag, never a selection.

Box toolbar (P07-T13, buttons filled in by T15 to T17)
- Dark rounded pill above the box top, the same screen size at every zoom, centered on the box; it flips below the box or clamps to the screen edge when there is no room. Buttons left to right: Edit (pencil), Color (filled circle of the text color), Font size (number in pt), Alignment (icon of the current one), Fill width (highlighted while on), divider, Delete (red trash).
- Delete removes the box with its text as one undo step.

Move (P07-T13 drag, P07-T14 keys)
- Drag with finger or pen in Preview: x follows smoothly (no column or millimeter snapping); y moves in whole line steps (U) and keeps the lift, so the baselines stay on the rules; outline and text follow live, also onto another page; one undo step on release. A drag clears a vertical offset.
- Hardware arrow keys in Preview. Left/Right move smoothly: a press moves 1 pt at once, while held the speed is vmax * min(1, (t / 0.8 s)^2), vmax 900 dp/s on screen (tunable constants in one place). Up/Down move one line per step, held steps follow the same curve up to 15 lines/s. Shift+Left/Right as Left/Right. Shift+Up/Down move smoothly like Left/Right and leave the box between the lines (a vertical offset of the whole box, A-057 grid exception). The next Up/Down without Shift first snaps to the nearest line in that direction, which removes the offset. One undo step per key hold; the viewport keeps the box in view like the caret. Keys keep the box inside its page's text zone; the offset shifts the box on its first page, continuation pages start on the rules (A-061).

Width (P07-T15)
- Knobs at the middle of the left and right border: drawn 10 dp, touch and pen target 48 dp at every zoom; the pen on a knob or the grip never writes. A knob moves its border smoothly (no snapping), at least 20 mm wide, inside the page text zone; the text rewraps live; one undo step; it ends auto width. In Center alignment both borders move symmetrically around the anchor.
- Fill width keeps two border sets, custom (auto width or fixed borders) and fill; fill starts at the page text zone edges (not the paper edge). The button switches the active set; a knob changes only the active set; the other keeps its last borders in page coordinates. Sequence: custom -> press -> fill (zone edges the first time, later the last adjusted fill borders) -> press -> last custom borders -> press -> last fill borders.

Alignment (P07-T16)
- Per box: Left, Center, Right, Justify. Paragraphs, list items and quotes take it (Justify: every line but the last of a paragraph spans both borders); headings take Left, Center or Right (Justify reads as Left); code, tables and block math keep their own. Visible while editing too.
- Auto width: switching alignment keeps the current borders; only the side that grows changes (see Model and placement), so Center keeps the text's middle on the anchor by itself.
- Fixed borders: Left, Right and Justify never change the borders. Center (outside fill width) saves the current borders and sets them symmetric around the anchor: half width = the larger of the anchor's distances to the current borders, limited so both borders stay inside the page. The text's middle then sits on the spot where the box was created. Leaving Center for any other alignment restores the saved borders. A box without an anchor (older boxes, the body flow) uses its middle. In fill width, Center centers the text between the fill borders without moving them. As built details (list labels follow the first line, knob center, minimum width at the zone edge): A-062.

Font size and color (P07-T17)
- The size number opens a popup with S, M, L and the Text panel's free size (A-051); the color circle opens the pen palette plus Auto (today's near-black on light paper, near-white on dark). Both apply to the whole box in Preview without opening the keyboard, keep the grid invariant, and are one undo step each.
- As built (A-063): the size popup is minus, S, M, L, plus (minus and plus step the free size by 0.05 cap fraction and keep the popup open); the number is the body font size in pt. The color rides on the text runs, so every painter (tiles, thumbnails, export, overlay) uses it without a relayout.

Editing look (P07-T10, P07-T11)
- Text being edited is drawn from the same paginated page layout as all other text: it breaks at the page bottom and continues on the next page while typing. Line breaks match the unedited text; only the dimmed Markdown markers and raw math of the block holding the caret differ. The EditText stays the IME connection but draws nothing; caret, selection highlight and handles come from the page layout, so the highlight sits exactly under the handles. Entering or leaving Edit never flashes, and the keystroke budget (R-PERF-05) holds.
- Touch selection, in Edit only: a tap places the caret; a long-press selects the word under the finger (haptic tick) and a drag after it extends by words; a double tap selects a word; the handles (44 dp) drag by letter, across blocks and pages; a one-finger drag that starts neither with a long-press nor on a handle pans the page. A floating menu above a selection offers Cut, Copy, Paste and Select all.

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
