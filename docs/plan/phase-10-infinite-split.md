# P10 Infinite canvas and split view
Goal: fixed pages that expand into infinite canvases, and two documents side by side (notes + notes, notes + PDF).
Exit: both flows work on the tablet with budgets; tag `p10-done`.

### P10-T01 Convert page to infinite
Implements: R-CORE-02
Read: 05-canvas-rendering.md#canvas-mode, 03-document-model.md#pages
Do: page menu "Expand to infinite canvas" (keeps origin, template repeats); "Back to fixed page" allowed only if content fits the original bounds; undoable; stack mode shows infinite pages as cards with a content preview; tapping enters canvas mode.
Accept:
- [ ] unit: conversion commands and fit check
- [ ] device: convert, draw far outside the original bounds, reopen
Verify: tests + device-tester

### P10-T02 Canvas mode viewport
Implements: R-CORE-02, R-INK-07
Read: 05-canvas-rendering.md#canvas-mode
Do: free 2D pan/zoom without page clamping, content-bounds tracking, template repetition in all directions, body flow frame with unbounded height, minimap chip showing position (tap to recenter).
Accept:
- [ ] device: pan 20 screens away and back: jank <= 1%
Verify: device-tester

### P10-T03 Split view panes
Implements: R-UI-03
Read: 10-editor-ui.md#split-view
Do: two EditorPanes (landscape side by side, portrait stacked), draggable divider (30-70%), active pane outline, shared toolbar acting on the active pane, independent undo/zoom/tool state, close either pane.
Accept:
- [ ] screenshot: split landscape and portrait
- [ ] device: draw in both panes alternately; undo affects only the active pane
Verify: screenshots + device-tester

### P10-T04 Open beside
Implements: R-UI-03
Read: 10-editor-ui.md#split-view
Do: library card menu "Open beside", editor menu "Open beside..." (picker), PDF + note workflow (drag of objects across panes is out of scope).
Accept:
- [ ] user: "Open a PDF beside a note and take notes while reading. Anything awkward?"
Verify: device-tester smoke
