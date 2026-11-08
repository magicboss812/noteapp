package dev.folio.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The width of a text box (10-editor-ui.md#text-boxes, Width; P07-T15): two border sets, custom (auto width or fixed
 * borders) and fill, of which the frames hold the active one and [FlowBox] keeps the other ([FlowBox.custom] while
 * fill is on, [FlowBox.fillBorders] while it is off, null = the page text zone edges). The fill width button switches
 * the sets ([toggled]); a width knob moves one border of the active set ([knob]) and ends auto width ([knobbed]).
 */
object BoxWidth {
    private const val EPS_PT = 1e-3f

    /** A box after a width change: its [box] state and the [borders] its frames take. */
    data class Change(
        val box: FlowBox,
        val borders: Borders,
    )

    /**
     * The fill width button on a box whose frames hold [active], on a page whose text zone runs between [zone]:
     * custom -> fill (the zone edges the first time, later the last fill borders), fill -> the last custom borders
     * (an auto width box returns to auto width, its reflow then fits the borders to the text again).
     */
    fun toggled(
        box: FlowBox,
        active: Borders,
        zone: Borders,
    ): Change =
        if (box.fill) {
            val custom = box.custom ?: active
            val fill = active.takeUnless { it.near(zone) }
            Change(box.copy(fill = false, custom = null, fillBorders = fill), inside(custom, zone))
        } else {
            val fill = box.fillBorders ?: zone
            Change(box.copy(fill = true, custom = active, fillBorders = null), inside(fill, zone))
        }

    /** [box] once a knob moved the active borders: only the active set changes, and the custom set stops being auto width. */
    fun knobbed(box: FlowBox): FlowBox = if (box.fill || !box.autoWidth) box else box.copy(autoWidth = false)

    /**
     * Borders [current] with the [left] (else right) border dragged to [xPt]: unsnapped, at least [minWidthPt] wide,
     * inside [zone] (the whole zone when it is narrower than that); the other border stays.
     */
    fun knob(
        zone: Borders,
        current: Borders,
        left: Boolean,
        xPt: Float,
        minWidthPt: Float = TextZones.BOX_MIN_WIDTH_PT,
    ): Borders {
        if (zone.widthPt <= minWidthPt) return zone
        val kept = inside(current, zone, minWidthPt)
        return if (left) {
            Borders(xPt.coerceIn(zone.leftPt, max(zone.leftPt, kept.rightPt - minWidthPt)), kept.rightPt)
        } else {
            Borders(kept.leftPt, xPt.coerceIn(min(zone.rightPt, kept.leftPt + minWidthPt), zone.rightPt))
        }
    }

    // [b] moved and narrowed into [zone], at least [minWidthPt] wide where the zone allows.
    private fun inside(
        b: Borders,
        zone: Borders,
        minWidthPt: Float = 0f,
    ): Borders {
        if (b.leftPt >= zone.leftPt && b.rightPt <= zone.rightPt && b.widthPt >= minWidthPt) return b
        val width = min(zone.widthPt, max(b.widthPt, minWidthPt))
        val left = b.leftPt.coerceIn(zone.leftPt, max(zone.leftPt, zone.rightPt - width))
        return Borders(left, left + width)
    }

    private fun Borders.near(other: Borders): Boolean = abs(leftPt - other.leftPt) <= EPS_PT && abs(rightPt - other.rightPt) <= EPS_PT
}
