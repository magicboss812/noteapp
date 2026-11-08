package dev.folio.core.ink.shape

import dev.folio.core.model.InputTool
import dev.folio.core.model.StrokeInputs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max

/**
 * Straight highlighter strokes (06-ink-input.md#shape-recognition, D-024): inputs on the segment between two page points,
 * about [STEP_PT] apart so the partial eraser cuts them like drawn strokes, times spread evenly, no pressure or tilt.
 */
object StraightStroke {
    /** Spacing of the inputs, pt. */
    const val STEP_PT = 2f

    /** Inputs from ([x0], [y0]) to ([x1], [y1]) (page pt) over [durationMs]. */
    fun inputs(
        x0: Float,
        y0: Float,
        x1: Float,
        y1: Float,
        durationMs: Float,
    ): StrokeInputs {
        val n = max(2, ceil(hypot(x1 - x0, y1 - y0) / STEP_PT).toInt() + 1)
        val x = FloatArray(n)
        val y = FloatArray(n)
        val t = FloatArray(n)
        val last = n - 1
        for (i in 0..last) {
            val f = i.toFloat() / last
            x[i] = x0 + (x1 - x0) * f
            y[i] = y0 + (y1 - y0) * f
            t[i] = max(0f, durationMs) * f
        }
        x[last] = x1
        y[last] = y1
        return StrokeInputs(x, y, t, null, null, null, InputTool.STYLUS)
    }
}
