# 11 Design system
Direction (A-030): the measured Notewise look in `docs/design/notewise/DESIGN.md` (DESIGN below), adopted directly: colors, type sizes, spacing, radii, elevation, icon size and stroke, component specs. Folio keeps its name, copy, Lucide icons, its tool set (10-editor-ui.md, including sticky notes and toolbar docking) and leaves out AI, Shared, account and cloud UI. Where DESIGN gives no value (`(~)` or a missing light value) the table marks the Folio pick with `*`. `TokensTest` checks every token below against DESIGN.

## Principles
- Dark-first chrome around light paper: near-black backdrops, one cool gray surface for every floating layer, a single soft blue accent.
- Everything floats: the editor has no app bar; tools live in 44 dp stadium pills with a 1 dp border.
- Selection is blue and quiet: dark-blue fill circle, 4 dp blue dot, 2 dp blue ring, or blue label + trailing check. No row tints.
- Character: serif display and card titles against sans everywhere else, the blue-slate gradient band at the top of the library, saturated folder cards.
- Destructive actions use soft salmon text, never red fills.
- Motion explains state changes; nothing bounces for fun.

## Colors
| Token (FolioColors) | DESIGN token | Dark | Light |
|---|---|---|---|
| libraryTop / libraryMid / libraryBottom | bg.library | #213044 / #141519 / #0A0A0B | #88AFE6 / #CDE3EE / #FEFEFF |
| libraryDots (1 dp dots, 22 dp pitch) | bg.library | white 6%* | white |
| page | bg.page | #070708 | #FAFAFB |
| sidebar | bg.sidebar | #1A1A1B | #F3F3F3 |
| canvas (editor backdrop) | bg.canvas | #252526 | #EEEEEF |
| overlay | bg.overlay | #1C1E21 | #FAFAFB* |
| surface (menus, popovers, panels, dialogs) | surface | #222428 | #FAF9FA |
| surfaceDialog | surface.dialog | #232529 | #FAF9FA* |
| surfaceHeader | surface.header | #1E2023 | #FEFDFE |
| surfaceToolbar | surface.toolbar | #292C31 | #F9F9FA |
| surfaceBar (on-canvas bars) | surface.bar | #1C1C1C | #F9F9FA* |
| surfaceInset | surface.inset | #1D1F22 | #FCFCFD |
| surfaceGroup | surface.group | #2B2E34 | #F1F2F4* |
| surfaceTinted | surface.tinted | #333643 | #EDF0F7* |
| settingsCard | surface.settingsCard | #34394A | #EDF0F7 |
| multiBar | surface.multiBar | #2F363F | #F1F2F4* |
| border | border | #4D4F57 | #EBEBEC |
| divider | divider | #494B52 | #E6E6E6 |
| textPrimary | text.primary | #E1E1E8 | #1B1B1F |
| textSecondary | text.secondary | #C6C8D1 | #4C4D55 |
| textTertiary | text.tertiary | #8D8D93 | #8A8B92* |
| textDisabled | text.disabled | #67686D | #B4B5BA* |
| icon (top pills, library) | icon | #D7D7DD | #1F1F23 |
| iconToolbar (toolbar rows) | icon | #C1C3CC | #343438 |
| accent | accent | #6B99F0 | #4B85E0 |
| accentFill (FAB, filled buttons) | accent.fill | #6997EE | #4B85E0 |
| onAccent | onAccent | #102B6E | #FFFFFF |
| accentContainer (selected chip) | accent.container | #28395A | #E6EEF5 |
| accentContainerStrong (selected tool, segment) | accent.containerStrong | #23496E | #C6DBF7 |
| onAccentContainerStrong | accent.containerStrong | #BDD1EA | #12263B |
| accentTile | accent.tile | #2B3C5D | #E6EEF5* |
| accentTrack (slider inactive track) | accent.track | #22486D | #C6DBF7* |
| navSelected | nav.selected | #2C2C2E | #DEDEE2 |
| closeChip / closeChipGlyph | closeChip | #2C2C2E / #8E8F99 | #E8E8EA* / #6E6F78* |
| danger | danger | #F3B8B1 | #D4504A |
| canvasSelection | canvas.selection | #4997F3 | #4997F3 |
| searchHighlight | search.highlight | #F3F350 | #F3F350 |
| scrim (modal dialogs only) | scrim | black 40% | black 40% |
| pageBorder (Folio) | - | #E3E6EB | #E3E6EB |
| success / warning (Folio) | - | #4ADE80 / #FBBF24 | #16A34A / #D97706 |
| folder colors | folder colors | 12 values below | same* |
Folder colors (FolderColor): #74C662 #4088A6 #7C1F45 #AB7C86 #DB6945 #EABE4D #430D73 #4CA2C8 #EB6CA6 #BA5522 #96244F #479A5D. Titles on them: #2E2D2B on light colors, white on dark (luminance > 0.4). DESIGN shows them brighter in light theme but measured only two, so both themes use one list*.
Palettes (defaults of the options rows, 10-editor-ui.md#tool-options): pen #010101 #ED884C #904A65 #8AB73D #6BA0D7 #CB4871 #2B4C9E; highlighter #CA4770 #EAD162 #8BB83D #476C77 #881DED. Tag colors #6F9BF0 #96D785 #EABE4D #ED983D #E36F63 #D37AF6. Paper colors #FAFAFB #F1EFE4 #CFC1A4 #DDE3ED #363637 #0B0B0C.
Dark mode never changes paper color, templates, ink, images, or PDF rendering (R-PAGE-03).

## Typography
- Sans: **Inter** (OFL, variable), standing in for DESIGN's "Roboto-like" sans (HyperOS's system font is MiSans, so Roboto would need a third bundled family).
- Serif: **Literata** (OFL, variable, opsz pinned to the style size), DESIGN's best guess; the file is core:text's `literata_var.ttf` under the same resource name, so the APK keeps one copy.
| Style | Font | Size / line (sp) | Weight | Use |
|---|---|---|---|---|
| display | Literata | 32 / 42 | 700 | library greeting |
| headline | Inter | 22 / 30 | 700 | settings section titles |
| title | Inter | 20 / 26 | 400 | dialog titles (centered), app bar title |
| titleSmall | Inter | 18 / 24 | 500 | panel and popover titles, sheet titles |
| cardTitle | Literata | 15 / 17 | 700 | note and folder card titles, max 2 lines |
| body | Inter | 16 / 22 | 400 | menu items, field text, dialog rows |
| bodyMedium | Inter | 15 / 20 | 500 | buttons, chips, editor menus |
| label | Inter | 15 / 19 | 400 | setting rows, subtitles, empty-state caption |
| labelSmall | Inter | 14 / 18 | 500 | tile labels, meta lines, multi-bar labels |
| caption | Inter | 12 / 14 | 400 | timestamps, counters, menu subtitles |
| toolbarValue | Inter | 16 / 20 | 500 | zoom %, font size in row 2 |

## Spacing and shapes
- Spacing (dp): 2, 4, 8, 12, 16, 20, 24, 28, 32, 40, 48. Floating chrome sits 8 dp from screen edges and 8 dp apart; panel content 16-24 dp insets.
- Sizes (FolioSpacing): touchTarget 44, toolbarPill 44 (height), toolbarCell 36 (selected circle), iconToolbar 24, iconMenu 20, iconSmall 16, colorDot 22, indicatorDot 4 (4 dp below the cell), dividerWidth 2 x dividerLength 26, popoverWidth 340, dialogWidth 560, closeChip 24, chromeInset 8 (edge and gap of floating chrome).
- Touch targets stay >= 44 dp (.claude/rules/compose-ui.md): toolbar cells draw a 36 dp circle inside a 44 x 44 dp target, so cells sit on a 44 dp pitch where Notewise uses 36.
- Radii (FolioShapes): stadium (pills, chips, buttons, bars), card 16 (note/folder cards, Material dialogs), popover 18 (popovers, tool panels), panel 24 (Notewise-style dialogs, sheet top corners), menu 12 (menus, grouped cards, option cards), tile 10 (tiles, preset cards, list rows), settings 8, field 4 (text fields, small thumbnails), checkbox 2.
- Borders: 1 dp `border` on pills, note cards, preset cards, outlined containers (folder cards have none); 2 dp accent for focus and selection.

## Elevation
- No shadows on dark backgrounds except popovers, menus and panels, which can sit over paper: popover shadow y 4, blur 8, black 16%.
- Light theme: toolbar pills add y 2, blur 4, black 10%; popovers as above.
- Pages on the canvas: 1 dp `pageBorder` + shadow y 2, blur 8, 6%.
- Cards carry no shadow (1 dp border).

## Icons
- Lucide (ISC license), outlined, stroke 2 in the 24 px viewBox, round caps and joins (DESIGN: 24 dp / ~2 dp in toolbars, 20 dp / ~1.5-1.75 dp in menus, which the same vector gives at 20 dp). Pinned tag: 1.49.0 (P04-T01, recorded in docs/notes/env.md). SVGs live in `tools/icongen/src/main/resources/lucide/`; add one there and run `./gradlew :tools:icongen:run`.
- Generated by `tools:icongen` into `FolioIcons` ImageVectors (no runtime SVG parsing, no icon fonts).
- Sizes: 24 dp in toolbars, library and sidebar; 20 dp in menus and inline rows; 16 dp for chevrons and info icons. Color = the row's text color; disabled #6F7177 (`textDisabled`). Selected tool: `onAccentContainerStrong` on the `accentContainerStrong` circle.
- Needed set (extend as required; Lucide 1.x names: `house` for home, `trash` for trash-2): house, menu, plus, panel-left, layout-grid, file-plus, ellipsis, lasso, pen, pen-line, pen-tool, pencil, brush, brush-cleaning, highlighter, eraser, shapes, type, table, image, camera, sticky-note, ruler, paperclip, columns-2, undo-2, redo-2, sliders-horizontal, circle-plus, search, star, tag, trash, folder, folder-plus, settings, chevron-left/right/down, x, check, circle-check, arrow-down-wide-narrow, list, list-ordered, list-checks, bold, italic, strikethrough, highlighter, code, sigma, link, heading-1/2/3, quote, minus, square, circle, triangle, move-up-right, hexagon, share-2, download, infinity, maximize-2, grip-vertical, keyboard, file-text, file-up, rotate-ccw, crop.

## Motion
| Token | Value | Used for |
|---|---|---|
| fast | 120 ms, standard easing | press states, icon swaps, chip selection |
| standard | 200 ms, standard easing | options row changes, menus, tab indicator |
| emphasized | 320 ms, emphasized decelerate | sheets, card -> editor container transform, split view open |
| spring | dampingRatio 0.85, stiffness 500 | toolbar dock snapping, floating toolbar drag release, selection box |
Easing: standard = CubicBezier(0.2, 0, 0, 1); emphasized decelerate = CubicBezier(0.05, 0.7, 0.1, 1).
- Card -> editor: shared element (card bounds -> page area), cover image crossfades into the live first page.
- Tool switch: the selected circle fades in, icon scales 0.92 -> 1.
- Options row: morphs in place, width hugs content and stays centered (AnimatedContent, fade + size).
- Page add/delete: fade + 0.96 -> 1 scale; page panel reorder: animateItem.
- Predictive back from the editor to the library.
- Reduce motion setting: all durations x 0.5 and no container transform (crossfade instead).
- Menus and popovers open without scrim; one outside tap only dismisses.
- Never animate content during inking; animations must not delay input handling.

## Library screen
DESIGN section 8 (Library), built in P05. Landscape:
- Sidebar 280 dp `sidebar` (collapses to an 80 dp rail; grid reflows 4 -> 5 columns): nav pills 250 x 42 dp, selected `navSelected` + accent icon/label; Home, folders, Favorites, Tags header with "+", Bin; settings gear bottom left.
- Content: `library` gradient band with the dot grid at the top. Header: serif greeting ("Hello, <name>" or "Your notes") ~108 dp from the top, date subtitle in textSecondary, filter chip row (All, Favorites, Tags, Bin; selected `accentContainer` + accent label) with sort, view toggle and select toggle at its right end; search capsule 48 dp top right; FAB 48 dp `accentFill` bottom right.
- Grid: 195 dp square cards, 24 dp gutter, 16 dp inset. List view: 2 columns of 100 dp rows.
- Portrait: the sidebar becomes a modal drawer opened from a menu button left of the greeting.

## Cards
- NoteCard: 195 dp square, radius 16, 1 dp border. Header 66 dp `surfaceHeader`: timestamp caption (textTertiary) at 12 dp inset, serif cardTitle below (2 lines, ellipsis), overflow "..." top right; 1 dp divider; preview fills the rest edge to edge (cover thumbnail, or mini-Markdown textPreview in caption type on paper white). Selected: 2 dp accent border. Favorite star and tags show in the header meta line.
- FolderCard: 195 dp square, radius 16, no border, folder color fill. Header 66 dp: count caption (title color at 45%) then serif title; two stacked page previews (back dark, front white, radius 8) with the lower 40% under a translucent lighter pocket.
- List mode: 100 dp rows, 100 dp thumbnail (radius 10, 1 dp border), count over sans title, overflow at the end.

## Sticky notes
Folio's own (DESIGN lists sticky notes as Notewise-only, but 10-editor-ui.md keeps the tool). Colors: yellow #FDE68A, pink #FBCFE8, blue #BFDBFE, green #BBF7D0, lavender #DDD6FE. Radius 4 pt, shadow y 2, blur 6, 12% (drawn in page space so it exports), optional rotation up to ±4 deg. Text uses the flow's font at body size, ink #1F2937.

## Components
DESIGN section 7 is the spec; Folio components and their mapping:
- `PillGroup`: 44 dp stadium, `surfaceToolbar`, 1 dp border, light-theme toolbar shadow; children are 44 dp cells.
- `ToolButton`: 36 dp circle `accentContainerStrong` + `onAccentContainerStrong` icon when selected, `iconToolbar` otherwise.
- `WidthChip`: bar glyph of growing thickness; selected = 4 dp accent dot below. `ring` variant (eraser sizes): selected = 2 dp accent ring.
- `ColorDot`: 22 dp dot (1 dp border) in a 44 dp cell; selected = 4 dp accent dot below.
- `PillDivider`: 2 x 26 dp `divider` (turned for vertical rails).
- `SegmentedTabs`: filter chips, 36 dp stadium, selected `accentContainer` + accent label.
- `FolioIconButton` (Plain / Outlined capsule), `CloseChip` (24 dp circle in a 44 dp target), `FolioButton` (Primary: filled `accentFill` stadium 48 dp; Text: no fill, bodyMedium in `textPrimary`, accent or `danger`).
- `FolioPopover`: `surface`, radius 18, padding 16, 340 dp, popover shadow, centered titleSmall title + close chip; tool panels use the same card. Sliders inside: active track accent, inactive `accentTrack`.
- `FolioSheet` (top radius 24, `surface`, scrim), `FolioDialog` (Material style: 560 dp max, radius 16, centered title 20 sp, text-button actions bottom right; destructive confirm = `danger` text, no fill, which also resolves D-023), `FolioSnackbar` (`surface` stadium, 1 dp border, accent action), `NoteCard`, `FolderCard`. Each has light/dark entries in the Roborazzi catalog. Later tasks add switch, menu, empty state, save-state dot and page indicator from DESIGN section 7.
