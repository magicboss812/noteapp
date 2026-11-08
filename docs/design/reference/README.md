# Visual references
Two marketing screenshots of Notewise, provided by the user as the visual direction (R-UI-01). Use them for layout, density, and mood.
The measured reference is `docs/design/notewise/DESIGN.md` (captured on the tablet, shots in `docs/design/notewise/shots/`, method in `CAPTURE.md`). Where both exist, DESIGN.md wins.

## editor-tools-reference.png
Take:
- Two floating toolbar rows over the page: row 1 split into rounded pill groups (document actions left, tools right), row 2 a centered pill with width presets (three line glyphs), color dots, add-color, settings; undo/redo pill on the left of row 2.
- Thin outlined icons with a light-blue selected state (tinted rounded background + blue icon).
- Dotted paper, generous whitespace, sticky note with a slight rotation and soft shadow.
- The tool set: pen, highlighter, eraser, shape, ruler, image, sticky note, lasso, text, table.
Leave out: video and laser pointer tools (not requested), marketing headline and decorations.

## library-reference.png
Take:
- Left sidebar with Home and collapsible sections; large serif greeting with a gray subtitle; round search button top-right.
- Segmented tabs (All, Favourites, Shared, Bin, Tags) with a light-blue selected chip; sort control and grid/select toggles on the right. Folio uses All, Favorites, Tags, Bin (no Shared).
- White cards with thin borders and 16 dp radius: timestamp + overflow on top, serif title, text preview with mini-Markdown formatting (bold headings, bullets, tables), or a handwritten page preview.
- Yellow folder card with stacked sheets and a note count.
- Very light background with a soft blue glow.
Leave out: AI entry, "Shared", avatar/account, cloud sync indicator, phone layout.

## text-box-toolbar-reference.png
Notewise text box in its selected state, captured by the user on 2026-11-08 (A-057, 10-editor-ui.md#text-boxes).
Take:
- Dark rounded pill toolbar floating above the box: edit (pencil), color circle, font size number, divider, red trash.
- Thin blue outline around the box, a small pill grip with three dots centered on the top border, round knobs at the middle of the left and right border.
Leave out: Notewise's tiny zoom-scaled knob hit areas (Folio uses 48 dp targets at every zoom), the lasso-convert and background buttons.

## feedback-2026-11-08-*.png
User screenshots behind the 2026-11-08 text feedback (P07-T10 to T17): `editing-over-page-gap` (the edited paragraph runs over the gap between two pages), `after-editing` (the same text paginated after leaving it), `selection-offset` (the selection highlight sits about two letters right of the handles).
