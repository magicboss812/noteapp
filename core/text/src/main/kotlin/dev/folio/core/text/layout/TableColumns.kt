package dev.folio.core.text.layout

import dev.folio.core.model.GridUnit
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Column widths of a table (07-text-engine.md#tables), summing to the frame width. Every column is at least its widest
 * word and [MIN_COLUMN_PT] (or an equal share when the frame is too narrow for that). When the unwrapped contents fit,
 * the frame width is shared in proportion to them; otherwise each column gets its least width plus a share of the
 * rest in proportion to how much more its unwrapped content wants (A-048), so short columns never break words.
 */
internal object TableColumns {
    /** 12 mm. */
    const val MIN_COLUMN_PT = 12f * GridUnit.PT_PER_MM

    /** Widths for columns whose widest word is [leastPt] and unwrapped content [naturalPt] each, filling [totalPt]. */
    fun widths(
        leastPt: FloatArray,
        naturalPt: FloatArray,
        totalPt: Float,
    ): FloatArray {
        val n = naturalPt.size
        if (n == 0) return FloatArray(0)
        val floor = min(MIN_COLUMN_PT, totalPt / n)
        val low = FloatArray(n) { max(leastPt[it], floor) }
        val high = FloatArray(n) { max(naturalPt[it], low[it]) }
        val lowSum = low.sum()
        val highSum = high.sum()
        return when {
            highSum <= totalPt -> FloatArray(n) { high[it] * totalPt / highSum }
            lowSum >= totalPt -> FloatArray(n) { low[it] * totalPt / lowSum }
            else -> FloatArray(n) { low[it] + (high[it] - low[it]) * (totalPt - lowSum) / (highSum - lowSum) }
        }
    }

    /**
     * Left edge of every column from 0, then the right edge of the last one. With a [snapPt] (grid templates) the inner
     * edges move to the nearest multiple of it, so the borders sit on grid lines, keeping every column at least one
     * step wide where the width allows; the outer edges stay at the frame's.
     */
    fun edges(
        widthsPt: FloatArray,
        snapPt: Float = 0f,
    ): FloatArray {
        val n = widthsPt.size
        val out = FloatArray(n + 1)
        for (i in widthsPt.indices) out[i + 1] = out[i] + widthsPt[i]
        if (snapPt <= 0f || n < 2 || out[n] < n * snapPt) return out
        for (i in 1 until n) {
            val snapped = (out[i] / snapPt).roundToInt() * snapPt
            out[i] = snapped.coerceIn(out[i - 1] + snapPt, out[n] - (n - i) * snapPt)
        }
        return out
    }
}
