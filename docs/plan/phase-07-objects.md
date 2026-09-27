# P07 Objects and tools
Goal: shapes (drawn and recognized), lasso with full operations, images, sticky notes, ruler, attachments, note links; everything undoable and persisted.
Exit: every object operation passes undo/redo and reopen tests; tag `p07-done`.

### P07-T01 Shape tool and shape objects
Implements: R-INK-05
Read: 06-ink-input.md#shapes, 03-document-model.md#objects
Do: shape tool (line, arrow, rectangle, ellipse, triangle, polygon) by pen drag, style (stroke color/width, dashed, fill), rendering, selection handles (resize, rotate, move points for line/arrow/polygon).
Accept:
- [ ] unit: geometry of handles and transforms
- [ ] screenshot: all shapes and styles
Verify: tests + screenshots

### P07-T02 Shape recognition on hold
Implements: R-INK-05
Read: 06-ink-input.md#shape-recognition
Do: dwell detection (pen still within 4 dp for 450 ms at stroke end), recognizer (resample, corner detection, fits for line, arrow, triangle, rectangle, ellipse/circle, polygon), confidence threshold, replacement with a shape object while the pen stays down (continue dragging to adjust), highlighter straight-line snap.
Accept:
- [ ] unit: synthetic noisy inputs classified correctly (>= 95% on a 200-sample seeded set); scribbles stay ink
- [ ] user: "Draw shapes and hold the pen at the end. Do they snap when intended and never when not?"
Verify: tests

### P07-T03 Lasso selection and transforms
Implements: R-INK-04
Read: 06-ink-input.md#lasso
Do: freeform and rectangle lasso (dashed path), selection rules, selection box with 8 handles + rotate handle, move (pen or finger drag inside), scale, rotate, action bar: cut, copy, paste, duplicate, delete, recolor, bring forward/back, to front/back; in-app clipboard (also as image to the system clipboard via FileProvider).
Accept:
- [ ] unit: selection membership rules; transform commands undo
- [ ] device: select 50 strokes, move and rotate; one undo each
Verify: tests + device-tester

### P07-T04 Images
Implements: R-MED-02
Read: 03-document-model.md#objects, 04-file-format.md#assets
Do: insert from Photo Picker, camera (ACTION_IMAGE_CAPTURE with FileProvider, no CAMERA permission), crop and rotate UI, resize/move via selection, store original (JPEG/PNG kept; other formats converted to PNG) as sha256 asset, downsampled display bitmaps.
Accept:
- [ ] unit: asset dedupe by hash; crop rect math
- [ ] device: insert fixture image, crop, rotate, reopen
Verify: tests + device-tester

### P07-T05 Sticky notes
Implements: R-TXT-07
Read: 07-text-engine.md#flows-and-frames, 11-design-system.md#sticky-notes
Do: sticky object (5 colors, slight default rotation option, shadow), text flow inside snapped to U, resize, move.
Accept:
- [ ] screenshot: sticky variants
Verify: screenshots

### P07-T06 Ruler
Implements: R-INK-05
Read: 06-ink-input.md#ruler
Do: ruler overlay (finger drag moves, two-finger twist on the ruler rotates, angle label, 15 degree detents), pen strokes starting within 12 dp of the edge project onto the edge (StrokeInput feeding), ruler state per pane (not persisted).
Accept:
- [ ] unit: projection math
- [ ] user: "Draw lines along the ruler at several angles. Straight and accurate?"
Verify: tests

### P07-T07 Attachments and note links
Implements: R-MED-04, R-ORG-02
Read: 03-document-model.md#objects, 09-storage-library.md#links
Do: attachment object (file picked via SAF picker, copied into assets, chip with icon/name/size, open via FileProvider + ACTION_VIEW), link objects are text links (P06-T09); backlinks panel in the editor overflow menu.
Accept:
- [ ] unit: attachment asset handling; backlinks query
- [ ] device: attach fixture PDF, open it with an external app intent (chooser appears)
Verify: tests + device-tester

### P07-T08 Undo and persistence coverage
Implements: R-FILE-05
Do: parametrized tests for every object type and operation: execute, undo, redo, pack, reopen, compare.
Accept:
- [ ] unit: full matrix green
Verify: `./gradlew qa`
