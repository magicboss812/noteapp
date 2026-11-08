# P07 Objects and tools
Goal: shapes (drawn and recognized), lasso with full operations, images, sticky notes, ruler, attachments, note links; everything undoable and persisted.
Exit: every object operation passes undo/redo and reopen tests; tag `p07-done`.
Text boxes first (user feedback 2026-11-08, A-057): P07-T10 to T17 run before T01. Spec: 10-editor-ui.md#text-boxes; user shots in `docs/design/reference/feedback-2026-11-08-*.png`.

### P07-T10 Edited text in the page layout
Model: opus high
Implements: R-TXT-03, R-TXT-05, R-TXT-10
Read: 10-editor-ui.md#text-boxes (Editing look), 07-text-engine.md#live-field-as-built, #selection-as-built, #rendering
Do: the edited block runs over the gap between pages until editing ends (`feedback-2026-11-08-editing-over-page-gap.png`): `LiveTextField` is a free EditText at the block's first slice (`TextFocusLogic.fieldOf`, `FlowHits.placementOf`) with its own StaticLayout. Draw the focused block in the canvas overlay from a `BlockLayout` of its source (markers dimmed, raw math) at the frame width, distributed by `FlowDistributor` like all other blocks, so it breaks at the page bottom and continues on the next page while typing; line breaks equal the unfocused ones except marker and math widths. `LiveTextField` keeps focus and the IME connection but draws no text, caret or highlight. Caret drawn by the overlay, blinking, also in an empty block at the style's line height (no caret today: `BlockLayouter.empty()` has `fontSizePt = 0`, so the field's text size is 0). The highlight inside the focused block comes from that layout in source offsets (today the EditText's `highlightColor` drifts from the handles, which use `FlowLayout.caretSpot` plain offsets, by every `==` marker or formula before the selection: `feedback-2026-11-08-selection-offset.png`). Focus layout of the edited block may run on main (07 #rendering allows it); following blocks shift through the existing tile invalidation. Simplify `TextHandoff` where both sides now draw the same layout. Sub-commits: a (overlay text, caret, pagination), b (highlight and handles, EditText draws nothing, handoff). Amend 07-text-engine.md#live-field-as-built (the A-045 overhang and platform wrap go away).
Accept:
- [ ] unit: a focused paragraph longer than the page's remaining lines splits over two frames while focused, line breaks equal to the unfocused layout for text without markers; caret rect of an empty block has the style's line height; highlight boxes and handle spots agree on a line with `==x==` and `$a_2$` before the selection
- [ ] screenshot: focused paragraph across a page break; empty focused block with caret; selection inside a line with markers
- [ ] device: type at the bottom of a page: the text continues on the next page while typing, nothing in the gap (screenshot); Text tool tap on an empty page: caret visible at once; select across `==` markers: highlight under the handles; `text:key` p95 not worse than D-043 on the 20k-word note; no frame without the block on focus and blur (gfxinfo + screenshots)
- [ ] user: type a long paragraph over a page break, then leave it: nothing jumps, flickers or lags
Verify: `./gradlew :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T11 Touch text selection
Model: opus high
Implements: R-TXT-10
Read: 10-editor-ui.md#text-boxes (Editing look), 07-text-engine.md#selection-as-built
Do: a finger cannot select letters or words today: `CanvasHostView.dispatchTouchEvent` never passes touches to the EditText, a finger tap only becomes `texts.focusAt`, and the P06-T14 handles only exist once a keyboard selection does. Add gestures on the page layout for the block in edit: tap = caret, long-press = word under the finger with a haptic tick, a drag after the long-press extends by words, double tap = word, handles drag by letter (existing), across blocks and pages; a plain one-finger drag still pans. Word ranges by `BreakIterator` on the source, never splitting a marker or a formula. A floating menu above the selection (constant screen size): Cut, Copy, Paste, Select all through the existing clipboard paths. A long-press on text that is not being edited enters editing with that word selected.
Accept:
- [ ] unit: word range at an offset (punctuation, `**bold**`, `$x$`, digits); long-press drag extending over a block boundary; gesture classification (tap, long-press, double tap, pan, handle) from MotionEvent sequences
- [ ] screenshot: selected word with handles and floating menu
- [ ] device: long-press selects the word, dragging extends it, double tap selects a word, a handle moves letter by letter, Copy then Paste into another note works, one-finger drag over text pans
- [ ] user: select words and single letters with your finger
Verify: `./gradlew :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T12 Text boxes: tap where you type, real hit areas, page continuation
Model: opus high
Implements: R-TXT-07, R-TXT-09
Read: 10-editor-ui.md#text-boxes (Model and placement), 07-text-engine.md#flows-and-frames, #flow-engine, 03-document-model.md#objects, 04-file-format.md#protobuf-schema, #versioning
Do: `FlowLayout.hit` tests frame rects, so a body frame takes every tap on its page, and `CaretPlans` then fills blank lines (`FillPlan`) or appends, which starts new pages at the first line. Replace per spec: hit = borders by used lines (min one line) + 8 dp slop; a Text tool tap outside every box creates a box at the tap (line holding the tap, first glyph at the tap x, left border 0.5 U padding left of it, right border at the text zone edge, at least 20 mm, anchor stored), focused with the caret. Every box continues on the next page between the same borders when its lines pass the text zone bottom (continuation pages as the body flow has them), including boxes created mid-page; the body flow of existing notes becomes a box in fill width. An empty box is removed when it loses focus, without an undo step. Faint dashed outlines of all boxes on screen while the Text tool is active (overlay view). Typing with nothing focused per spec. One format step for all new persisted fields: per box anchor x, fill flag, custom and fill border sets, saved pre-center borders, vertical offset; `TextAlign.END`; flow text color (migration, codec goldens; old files open unchanged). Amend 07-text-engine.md#flows-and-frames and A-050/A-055 lines.
Accept:
- [ ] unit: a tap below the body text on the same page misses it and makes a new box; new box geometry (line, left = tap x - padding, min width near the right edge); a box passing the page bottom continues on the next page between the same borders, the continuation page is added and removed again; removing an empty box leaves the undo stack unchanged; codec round trip of every new field; an old file opens with defaults
- [ ] screenshot: faint outlines with the Text tool on; one box continued over a page break
- [ ] device: on an empty page tap mid-line in the middle of the page: the caret sits at the tap and typed text starts there; tap below existing text: a new box starts, the old one does not take the tap; typing past the page bottom continues on the next page
- [ ] user: place a few boxes on lines and spots of your choice and type into them
Verify: `./gradlew :core:model:test :core:format:test :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T13 Box Preview and Edit modes, outline, toolbar, move, delete
Model: opus high
Implements: R-TXT-09
Read: 10-editor-ui.md#text-boxes (Modes, Box toolbar, Move), A-055, `docs/design/reference/text-box-toolbar-reference.png`, 11-design-system.md
Do: the modes and transitions of the spec. Preview leaves the field unfocused (no keyboard), so a pen drag on the grip is not taken by HyperOS (D-041) and a finger grip drag at 25 % moves the box instead of panning (D-042). Outline (accent, 1.5 dp) and top grip in Preview and Edit; the toolbar in the Notewise look (dark pill, constant screen size, flips below or clamps); Edit and Delete work here, Color, Size, Alignment and Fill width are laid out but hidden until T15 to T17. Drag anywhere inside a Preview box or on the grip: x smooth (drop the x snapping of `TextZones.movedBox`), y in whole U steps keeping the lift, live, across pages, one undo step. Delete: one undo step. Replaces P11-T12's grip items and its rejected-drop preview leftover (clear the preview outline when `boxCommand` is null or refused). Amend A-055 in 07-text-engine.md#flows-and-frames.
Accept:
- [ ] unit: mode state machine over every transition of the spec; drag geometry (x unsnapped, y snaps to U with the lift kept, onto the next page); a rejected drop clears the preview; Delete is one undo step and undo restores text and position
- [ ] screenshot: box in Preview with toolbar, grip and knobs; box in Edit (Edit active, caret); toolbar flipped below a box at the page top
- [ ] device: tap a box: outline and toolbar, no keyboard; finger drag inside moves it (x smooth, y by lines); pen drag on the grip moves it without a handwriting toast; at 25 % a finger grip drag moves it; Edit: keyboard and caret; tap a selected box: caret at the tap; Delete then undo
- [ ] user: select, move and delete boxes with finger and pen
Verify: `./gradlew :core:model:test :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T14 Arrow keys move boxes; vertical offset
Model: opus high
Implements: R-TXT-09
Read: 10-editor-ui.md#text-boxes (Move), A-057, 07-text-engine.md#line-box
Do: hardware arrow keys in Preview per spec: smooth Left/Right with the press-time curve and speed cap; Up/Down in line steps paced by the same curve; Shift+Up/Down smooth, storing the box's vertical offset (A-057: the whole box shifts, line boxes stay k * U); the next unshifted Up/Down snaps to the nearest line in its direction first, removing the offset; a drag snaps too. Driven per Choreographer frame without allocation or recomposition, live, one undo step per key hold, viewport follows. Grid invariant tests learn the offset.
Accept:
- [ ] unit: speed curve (1 pt on press, quadratic ramp, cap), line-step pacing, Shift+Down then Up snaps up and clears the offset, Shift+Up then Down snaps down; grid invariant with an offset (baselines = rules + lift + offset, line boxes k * U); one undo entry per hold
- [ ] device: hold Right 2 s: smooth acceleration to a steady speed, no janky frames (gfxinfo); Up and Down move by lines; Shift+Down leaves the box between lines; Up then snaps it onto a line
- [ ] user: move boxes with the keys; the acceleration feels right
Verify: `./gradlew :core:model:test :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest` + device-tester

### P07-T15 Box width knobs and fill width
Model: opus high
Implements: R-TXT-09
Read: 10-editor-ui.md#text-boxes (Width)
Do: knobs per spec: drawn 10 dp, 48 dp target at every zoom for finger and pen, smooth, at least 20 mm, inside the page, live rewrap with layout on the `text` dispatcher, one undo step. Fill width button with the two border sets as a pure `BoxWidth` state machine in core:model; the knobs change only the active set. Symmetric knobs in Center come with T16.
Accept:
- [ ] unit: `BoxWidth` sequence custom -> fill (zone edges) -> adjust fill -> custom (last custom) -> fill (adjusted fill, not zone edges); a knob changes only the active set; min width and page clamp; knob hit target is 48 dp at zoom 0.25, 1 and 4
- [ ] screenshot: box with fill width on (button highlighted)
- [ ] device: drag each knob with finger and pen at 50 % and 200 %, text rewraps live without jank; fill width presses follow the spec sequence
- [ ] user: make a narrow box next to other text, toggle fill width back and forth
Verify: `./gradlew :core:model:test :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T16 Text alignment per box
Model: opus high
Implements: R-TXT-09
Read: 10-editor-ui.md#text-boxes (Alignment), 07-text-engine.md#block-layout, #block-styles-as-built
Do: alignment per box (Left, Center, Right, Justify; `TextAlign.END` from T12) with a toolbar button and a popup of four icons; which blocks take it per spec; visible while editing (T10 draws from the layout). Center saves the borders and centers them on the anchor, leaving Center restores them, in fill width Center only centers the text; in Center the knobs move symmetrically around the anchor. The grid invariant is unaffected.
Accept:
- [ ] unit: line x offsets for all four alignments (justified last line stays left); Center after a tap gives borders symmetric around the anchor, limited by the page; Center -> Left restores the saved borders; Left <-> Right and Justify keep the width; Center in fill width keeps the borders; the anchor follows moves; symmetric knob drag in Center
- [ ] screenshot: one box per alignment; a justified paragraph
- [ ] device: tap mid-line, type a word, choose Center: the word's middle sits on the tap spot (measured on the screenshot); Left: the borders return
- [ ] user: try all four alignments on a tapped box
Verify: `./gradlew :core:model:test :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T17 Box font size and color
Model: opus medium
Implements: R-TXT-09
Read: 10-editor-ui.md#text-boxes (Font size and color), A-051, 07-text-engine.md#font-normalization
Do: toolbar Size (the box's size in pt) with a popup of S, M, L and the free size; Color (circle) with the pen palette plus Auto; both apply to the whole box in Preview without the keyboard, persist (T12 fields), reach the page renderer and thumbnails, one undo step each. Sizes stay derived, so the grid invariant holds.
Accept:
- [ ] unit: a size change keeps the grid invariant for every bundled font; color round trip through the codec; undo restores size and color
- [ ] screenshot: boxes in three colors; the size popup
- [ ] device: change size and color in Preview: no keyboard, text rewraps on the lines; reopen the note: both kept
Verify: `./gradlew :core:text:testDebugUnitTest :feature:editor:testDebugUnitTest :feature:editor:verifyRoborazziDebug` + device-tester

### P07-T01 Shape tool and shape objects
Model: opus medium
Implements: R-INK-05
Read: 06-ink-input.md#shapes, 03-document-model.md#objects
Do: shape tool (line, arrow, rectangle, ellipse, triangle, polygon) by pen drag, style (stroke color/width, dashed, fill), rendering, selection handles (resize, rotate, move points for line/arrow/polygon).
Accept:
- [ ] unit: geometry of handles and transforms
- [ ] screenshot: all shapes and styles
Verify: tests + screenshots

### P07-T02 Shape recognition on hold
Model: opus high
Implements: R-INK-05
Read: 06-ink-input.md#shape-recognition
Do: dwell detection (pen still within 4 dp for 450 ms at stroke end), recognizer (resample, corner detection, fits for line, arrow, triangle, rectangle, ellipse/circle, polygon), confidence threshold, replacement with a shape object while the pen stays down (continue dragging to adjust), highlighter straight-line snap; the highlighter's stored "always straight" option (P04-T03, D-024) snaps every highlighter stroke to a straight line without a hold.
Accept:
- [ ] unit: synthetic noisy inputs classified correctly (>= 95% on a 200-sample seeded set); scribbles stay ink
- [ ] unit: with "always straight" on, a wavy highlighter stroke commits as a straight segment from its first to its last point (D-024)
- [ ] user: "Draw shapes and hold the pen at the end. Do they snap when intended and never when not?"
Verify: tests

### P07-T03 Lasso selection and transforms
Model: opus high
Implements: R-INK-04
Read: 06-ink-input.md#lasso
Do: freeform and rectangle lasso (dashed path), selection rules, selection box with 8 handles + rotate handle, move (pen or finger drag inside), scale, rotate, action bar: cut, copy, paste, duplicate, delete, recolor, bring forward/back, to front/back; in-app clipboard (also as image to the system clipboard via FileProvider). Free text boxes in a selection move with the grid snapping of P06-T17 (top on a rule, left on a column) so their lines stay on the grid.
Accept:
- [ ] unit: selection membership rules; transform commands undo
- [ ] device: select 50 strokes, move and rotate; one undo each
Verify: tests + device-tester

### P07-T04 Images
Model: opus medium
Implements: R-MED-02
Read: 03-document-model.md#objects, 04-file-format.md#assets
Do: insert from Photo Picker, camera (ACTION_IMAGE_CAPTURE with FileProvider, no CAMERA permission), crop and rotate UI, resize/move via selection, store original (JPEG/PNG kept; other formats converted to PNG) as sha256 asset, downsampled display bitmaps.
Accept:
- [ ] unit: asset dedupe by hash; crop rect math
- [ ] device: insert fixture image, crop, rotate, reopen
Verify: tests + device-tester

### P07-T05 Sticky notes
Model: opus medium
Implements: R-TXT-07
Read: 07-text-engine.md#flows-and-frames, 11-design-system.md#sticky-notes
Do: sticky object (5 colors, slight default rotation option, shadow), text flow inside snapped to U, resize, move; page duplicate copies sticky notes (D-027); the Sticky tool creates a sticky with its text focused (from P06-T10, A-050).
Accept:
- [ ] screenshot: sticky variants
- [ ] unit: duplicating a page copies its sticky notes
Verify: screenshots

### P07-T06 Ruler
Model: opus high
Implements: R-INK-05
Read: 06-ink-input.md#ruler
Do: ruler overlay (finger drag moves, two-finger twist on the ruler rotates, angle label, 15 degree detents), pen strokes starting within 12 dp of the edge project onto the edge (StrokeInput feeding), ruler state per pane (not persisted).
Accept:
- [ ] unit: projection math
- [ ] user: "Draw lines along the ruler at several angles. Straight and accurate?"
Verify: tests

### P07-T07 Attachments and note links
Model: opus medium
Implements: R-MED-04, R-ORG-02
Read: 03-document-model.md#objects, 09-storage-library.md#links
Do: attachment object (file picked via SAF picker, copied into assets, chip with icon/name/size, open via FileProvider + ACTION_VIEW), link objects are text links (P06-T09); backlinks panel in the editor overflow menu.
Accept:
- [ ] unit: attachment asset handling; backlinks query
- [ ] device: attach fixture PDF, open it with an external app intent (chooser appears)
Verify: tests + device-tester

### P07-T08 Undo and persistence coverage
Model: opus medium
Implements: R-FILE-05
Do: parametrized tests for every object type and operation: execute, undo, redo, pack, reopen, compare.
Accept:
- [ ] unit: full matrix green
Verify: `./gradlew qa`

### P07-T09 Eraser feedback and erase during handoff
Model: opus high
Implements: R-INK-03
Read: 06-ink-input.md#erasers, 06-ink-input.md#stylus-capabilities
Do: the eraser ring disappears when the pen touches; keep it visible and following the tip for the whole erase gesture (user, P04-T03 USER-CHECK), drawn in the overlay without per-event recomposition. An erase gesture right after a pen lift also hits strokes still in the dry handoff: the hit snapshot includes pending strokes (D-022).
Accept:
- [ ] unit: erase right after commit removes a stroke still pending in `DryHandoff`; undo restores it
- [ ] device: eraser ring visible in a screenshot taken mid-erase (`input.sh` stylus drag)
- [ ] user: "Erase with the pen. Does the ring stay visible the whole time?"
Verify: tests + device-tester
