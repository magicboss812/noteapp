package dev.folio.core.render.template

import android.graphics.Paint
import android.graphics.Typeface
import dev.folio.core.model.GridUnit.Companion.PT_PER_MM
import kotlin.math.max
import kotlin.math.roundToInt

/** Paints for one template draw; widths in page pt, never thinner than one device pixel. Not thread-safe. */
internal class TemplatePaints {
    /** Regular rules and grid lines. */
    val rule = stroke()

    /** Emphasized thin lines: every 5th graph line, music staves. */
    val dark = stroke()

    /** 1 pt separators: Cornell zones, planner boxes, headers. */
    val strong = stroke()

    /** Graph axes. */
    val axis = stroke()

    /** Red margin line of lined pages. */
    val margin = stroke().apply { color = MARGIN_ARGB }

    /** Dots of dotted pages (round points). */
    val dot = stroke().apply { strokeCap = Paint.Cap.ROUND }

    /** Planner labels. */
    val label =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_ARGB
            typeface = Typeface.DEFAULT
            isLinearText = true
            isSubpixelText = true
        }

    /** CUSTOM template images. */
    val bitmap = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)

    /** Sets colors from the template line color and widths for [scale] px per pt. */
    fun configure(
        lineArgb: Int,
        scale: Float,
    ) {
        val onePx = 1f / scale
        rule.color = lineArgb
        rule.strokeWidth = max(RULE_PT, onePx)
        dark.color = darken(lineArgb, DARK_FRACTION)
        dark.strokeWidth = max(RULE_PT, onePx)
        strong.color = darken(lineArgb, DARK_FRACTION)
        strong.strokeWidth = max(SEPARATOR_PT, onePx)
        axis.color = darken(lineArgb, AXIS_FRACTION)
        axis.strokeWidth = max(SEPARATOR_PT, onePx)
        margin.strokeWidth = max(MARGIN_LINE_PT, onePx)
        dot.color = lineArgb
        dot.strokeWidth = max(DOT_PT, onePx)
    }

    private fun stroke() =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.BUTT
        }

    companion object {
        const val RULE_PT = 0.5f
        const val SEPARATOR_PT = 1f
        const val MARGIN_LINE_PT = 0.75f
        const val DOT_PT = 1.2f
        const val MARGIN_ARGB = 0xFFF2A6A6.toInt()
        const val LABEL_ARGB = 0xFF8A94A6.toInt()
        const val DARK_FRACTION = 0.2f
        const val AXIS_FRACTION = 0.45f

        /** Bottom margin of ruled areas (12 mm, 07-text-engine.md#flows-and-frames). */
        const val BOTTOM_MARGIN_PT = 12f * PT_PER_MM
    }
}

/** [argb] moved towards black by [fraction], alpha kept. */
internal fun darken(
    argb: Int,
    fraction: Float,
): Int {
    val keep = 1f - fraction
    val r = ((argb shr RED_SHIFT and CHANNEL) * keep).roundToInt()
    val g = ((argb shr GREEN_SHIFT and CHANNEL) * keep).roundToInt()
    val b = ((argb and CHANNEL) * keep).roundToInt()
    return (argb and ALPHA_MASK) or (r shl RED_SHIFT) or (g shl GREEN_SHIFT) or b
}

private const val CHANNEL = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val ALPHA_MASK = 0xFF shl 24
