package dev.folio.core.model

/** A left and a right box border in page pt ([leftPt] <= [rightPt]). */
data class Borders(
    val leftPt: Float,
    val rightPt: Float,
) {
    /** Distance between the borders. */
    val widthPt: Float get() = rightPt - leftPt
}

/**
 * Box geometry of a text flow (10-editor-ui.md#text-boxes, A-057). The frames of the flow hold the active text column
 * of every page; this keeps what the frames cannot: [anchorXPt], the x of the Text tool tap that created the box
 * (null for older boxes and the body flow), [autoWidth] while the borders follow the typed text, [fill] while the
 * fill border set is active, the inactive sets ([custom], the last custom borders; [fillBorders], the last fill
 * borders, null = the page text zone edges), [centerSaved] (the borders before Center alignment re-centered them) and
 * [offsetPt], the whole box's vertical offset from its lines (Shift+Up/Down, A-057; 0 = on the lines).
 */
data class FlowBox(
    val anchorXPt: Float? = null,
    val autoWidth: Boolean = false,
    val fill: Boolean = false,
    val custom: Borders? = null,
    val fillBorders: Borders? = null,
    val centerSaved: Borders? = null,
    val offsetPt: Float = 0f,
) {
    /** Defaults. */
    companion object {
        /** A box with fixed borders taken from its frames (older free boxes, page duplicates). */
        val FIXED = FlowBox()

        /** The body flow of a note: fill width between the page text zone edges. */
        val FILL = FlowBox(fill = true)

        /** A box created by a Text tool tap at [anchorXPt]: auto width, anchored there. */
        fun created(anchorXPt: Float): FlowBox = FlowBox(anchorXPt = anchorXPt, autoWidth = true)
    }
}
