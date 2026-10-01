# Notewise capture (spec for scripts/auto/notewise-capture.sh sessions)
Goal: a compact, measured description of Notewise's design system that P04-T10 can build from WITHOUT viewing screenshots. Every session runs one group below (G1..G6) or the synthesis (S), commits its output, and stops. The user starts the runner with the tablet connected and unlocked.

## Outputs
- `shots/<group>-<screen>.jpg`: written by `nw.sh shot` (1600 px wide copy). Committed, for humans and for rare look-ups.
- `notes/<group>.md`: one file per group, <= 90 lines, written at the end of the group session. Format below.
- `DESIGN.md`: written by S from the notes only, <= 250 lines.

## Tool: `bash scripts/design/nw.sh <cmd>` (one call per Bash step; no loops, no redirects)
- `info` package, screen size, density (Pad 7: 440 dpi, so 1 dp = 2.75 px), `launch` brings Notewise to front.
- `ui` compact list of on-screen elements: `(cx,cy) WxH Class [tap] [on] "text" desc=".."`. Navigate with this, not with screenshots.
- `tap X Y`, `longpress X Y`, `swipe X1 Y1 X2 Y2 [MS]`, `key BACK|ESCAPE|ENTER|...`, `text "words"` (device px, current orientation).
- `shot NAME` saves the raw PNG (local), the repo JPEG, and a 1280 px preview; prints the preview path.
- `crop NAME X Y W H [SCALE]` native-pixel region of a saved shot (keep W,H <= 900); `px NAME X Y [X Y ..]` exact hex colors.

## Context economy (the reason this file exists)
- Find and reach screens with `ui`. View an image only to judge the look of a screen you will describe.
- Each shot: Read its preview ONCE, then measure with `crop` (small regions) and `px` (colors). Never re-read a preview; never Read `shots/*.jpg` or another group's files.
- Budget per group: at most 10 previews and 12 crops. Prefer one shot that shows several components.
- Write measurements into the notes as you go (Edit the notes file after every 2-3 shots), so a compaction loses nothing.

## Safety
- Do not sign in, buy, subscribe, rate, share, export to a cloud, or change Notewise sync/account settings. Dismiss such prompts with BACK.
- Do not edit or delete the user's existing notebooks or notes. For editor screens use one notebook named `Folio reference` (create it in G3 if missing; later groups reuse it). Leave it in place at the end.
- nw.sh acts only while Notewise is in front. If it refuses twice (locked tablet, other app), write what is missing at the top of the notes file and stop.

## Notes format (`notes/<group>.md`)
```
# <group> <title>
## <screen> (shots/<file>.jpg)
- Layout: regions with sizes in dp, margins, alignment
- Color: role -> #HEX (from px), e.g. background, surface, border, accent, text primary/secondary, selected tint
- Type: element -> family (serif/sans/mono, best guess of the face), size dp, weight, color
- Shape: radius dp, border width/color, shadow (offset, blur, opacity estimate)
- Icons: size dp, stroke width, style (outline/filled), selected state
- States/motion seen: selected, pressed, disabled, open/close behavior
```
Report numbers (dp, hex). Leave out anything you could not measure rather than guessing.

## G1 Library home
Home grid (landscape), sidebar expanded and collapsed, greeting/header, search button, segmented tabs (each tab state), sort and view toggles, note card (text preview), note card (handwritten preview), folder card, empty state if reachable, portrait home (rotate only if the device already is portrait; else skip).

## G2 Library organization
Inside a folder (breadcrumb), Favorites, Tags tab, Bin, search overlay with results, card overflow menu, multi-select mode bar, rename / move / delete dialogs (open and cancel; never confirm), new-folder dialog (cancel), snackbar or toast if one appears.

## G3 Editor frame
Create or open `Folio reference`. Editor with toolbar row 1 (document actions, tools), row 2 per tool: pen, highlighter, eraser, shapes, lasso, text, image, sticky note. Undo/redo, page indicator, overflow menu. Draw 3 short strokes with `swipe` to show ink on paper.

## G4 Editor tool options
Color palette and custom color picker, width presets, pen settings popover, eraser modes, shape options, lasso selection with its action bar (select the 3 strokes), sticky note styles, text formatting bar (tap the text tool on the page, type a short line).

## G5 Editor panels and sheets
Page panel / thumbnails, page overview, add page and page settings (size, template, color), template gallery or new-note sheet, export sheet (open and cancel), toolbar position options if Notewise has them, any side panel (outline, bookmarks).

## G6 Settings and themes
Settings home and 2 representative sub-pages, dark mode (only if Notewise has its own in-app theme switch: library and editor once each, then switch back), about/empty/error states seen elsewhere.

## S Synthesis (no device)
Read all `notes/G*.md`; view at most 3 previews in `shots/` and only to settle a contradiction. Write `DESIGN.md`:
1. Principles (5 lines: density, mood, what makes it look like Notewise)
2. Color tokens: table role -> light hex (-> dark hex if seen), with where it is used
3. Typography scale: style -> family, size dp, weight, line height, use
4. Spacing and layout grid; 5. Shape (radii), borders, elevation/shadows; 6. Iconography
7. Components: toolbar rows and pills, segmented tabs, chips, buttons, color dots, sliders, popovers, menus, sheets, dialogs, cards (note, folder), sidebar, search, snackbar; per component: size, padding, colors (token names), states
8. Screen layouts: library, editor (toolbar placements), panels; 9. Motion observed
10. Shot index: file -> one line
Numbers come from the notes; mark any value taken from a single uncertain measurement with `(~)`.

Don't forget to mention, that each component is part of Notewise. Because Notewise is slightly different in some ways, it doesn't have a paid button/subscriptions, sticky notes, a recorder, a zoom panel and a tape brush. There are still some more and DESIGN.md may include all components, but this should be a reminder before the rest of design.md for tasks that will regard the design system. This means, strict "Don't necessesarily add new mechanics and subjects" for the Design file, additional things like the amount of shapes or brush types is acceptible, but sticky notes, recorder and other new stuff at Notewise should NOT be reinvented for this app too.
