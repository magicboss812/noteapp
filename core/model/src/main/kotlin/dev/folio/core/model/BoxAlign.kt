package dev.folio.core.model

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * The alignment of a text box (10-editor-ui.md#text-boxes, Alignment; P07-T16) and what it does to the borders. Auto
 * width boxes keep their borders: the reflow grows them on the side the alignment sets around the anchor. Fixed
 * borders: Left, Right and Justify never move them; Center (outside fill width) saves them in [FlowBox.centerSaved] and
 * centers them on the anchor; leaving Center restores the saved ones. In fill width Center only centers the text.
 */
object BoxAlign {
    /** A box after an alignment change: its [box] state and the [borders] its frames take. */
    data class Change(
        val box: FlowBox,
        val borders: Borders,
    )

    /**
     * Box [box] aligned [from] whose frames hold [active], on a page whose text zone runs between [zone], set to [to].
     * A box without an anchor centers on its middle.
     */
    fun aligned(
        box: FlowBox,
        from: TextAlign,
        to: TextAlign,
        active: Borders,
        zone: Borders,
        minWidthPt: Float = TextZones.BOX_MIN_WIDTH_PT,
    ): Change {
        val centering = to == TextAlign.CENTER && from != TextAlign.CENTER
        val leaving = from == TextAlign.CENTER && to != TextAlign.CENTER
        val saved = box.centerSaved
        return when {
            box.fitsText || (!centering && !leaving) -> {
                Change(box, active)
            }

            box.fill -> {
                // The fill borders stay; Center saved the custom set's borders (outside fill), which return to it.
                if (leaving && saved != null) Change(box.copy(custom = saved, centerSaved = null), active) else Change(box, active)
            }

            centering -> {
                val anchor = box.anchorXPt ?: ((active.leftPt + active.rightPt) / 2f)
                Change(box.copy(centerSaved = active), centered(anchor, active, zone, minWidthPt))
            }

            else -> {
                Change(box.copy(centerSaved = null), inside(saved ?: active, zone))
            }
        }
    }

    /**
     * Borders [current] with a knob in Center dragged to [xPt] (P07-T15 knobs, symmetric in Center): both borders move
     * alike around the borders' middle (the anchor once Center centered them), at least [minWidthPt] wide, inside [zone].
     */
    fun symmetricKnob(
        zone: Borders,
        current: Borders,
        xPt: Float,
        minWidthPt: Float = TextZones.BOX_MIN_WIDTH_PT,
    ): Borders {
        val middle = (current.leftPt + current.rightPt) / 2f
        val room = min(middle - zone.leftPt, zone.rightPt - middle)
        if (room * 2f < minWidthPt) return inside(Borders(middle - minWidthPt / 2f, middle + minWidthPt / 2f), zone)
        val half = abs(xPt - middle).coerceIn(minWidthPt / 2f, room)
        return Borders(middle - half, middle + half)
    }

    // Borders symmetric around [anchor] with half width the larger distance from it to [active]'s borders, limited to
    // [zone]; at least [minWidthPt] wide where the zone allows (then shifted inside it, no longer centered on the anchor).
    private fun centered(
        anchor: Float,
        active: Borders,
        zone: Borders,
        minWidthPt: Float,
    ): Borders {
        val wanted = max(abs(anchor - active.leftPt), abs(active.rightPt - anchor))
        val half = min(wanted, min(anchor - zone.leftPt, zone.rightPt - anchor))
        if (half * 2f >= minWidthPt) return Borders(anchor - half, anchor + half)
        return inside(Borders(anchor - minWidthPt / 2f, anchor + minWidthPt / 2f), zone)
    }

    // [b] moved and narrowed into [zone].
    private fun inside(
        b: Borders,
        zone: Borders,
    ): Borders {
        if (b.leftPt >= zone.leftPt && b.rightPt <= zone.rightPt) return b
        val width = min(zone.widthPt, b.widthPt)
        val left = b.leftPt.coerceIn(zone.leftPt, max(zone.leftPt, zone.rightPt - width))
        return Borders(left, left + width)
    }
}
