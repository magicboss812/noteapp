# Notewise design system (measured reference for Folio)
Source: notes/G1..G6.md (Notewise 4.0.4 on Xiaomi Pad 7, landscape 3200x2136 px, 440 dpi, 1 dp = 2.75 px, German UI translated). Hex values come from `px` samples; ranges `#A..#B` are sensor/grain spread, use the first value unless noted. `(~)` = single uncertain measurement.

## Scope reminder (read first)
Every component below is a Notewise component, recorded as a visual reference. Folio adopts the look (colors, type, shapes, spacing, states), not Notewise's feature set. Do not add new mechanics or subjects just because Notewise has them. More variants of something Folio already has (more shapes, more pen kinds, more templates) are fine.
Notewise-only, never rebuild in Folio: paid/upgrade buttons, subscriptions, upsell banners and cards, sign-in, Notewise AI (sparkle buttons, AI settings), cloud sharing ("Shared" tab, Share tile), sticky notes (tool, row, panel, palette), recorder (mic), zoom panel, tape/washi brush, emoji/stickers, web capture tool, document scan, laser pointer, handwriting-to-text, lock note / passwords, Notewise file import/export, quick switch panel, outline and bookmarks filters, "infinity" badge.
Folio has (style them from here): library, folders, tags, favorites, bin, search, pens/highlighter/eraser/lasso/shapes/ruler, text, images (gallery + camera), tables inside Markdown text, templates, page sizes, paper colors, export PDF/PNG/.folio, split view, settings, light/dark theme. Toolbar docking top/left/right/float is Folio's own (R-UI-02; Notewise only has Top/Bottom): keep the behavior, apply the pill styling.

## 1. Principles
- Dark-first chrome around light paper: near-black backdrops, one cool gray surface tone for every floating layer, a single soft blue accent (#6B99F0 dark / #4B85E0 light).
- Everything floats: the editor has no app bar; tools live in separate 44 dp stadium pills with a 1 dp border. Library and settings are calm and spacious.
- Selection is always blue and quiet: a dark-blue fill circle, a 4 dp blue dot, a 2 dp blue ring, or blue label + trailing check. No row tints.
- Character comes from three details: serif display/card titles (greeting, card names) against Roboto-like sans everywhere else, a blue-slate gradient band with a faint dot grid at the top of the library, and saturated folder-colored cards with fine grain.
- Medium density: 36 dp toolbar cells, 42 dp menu rows, 44-48 dp setting rows, generous 16-24 dp insets. Destructive actions use soft salmon text, never red fills.

## 2. Color tokens
| Token | Dark | Light | Use |
|---|---|---|---|
| bg.library | gradient #213044 (top) -> #141519 -> #0A0A0B, dot grid ~1 dp / 22 dp pitch | white #FEFEFF with top band #88AFE6 -> #CDE3EE -> white by y 220 dp, white dots | library content area |
| bg.page | #070708 | #FAFAFB | settings page, folder view (#010101 + glow #18222F at top) |
| bg.sidebar | #1A1A1B (rail #151515) | #F3F3F3 | library sidebar, no divider |
| bg.canvas | #252526 | #EEEEEF | editor backdrop around pages |
| bg.overlay | #1C1E21 | (~) | full-screen search |
| surface | #222428 (#1D1F22..#26282C) | #FAF9FA | menus, popovers, tool panels, dialogs |
| surface.dialog | #232529 | (~) | large centered cards (overview, template, export) |
| surface.header | #1E2023 | #FEFDFE | note card header, overview header |
| surface.toolbar | #292C31 (row 1 #26292E, row 2 #2C2F35) | #F9F9FA | editor pills |
| surface.bar | #1C1C1C..#232324 | (~) | on-canvas selection/text context bars |
| surface.inset | #1D1F22 (#191919 in palette editor) | #FCFCFD | list tiles inside a dialog or card |
| surface.group | #2B2E34 | (~) | grouped list cards, info cards, value capsules #24272C |
| surface.tinted | #333643..#383C49 | (~) | inline option cards, size panel, tag name field #34394A |
| surface.settingsCard | #34394A (#373C4D top) with grain | #EDF0F7 (#F0F3FA top) | settings section cards |
| surface.multiBar | #2F363F | (~) | multi-select action bar |
| border | #4D4F57 (#46484F..#50525A) | #EBEBEC | pills, note cards, popover cards, outlined containers |
| divider | #494B52 | #E6E6E6 | menus, toolbar (2 dp wide), card header line |
| text.primary | #E1E1E8 (#DBDBE1..#E6E6EA) | #1B1B1F | labels, titles, menu items |
| text.secondary | #C6C8D1 | #4C4D55 | subtitles, inactive nav, section labels #B9BBC3 |
| text.tertiary | #8D8D93 | (~) | timestamps, counters, hints #8F909C |
| text.disabled | #67686D (labels), #6F7177 (icons), #8E8E94 (settings rows) | light gray | disabled buttons/rows |
| icon | #D7D7DD (top pills), #C1C3CC (toolbar rows) | #1F1F23..#343438 | icons; = text color elsewhere |
| accent | #6B99F0 (#6795EC..#72A0F8) | #4B85E0 (#4C86E1) | selected text/icons, checks, focused field border, text buttons, slider active, indicator dots |
| accent.fill | #6997EE (FAB), #72A0F8 (primary buttons) | #4B85E0 | FAB, filled primary buttons, checkbox fill |
| onAccent | #102B6E (#0E296C..#132E72) | #FFFFFF | label/check/thumb on accent fills |
| accent.container | #28395A (chips, toggles) | #E6EEF5 | selected filter chip, select-mode button |
| accent.containerStrong | #23496E (#20466B..#254B70) | #C6DBF7 (icon #12263B) | selected tool circle, segmented selected segment, selected chip in dialogs (label #BDD1EA) |
| accent.tile | #2B3C5D (#233454) | (~) | selected kind/export tiles, overflow action tiles |
| accent.track | #22486D | (~) | slider inactive track in popovers, dark-blue icon buttons |
| nav.selected | #2C2C2E | #DEDEE2 | selected sidebar pill (text/icon = accent) |
| closeChip | #2C2C2E..#353538, glyph #8E8F99 | (~) | 24 dp circular close buttons |
| danger | #F3B8B1 (#EFB4AD..#F4B9B2) | #D4504A | destructive labels + icons |
| canvas.selection | #4997F3 | #4997F3 | lasso box, text box outline; handles white #FDFDFE |
| search.highlight | #F3F350 behind #080809 | same | match highlight |
| scrim | black ~33-45% | same | modal dialogs only (rename, move, export, color picker) |
| paper | #FCFCFD, grid lines #CACACA..#D9D9D9 | same | page, never themed (R-PAGE-03) |
Palettes. Pen quick row: #010101 #ED884C #904A65 #8AB73D #6BA0D7 #CB4871 #2B4C9E. Highlighter: #CA4770 #EAD162 #8BB83D #476C77 #881DED. Popover palette: #040404 #B6261A #5DC4F2 #64CD44 #F5CB4E #ED884B #891EEE (stored #D10000 etc. are toned down for display on dark chrome (~)). Tag colors: #6F9BF0 #96D785 #EABE4D #ED983D #E36F63 #D37AF6 + rainbow custom dot. Folder colors (dark): #74C662 #4088A6 #7C1F45 #AB7C86 #DB6945 #EABE4D #430D73 #4CA2C8 #EB6CA6 #BA5522 #96244F #479A5D; light theme shows them brighter (Geo #74C662 -> #98ED85, Mathe #4088A6 -> #5BACC9). Paper colors: #FAFAFB #F1EFE4 #CFC1A4 #DDE3ED #363637 #0B0B0C.

## 3. Typography
All sans = Roboto-like; serif = Literata / Source Serif-like (best guess). Sizes in sp.
| Style | Family | Size | Weight | Line | Use |
|---|---|---|---|---|---|
| display | serif | 32 (dark) .. 36 (light) (~) | bold/semibold | 42 dp | library greeting |
| headline | sans | 22 | bold | 30 dp | settings section titles |
| title | sans | 20 | regular | | dialog titles (centered), app bar title |
| titleSmall | sans | 17-18 | medium | | panel titles, overview header, quick switch |
| cardTitle | serif | 15-16 | bold | 16-17 dp, max 2 lines | note and folder card titles (list view uses sans 16 regular) |
| body | sans | 16 | regular | | menu items, field text, dialog rows |
| bodyMedium | sans | 15-16 | medium | | buttons, chips, editor menus, sort label |
| label | sans | 15 | regular | 18-19 dp | setting rows, subtitles, empty-state caption |
| labelSmall | sans | 13-14 | regular/medium | | tile labels, meta lines, multi-bar labels (14 medium) |
| caption | sans | 11-12 | regular | 13 dp | card timestamp/count, counters, menu subtitles |
| toolbarValue | sans | 16 | regular/medium | | zoom "218%", font size "12" in row 2 |

## 4. Spacing and layout grid
- Base unit 4 dp; used steps 4, 8, 12, 16, 20, 24, 28. Floating chrome sits 8 dp from screen edges and 8 dp apart; panels/dialog content 16-24 dp insets; settings cards 20 dp text inset.
- Touch targets: toolbar cells 36 dp, menu/close buttons 40-48 dp, library icon buttons 52 dp, setting rows 48 dp.
- Library: sidebar 280 dp (rail 80 dp); content inset 16 dp; square cards 195 dp (191 dp with rail), gutter 24 dp, 4 columns (5 with rail); list view 2 columns of 100 dp rows, 10 dp gap.
- Dialogs: standard width 560 dp; compact 360-476 dp; large 800x708 dp. Popovers/panels 335-340 dp wide. Menus 200 dp wide.
- Settings: one centered column 663 dp wide, cards 8 dp apart, first card at ~107 dp.

## 5. Shape, borders, elevation
- Radii: stadium (height/2) for toolbar pills, chips, nav pills, search capsule, buttons, value capsules, action bars; 15-16 dp note/folder cards and standard dialogs; 18-20 dp popovers, tool panels, color dialogs; 24 dp (~) compact Notewise-style dialog; 12 dp menus, grouped cards, option cards; 8-10 dp tiles, preset cards, list rows, settings cards (8 (~)); 4 dp text fields, small thumbnails; 2 dp checkbox.
- Menus sharing an edge with their anchor keep that corner square (add menu (~)).
- Borders: 1 dp `border` on pills, note cards (folder cards have none), preset cards, outlined containers; 2 dp accent for focus and selection.
- Elevation: none visible on dark backgrounds. Menus/popovers/panels cast a soft shadow (~4 dp offset, ~8 dp blur, low opacity), visible on paper and in light theme; light toolbar pills have a ~4 dp soft gray shadow.

## 6. Iconography
- Lucide-like outline icons, rounded caps and joins. 24 dp / ~2 dp stroke in toolbars, library and sidebar; 20 dp / ~1.5-1.75 dp stroke in menus and inline rows; 16 dp for chevrons in fields and info icons.
- Color = the text color of their row; disabled icons #6F7177. Selected tool icon tints light blue on the #23496E circle (light: navy #12263B on #C6DBF7). A few filled glyphs: lock body, page-layout icons, blue sparkle (AI, Notewise-only).

## 7. Components (all Notewise; adopt styling only)
- Toolbar pill: 44 dp tall stadium, `surface.toolbar`, 1 dp `border`, 36 dp cells with 24 dp icons, dividers 2 dp x 26 dp `divider`. Width hugs content and re-centers over row 1 when it morphs.
- Toolbar row 1 (tools): ~748 dp; fixed lasso at left | divider | scrolling tools | divider | collapse chevron (flips down/up, hides row 2). Selected tool = 36 dp `accent.containerStrong` circle.
- Toolbar row 2 (tool options): 8 dp above row 1; per tool: mode/line type | widths | colors | details (sliders icon). Selected width/color = 4 dp accent dot 4 dp below the cell; eraser sizes = 2 dp accent ring; lasso modes = accent icon tint only. Width glyphs: bars of growing thickness (~3/5/8 dp). Color dots ~20-22 dp.
- Editor top pills: nav pill 188 dp (home, overview, add page, search, ...), edit pill (undo, redo, finger mode) 8 dp below; right pill (mode, more). Active mode = 36 dp circle #4E5058 (light #EDEDEE). Bottom-left info pill 127x43 dp: page fraction (current over rule over total, total #8F9196) | zoom %.
- On-canvas bar (lasso actions, text context): 40 dp stadium, `surface.bar`, 1 dp `border`, 20 dp glyphs at 32 dp spacing, 8 dp above/below the target; delete icon in `danger`. Lasso: 1 dp `canvas.selection` box, 8 dp white corner handles with 1.5 dp blue ring, 20 dp rotate icon outside the bottom-right corner; no dimming.
- Popover (color, width): anchored ~6 dp above row 2, centered on the tapped item; `surface`, radius 18 dp, padding 16 dp. Opened by tapping the already-selected item; tap outside only closes. Color popover 335x104 dp: title row (+, edit list icons) and 36 dp dot cells; selected dot = 26.5 dp fill inside a 32 dp ring #46484F; rainbow custom dot after a divider. Width popover 340x160 dp: 3 preset cards 97x72 dp (1 dp border, preview dot 2.5/5/9 dp, label) + slider row.
- Tool settings panel: 340 dp wide, up to full height, no scrim, anchored near the details button. Title row 48 dp (centered title + 24 dp close chip), preview 250x90 dp, kind cards 97x60 dp (selected: 2 dp accent border, `accent.tile` fill, accent icon+label), slider block, divider, 44 dp setting rows (switch / value capsule / dropdown), color section last. Destructive text button in `danger` (e.g. clear page).
- Slider (M3 expressive): active track accent, inactive `accent.track` (or #4E5052), track 6 dp (popover) to 16 dp (dialog), thumb = 4 dp wide accent bar (16-44 dp tall) with ~6 dp gaps, stop dot at the end; "-" / "+" buttons and a value capsule 64x32 dp.
- Switch (M3): 52x32 dp. On: accent track, 22-24 dp thumb `onAccent` (light: white). Off: track #474D5B (light #DEE6F1), 2 dp outline #92939D (light #73747D), 14-16 dp thumb in outline color.
- Segmented buttons: (a) outlined M3 segments 44x34 dp, 1 dp #91929C outline, selected `accent.containerStrong` + white icon; (b) chip pairs 32 dp, radius 6-8 dp, 8 dp apart, selected #254B70 + leading check + label #BDD1EA, unselected #242C39.
- Filter chips: 36 dp stadium, 16 dp side padding, hugging text, no gap besides padding; only the selected chip has `accent.container` fill + accent label; others transparent `text.primary`.
- Buttons: FAB 48 dp circle `accent.fill` + white plus, 16 dp from right, 48 dp from bottom. Primary filled pill 48 dp, `accent.fill` (#72A0F8), `onAccent` medium label; split variant with 1 dp #466DBC divider + chevron. Text buttons 48 dp tall, no fill, `text.primary` medium (dialog actions, right-aligned, 8 dp apart, 22-24 dp from edges) or accent (settings). Icon buttons 36-52 dp, no fill. Close chip 24 dp circle in a 48 dp target. Disabled label #67686D.
- Color dots: 20-22 dp in rows, 36 dp in tag dialog (40 dp pitch, selected = white check, no ring), 44 dp paper swatches (52 dp pitch, selected = 2 dp accent ring outside a 2 dp gap). Shape colors as 20 dp rings; "no fill" = dashed circle with slash.
- Menus: 200 dp wide, `surface`, radius 12-16 dp, 6-8 dp vertical padding, 42 dp rows, optional 20 dp leading icon at 12-20 dp inset, label ~14 dp after the icon, no dividers inside a group (1 dp inset divider between groups). Selected = accent label (+ icon) + trailing 20 dp check. Destructive item last, `danger`. No scrim; anchored to the trigger, may overlap neighbors.
- Dialogs, Material style (rename, new folder, move, export): 560 dp, radius 16 dp, `surface`, scrim, centered title 20 sp, outlined field 56 dp (radius 4 dp, focused 2 dp accent border, counter "n/100" caption right below), text-button actions bottom right. Rename opens with the whole name selected and the IME up (dialog moves above it). Confirm disabled while empty.
- Dialogs, Notewise style (new tag, palette editor): centered, radius 20-24 dp (~), title centered + close chip top right, centered content, filled accent pill button. Folio picks one style per purpose: Material for text entry + confirm, Notewise style for pickers.
- Large card dialogs (overview, template): 800x708 dp, `surface.dialog`, header 56 dp `surface.header` + 1 dp divider, header actions 40 dp; no scrim on overview, scrim on export.
- Text field variants: outlined (above); filled (export range: #404653, 1 dp bottom line #91929C, 56 dp); small number fields 50x26 dp #1F1F20 radius 6 dp; pill field (tag name) #34394A.
- Note card: 195 dp square, radius 15-16 dp, 1 dp `border`. Header 66 dp `surface.header`: timestamp caption #8D8D93 at 10-12 dp inset, serif cardTitle `text.primary` below (2 lines, ellipsis), overflow "..." (40 dp target, #8C8C91) at top right; 1 dp divider; page preview fills the rest edge to edge, clipped by the radius. Bin badge: 26 dp pill (~), fill #1D1D1D, accent caption ("13 days"), centered 8 dp above the bottom. Selection mode: "..." becomes a 28 dp circle (unselected 1.5 dp light ring; selected accent fill + dark check); card unchanged.
- Folder card: 195 dp square, radius 15 dp, no border, fill = folder color with fine grain. Header 66 dp: count caption (title color at 45% alpha) then serif title (dark #2E2D2B on light colors, white on dark), "..." at right. Below: two stacked page previews (back dark, front white 173x125 dp, radius 8 dp), lower ~40% covered by a translucent pocket in a lighter folder tint with a wavy top edge.
- List row (list view): 100 dp tall, 100 dp thumbnail (radius 11 dp, 1 dp border), 13 dp gap, count over sans title, overflow 48 dp at the end; no background or dividers.
- Sidebar: 280 dp, `bg.sidebar`; collapse toggle (panel-left icon, 52 dp) top right; nav pills 250x42 dp, 15 dp inset, 47 dp pitch, selected `nav.selected` + accent icon/label; "Tags" header 16-18 sp medium with "+" button; settings gear at bottom left. Collapsed: 80 dp rail, 50x42 dp icon pills.
- Library header: serif greeting, sans subtitle, chip row (or breadcrumb: home icon 52 dp, chevron 16 dp, current folder in accent), right-aligned sort button (label + arrow icon, label = current sort), view toggle, select-mode toggle (selected = 52 dp `accent.container` circle with accent check).
- Search: entry = 48 dp capsule top right (#202225 fill, 1 dp border, light: white + shadow). Overlay full screen `bg.overlay`: 72 dp top bar (back, borderless 18 sp query field, clear), 1 dp divider, thin progress bar while searching, live results in 2 columns, rows 95 dp (50 dp thumbnail radius 4 dp, title 18 sp, context 14 sp medium, snippet 14 sp starting with "..."), `search.highlight` on matches, section header 16 sp medium.
- Multi-select bar: floating stadium 557x69 dp, 28 dp above the bottom, centered on content, `surface.multiBar`, 1 dp `border`; equal buttons with 24 dp icon over 14 sp medium label (Cancel, Select all, Delete, Move, Export). No count label.
- Info banner (bin): full content width, 47-52 dp, radius 6-8 dp, fill #1F1F21, body text left at 16 dp, text button right ("Empty bin").
- Grouped list card: `surface.group`, radius 12 dp, 16 dp inset, 60 dp rows (40 dp thumbnail radius 4 dp, 16 sp title, trailing "..."), 1 dp dividers inset from the title. Reorder list: 44 dp tiles `surface.inset` radius 10-12 dp, 8 dp gaps, icon 24 dp, label, eye toggle, 6-dot grip.
- Settings: app bar 64 dp (back left, centered title 20 sp); section cards with headline title, 48 dp rows (55 dp with subtitle), right-side dropdown value + chevron-down 20 dp, navigation rows chevron-right, switch rows with 13 sp subtitle.
- Empty state: 250-300 dp illustration centered (white line art, dark gray fills, accent spot colors), caption 15-18 sp `text.secondary` 15-16 dp below; no button.
- Snackbar/toast: none observed (delete moves to bin silently). Folio keeps its own undo snackbar if planned; style it as a `surface` stadium.
- Checkbox: 18 dp, radius 2 dp; unchecked 2 dp outline `text.primary`; checked `accent.fill` with `onAccent` check.

## 8. Screen layouts
- Library: sidebar 280 dp | content (gradient band + dot grid at top). Header: greeting ~108 dp from top, subtitle, chip row at y 205 dp with sort/view/select at its right end; grid below; search capsule top right (top 51 dp, right inset 20 dp); FAB bottom right. Folder view: chip row becomes breadcrumb, same frame. Bin: banner under the chips. Light theme identical in layout.
- Editor: no app bar. Pages centered on `bg.canvas`. Top-left nav pill (8 dp from left, top 33 dp) + edit pill below; top-right mode/more pill + slim page stepper (18x60 dp) under it at the right edge; bottom-left page/zoom pill; bottom-center toolbox (row 2 over row 1, 8 dp apart, 25 dp above the bottom). With the IME open, the toolbox slides up to sit ~12 dp above the keyboard. Notewise offers only Top/Bottom toolbox positions (Folio: R-UI-02 docks, see scope reminder).
- Panels: popovers anchor to row 2; tool panels (340 dp) anchor to the details button; overflow panel 300x382 dp drops from the top-right pill (16 dp from the edge, header with 60 dp thumbnail + title + meta lines, 75 dp action tiles in `accent.tile`, 46 dp list rows); add menu 250 dp hangs from the nav pill; large dialogs (overview, template, export) centered at 800 or 560 dp.
- Template dialog: chip pair + size dropdown, 44 dp paper swatches, sliders (density, line width), collapsible category sections (header 18 sp medium, 6 dp accent dot = contains current), 99x140 dp template cards (radius 4 dp, selected 2 dp accent border + accent title), floating split "Apply" button bottom right.
- Settings: full page, `bg.page`, centered 663 dp column of cards.

## 9. Motion observed
- Sidebar collapse/expand animates width in place; the grid reflows 4 -> 5 columns.
- Row 2 morphs in place on tool change, width hugs content and stays centered; collapse chevron hides row 2 while row 1 stays bottom-anchored.
- Toolbox slides above the IME; the page scrolls to keep the text box visible.
- Menus and popovers open without scrim; one outside tap only dismisses. Bin delete reflows the grid with no visible animation. No other timings were measurable.

## 10. Shot index (shots/)
- G1-home.jpg: dark library, sidebar expanded, folder cards, chips, FAB
- G1-home-collapsed.jpg: sidebar as 80 dp rail, 5-column grid
- G1-home-list.jpg: list view, 2 columns of rows
- G1-sort-menu.jpg: sort dropdown with grouped options and checks
- G1-tab-favorites.jpg: Favorites chip selected, empty-state illustration
- G1-tab-bin.jpg: Bin tab, info bar, note cards with badges
- G2-folder.jpg: folder view with breadcrumb and note cards
- G2-card-menu.jpg: note card overflow menu (danger last item)
- G2-rename.jpg: Material rename dialog above the IME
- G2-move.jpg: move dialog with folder list and colored folder glyphs
- G2-deleted-snackbar.jpg: grid after delete (no snackbar)
- G2-bin-menu.jpg: bin card menu (restore, delete permanently)
- G2-multiselect.jpg: selection circles and floating action bar
- G2-new-folder.jpg: create-folder dialog, disabled confirm
- G2-search.jpg: full-screen search with highlighted results
- G2-new-tag.jpg: Notewise-style new-tag dialog with color dots
- G3-editor-pen.jpg: dark editor, all pills, pen row 2, ink on grid paper
- G3-editor-highlighter.jpg: highlighter row 2
- G3-editor-eraser.jpg: eraser row 2 with size rings
- G3-editor-shapes.jpg: shapes row 2 (widest row)
- G3-editor-lasso.jpg: lasso row 2, tinted mode icon
- G3-editor-text.jpg: text row 2 with font name and formatting
- G3-editor-sticky.jpg: sticky note row 2 (Notewise-only)
- G3-editor-collapsed.jpg: row 2 hidden via chevron
- G3-editor-overflow.jpg: overflow panel with action tiles and rows
- G4-color-popover.jpg: color popover over row 2
- G4-color-picker.jpg: custom color dialog, grid mode
- G4-color-spectrum.jpg: custom color dialog, spectrum mode
- G4-palette-edit.jpg: palette editor dialog with reorder rows
- G4-width.jpg: width popover with preset cards and slider
- G4-pen-settings.jpg: pen settings panel (kind cards, switches)
- G4-eraser-settings.jpg: eraser panel, object-type chips, danger button
- G4-shape-settings.jpg: shape panel with shape grid and pager dots
- G4-lasso-selection.jpg: lasso selection box and action bar
- G4-lasso-more.jpg: selection more menu with blue icon buttons
- G4-sticky-settings.jpg: sticky panel, outlined segmented buttons (Notewise-only feature)
- G4-text-editing.jpg: text box, context bar, toolbox above the IME
- G5-overview.jpg: page overview dialog with thumbnails
- G5-page-menu.jpg: page menu under a thumbnail
- G5-overview-filter.jpg: overview filter popup (all/bookmarks/outline)
- G5-outline-empty.jpg: outline empty state
- G5-quick-switch.jpg: quick switch side panel (Notewise-only)
- G5-add-menu.jpg: add menu with import rows and position choice
- G5-toolbar-customize.jpg: customize toolbar dialog, reorder list
- G5-toolbar-position.jpg: Top/Bottom position dropdown
- G5-template.jpg: template dialog, swatches, sliders, template cards
- G5-page-size.jpg: inline page size editor
- G5-template-apply.jpg: apply-scope popup with checkbox
- G5-export.jpg: export dialog, page picker, format tiles
- G6-settings.jpg: dark settings home
- G6-theme-menu.jpg: dark theme dropdown
- G6-settings-light.jpg: light settings home
- G6-settings-light-2.jpg: light settings, lower sections
- G6-settings-switches.jpg: light switch rows
- G6-toolbox-light.jpg: light toolbox sub-page, reorder list
- G6-scroll-layout-menu.jpg: light dropdown menu with shadow
- G6-theme-menu-light.jpg: light theme dropdown
- G6-settings-about.jpg: about section, version line
- G6-home-light.jpg: light library with blue top band
- G6-editor-light.jpg: light editor chrome with shadows
