# P04 Editor shell
Goal: the design system foundation and a complete editor frame around the canvas: toolbar (all dock positions), tool options, pages, undo, new-note flow, autosave, thumbnails.
Exit: create a note, draw with all pens, undo/redo, manage pages, leave and reopen with everything intact on the tablet; tag `p04-done`.

### P04-T01 Design system foundation
Implements: R-UI-01
Read: 11-design-system.md (all), docs/design/reference/README.md
Files: core/designsystem/**, tools/icongen/**
Do:
1. Tokens (colors light/dark, type, spacing, shapes, elevation, motion) and `FolioTheme`.
2. UI fonts Inter + Fraunces bundled (OFL, with licenses).
3. `tools:icongen` (JVM application): reads Lucide SVGs from a pinned release tag listed in 11-design-system.md#icons (downloaded to `tools/icongen/src/main/resources/lucide/`), converts `path/line/polyline/polygon/rect/circle/ellipse` to path data, generates `FolioIcons.kt` ImageVectors (stroke 1.75, round caps/joins).
4. Components: ToolButton, PillGroup, ColorDot, WidthChip, SegmentedTabs, FolioIconButton (44 dp target), FolioSheet, FolioDialog, FolioSnackbar (with Undo action), NoteCard shell, FolderCard shell.
Accept:
- [ ] screenshot: component catalog light and dark
- [ ] unit: icongen converts rect/circle/polyline correctly (golden path strings)
Verify: `./gradlew :tools:icongen:run :core:designsystem:verifyRoborazziDebug`

### P04-T02 Editor screen scaffold
Implements: R-CORE-01, R-UI-04
Read: 10-editor-ui.md#screen-structure, 02-modules.md#editor-state
Files: feature/editor/**
Do: EditorRoute, EditorViewModel holding EditorSession, CanvasHost in Compose, top toolbar row 1 (groups per 10-editor-ui.md#toolbar), navigation from library placeholder and debug `open`.
Accept:
- [ ] screenshot: editor landscape and portrait with an A4 lined page
- [ ] device: open seeded doc, draw, back to library, reopen
Verify: `./gradlew :feature:editor:verifyRoborazziDebug` + device-tester

### P04-T03 Tool options row
Implements: R-INK-01, R-UI-02
Read: 10-editor-ui.md#tool-options
Do: contextual row 2 per tool (brush kind, widths, colors with add/edit custom color picker, settings popover with pressure curve), per-tool last options persisted in DataStore.
Accept:
- [ ] screenshot: options row for pen, highlighter, eraser
- [ ] unit: tool state persistence round trip
Verify: module tests + screenshots

### P04-T04 Toolbar docking
Implements: R-UI-02
Read: 10-editor-ui.md#toolbar-docking
Do: dock modes TOP, LEFT, RIGHT, FLOATING (drag with edge snap, collapse to compact pill), handedness default, persisted per orientation, spring animations.
Accept:
- [ ] screenshot: each dock mode, landscape and portrait
- [ ] user: "Drag the toolbar to each side and float it. Does it feel smooth and stay where you left it after reopening?"
Verify: screenshots + device-tester smoke

### P04-T10 Design overhaul from the Notewise capture
Model: opus medium
Implements: R-UI-01
Read: docs/design/notewise/DESIGN.md (all), 11-design-system.md (all), docs/design/reference/README.md
Files: docs/architecture/11-design-system.md, core/designsystem/**, feature/editor/**/ui/**, feature/library/** (placeholder)
Do: runs before T05 so T05..T09 and P05 build on the new look (user request 2026-10-01).
1. Needs `docs/design/notewise/DESIGN.md` (user runs `scripts/auto/notewise-capture.sh`). Missing: keep the STATUS Blocked line with `[STOP]`, stop.
2. Rewrite 11-design-system.md from DESIGN.md: Notewise values (colors, type, spacing, radii, elevation, icon size/stroke, component specs) are adopted directly. Folio keeps its name, copy, Lucide icons (matched to Notewise size and stroke), its tool set (10-editor-ui.md) and leaves out AI, Shared, account and cloud UI. AMENDMENTS entry.
3. core:designsystem: tokens (light + dark), typography, shapes, components (toolbar pills, segmented tabs, chips, color dots, popovers, sheets, dialogs incl. D-023 contrast, cards, snackbar).
4. Restyle what T01..T04 built: editor row 1, row 2 / options row, side rails, floating pill, popovers, library placeholder.
5. Context: work from DESIGN.md numbers. View at most 4 images from `docs/design/notewise/shots/`, only to settle a value DESIGN.md leaves open. Sub-commits P04-T10a (doc + tokens), b (components + editor chrome), c (goldens) as needed.
Accept:
- [ ] unit: token table test (every DESIGN.md color/size token used by Folio equals its token value)
- [ ] screenshot: designsystem and editor goldens re-recorded; commit body pairs each golden with its Notewise shot
- [ ] user: open the library placeholder and a note on the tablet -> toolbar, options row, popovers, colors and type read as Notewise; differences go to docs/plan/FEEDBACK.md
Verify: `./gradlew :core:designsystem:testDebugUnitTest :feature:editor:testDebugUnitTest verifyRoborazziDebug`

### P04-T05 Undo/redo and shortcut foundation
Model: sonnet high
Implements: R-FILE-05, R-UI-05
Read: 10-editor-ui.md#keyboard-shortcuts
Do: undo/redo buttons bound to session; `ShortcutRegistry` with Ctrl+Z, Ctrl+Shift+Z, Ctrl+Y, Alt+1..9 tools, Ctrl+plus/minus/0 zoom, PageUp/PageDown, Esc; help sheet (Ctrl+/).
Accept:
- [ ] unit: registry dispatch table
- [ ] device: `input.sh combo CTRL_LEFT Z` undoes the last stroke
Verify: tests + device-tester

### P04-T06 Page management
Model: sonnet high
Implements: R-PAGE-01, R-PAGE-02
Read: 10-editor-ui.md#pages
Do: add page (after current, same spec), duplicate, delete (undoable), page panel drawer (thumbnails, drag reorder), page grid overview, page settings sheet (size A3/A4/A5, orientation, template + spacing, paper color; apply to this page / all pages).
Accept:
- [ ] unit: page commands undo
- [ ] screenshot: page panel, page settings sheet
- [ ] device: reorder via debug command then verify order after reopen
Verify: tests + device-tester

### P04-T07 New note flow
Model: sonnet high
Implements: R-CORE-02, R-PAGE-01
Read: 10-editor-ui.md#new-note
Do: template picker sheet: title, paper size, orientation, template gallery with live previews, spacing, paper color, fixed vs infinite; creates the document in the current folder and opens it.
Accept:
- [ ] screenshot: picker landscape and portrait
- [ ] device: create note via UI debug route; file appears in Folio-Debug
Verify: screenshots + device-tester

### P04-T08 Autosave indicator, recovery, thumbnails
Model: sonnet high
Implements: R-FILE-02
Read: 04-file-format.md#write-protocol, 09-storage-library.md#thumbnails
Do: subtle save-state dot (saved/saving/error with retry), recovery snackbar after crash recovery, page thumbnails (after 2 s idle, background), cover thumbnail = first page.
Accept:
- [ ] device: kill during editing (`stop.sh`), relaunch -> recovery snackbar and no data loss
Verify: device-tester

### P04-T09 Editor instrumented tests
Model: sonnet high
Implements: R-FILE-05
Read: .claude/rules/testing.md
Do: instrumented tests injecting stylus MotionEvents (TOOL_TYPE_STYLUS) via Instrumentation: create note, draw 5 strokes, undo 2, redo 1, leave, reopen, assert stroke count 4; eraser test.
Accept:
- [ ] device: `instrumented.sh :feature:editor` passes
Verify: device-tester
