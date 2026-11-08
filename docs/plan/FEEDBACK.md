# FEEDBACK
User concerns and change requests in plain words, written any time (tablet tests, USER-CHECKs, ideas).
Claude turns `Open` entries into task blocks at every phase REVIEW (next-task skill section 8, step 3), then moves them to `Converted` with the task ids. An entry ending in `[now]` is converted at the start of the next session, as a task in the current phase placed before `next:`.
Format: `- YYYY-MM-DD <what bothers you or what you want> [now]` (the date and `[now]` are optional).

## Open

## Converted
- 2026-11-08 (P06-T14/T17 USER-CHECKs, user, shots in docs/design/reference/feedback-2026-11-08-*.png) text boxes have no outline, so the selected one is unknown and cannot be moved; the edited text runs over the page gap and only paginates after leaving it (must always paginate, no flicker or lag); no caret in an empty block; body text takes taps anywhere on its page and new pages start at the first line instead of the tapped line; no touch selection of words or letters, only Shift+arrows; the highlight sits beside the selected letters. Wanted (Notewise-style box toolbar): Preview mode by default (no keyboard, no word selection, move by drag: x smooth, y by lines keeping the distance to the rule; font size and color buttons) and an Edit button; arrow keys move boxes (quadratic press-time speed with a cap, Up/Down by lines, Shift smooth, then unshifted Up/Down snaps and drops the offset); a tap inside a line creates a box whose first letter starts at the tap; left and right width knobs with zoom-independent big hit areas; fill width button that remembers both custom and fill borders; alignment left/center/right/justify, Center re-centers the borders on the creation spot, back to Left/Right restores them, Left/Right never change width; delete button -> A-057, P07-T10 to T17 (spec 10-editor-ui.md#text-boxes).
- 2026-11-08 (user, on the A-057 defaults) only the Edit button enters Edit, no tap-to-edit for now; leave Edit with the Back gesture (no Escape key); never select words outside Edit (press-and-hold must move the box); visible dashed outlines like Notewise; a new box starts tight at the tap and grows with the text up to the page text edge (by alignment), never past the page, unless fill width -> A-057 addendum, P07-T11/T12/T13/T15/T16, B-13.
- 2026-11-07 (user) a long pen line hid for a split second at pen-up on an almost empty page at 100 %; `ink-probe` saw no app frame lose a stroke -> P11-T09 (watch + front-buffer check).
- 2026-11-07 (P06-T07/T15/T09 USER-CHECKs, device-tester) math error popup anchored far right; soft keyboard left up after opening another note (Esc does not close it); task checkbox glyph touches its text -> P11-T12.
- 2026-11-07 (P06-T08/T09 USER-CHECKs, device-tester) table header not bold; remove column hits the left column from a cell start or empty cell; trailing space in a cell dropped; stylus tap on checkbox/link inert in read mode -> P06-T19.
- 2026-11-07 (P06-T07/T15 USER-CHECKs, device-tester) with the soft keyboard up the caret line is not scrolled into view; toolbars float over the typed lines -> P06-T18 (2).
- 2026-10-23 (P06-T13 USER-CHECK) each stroke disappears shortly after lifting the pen and reappears; any reload must be seamless -> P06-T15 (fixed, A-046).
- 2026-10-23 (P06-T06 USER-CHECK) zooming while typing keeps the text block at its screen size until editing ends -> P06-T15 (fixed, A-046).
- 2026-10-23 (P06-T06 USER-CHECK) choose how far text sits above the lines -> P06-T16.
- 2026-10-23 (P06-T06 USER-CHECK) individual text blocks that can be moved anywhere with the same strict spacing -> P06-T17 (+ lasso snapping in P07-T03).
- 2026-10-23 (P04-T10 USER-CHECK) editor UI does not look like Notewise: no top bar, toolbar floats over the note so read mode/fullscreen transitions are seamless; more pen settings in the menu opened by tapping the tool again; the width/color row can be hidden -> A-037, P06-T12, P06-T13.
- 2026-10-23 (P05-T07, P04-T06, P04-T07 USER-CHECKs) library <-> editor transitions are a little rough; page panel, overview and new-note sheet need smoother animations -> P11-T03.
- 2026-10-23 (P05-T05 USER-CHECK) selecting a tag in search without typing lists nothing; tagged notes only show once a word is typed -> P11-T11.
- 2026-10-23 (P05-T02 USER-CHECK) "Select all" cannot be undone, which makes picking single cards afterwards hard; the card "..." sits too close to the card border -> P11-T11.
- 2026-10-23 (device-tester, P04 device checks) the autosave dot (bottom-left) is drawn on top of the open pages panel and the overview; it should sit below them -> P11-T10.
- 2026-10-01 UI does not look like Notewise; capture the real app and redo the design before P04-T05 -> P04-T10.
- 2026-09-30 dry strokes look slightly blocky when zoomed in; more detail or a setting -> D-018 -> P11-T08.
- 2026-10-02 (P04-T03 USER-CHECK) the eraser ring disappears while pressing; it must stay visible while erasing -> P07-T09.
