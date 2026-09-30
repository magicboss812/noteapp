package dev.folio.core.ink.erase

import dev.folio.core.model.StrokeInputs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * Partial eraser geometry on stored inputs (06-ink-input.md#erasers): inputs within [reachPt] of one
 * eraser segment (eraser radius plus half the stroke width, measured to the centerline) are removed,
 * and a centerline segment the eraser crosses between two kept inputs is cut. The remaining runs
 * become fragments; runs with fewer than [MIN_INPUTS] inputs or shorter than [MIN_LENGTH_PT] are dropped.
 */
object StrokeSplitter {
    /** Fragments keep at least this many inputs. */
    const val MIN_INPUTS = 2

    /** Fragments shorter than this (pt, along the centerline) are dropped. */
    const val MIN_LENGTH_PT = 1f

    /**
     * Fragments of [inputs] after erasing along segment ([ax], [ay]) -> ([bx], [by]); null when the
     * eraser misses the stroke, empty when nothing survives. Times of each fragment restart at 0.
     */
    @Suppress("LongParameterList") // one segment as plain floats: called per eraser move
    fun split(
        inputs: StrokeInputs,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        reachPt: Float,
    ): List<StrokeInputs>? {
        val n = inputs.size
        val removed = BooleanArray(n) { pointSegmentDistance(inputs.x[it], inputs.y[it], ax, ay, bx, by) <= reachPt }
        // cut[i]: the centerline between kept inputs i and i + 1 is crossed.
        val cut =
            BooleanArray(maxOf(n - 1, 0)) { i ->
                !removed[i] &&
                    !removed[i + 1] &&
                    segmentDistance(inputs.x[i], inputs.y[i], inputs.x[i + 1], inputs.y[i + 1], ax, ay, bx, by) <= reachPt
            }
        if (removed.none { it } && cut.none { it }) return null
        return fragments(inputs, removed, cut)
    }

    private fun fragments(
        inputs: StrokeInputs,
        removed: BooleanArray,
        cut: BooleanArray,
    ): List<StrokeInputs> {
        val n = inputs.size
        val out = ArrayList<StrokeInputs>()
        var start = -1
        for (i in 0..n) {
            val keep = i < n && !removed[i]
            if (keep && start < 0) start = i
            val runEnds = start >= 0 && (!keep || i == n - 1 || cut[i])
            if (runEnds) {
                val end = if (keep) i + 1 else i
                if (end - start >= MIN_INPUTS && length(inputs, start, end) >= MIN_LENGTH_PT) out += slice(inputs, start, end)
                start = -1
            }
        }
        return out
    }

    /** Distance from point ([px], [py]) to segment ([ax], [ay]) -> ([bx], [by]). */
    @Suppress("LongParameterList")
    fun pointSegmentDistance(
        px: Float,
        py: Float,
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
    ): Float {
        val dx = bx - ax
        val dy = by - ay
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((px - ax) * dx + (py - ay) * dy) / len2).coerceIn(0f, 1f)
        return hypot(px - (ax + t * dx), py - (ay + t * dy))
    }

    /** Distance between segments p1-p2 and q1-q2 (0 when they cross). */
    @Suppress("LongParameterList")
    fun segmentDistance(
        p1x: Float,
        p1y: Float,
        p2x: Float,
        p2y: Float,
        q1x: Float,
        q1y: Float,
        q2x: Float,
        q2y: Float,
    ): Float {
        if (crosses(p1x, p1y, p2x, p2y, q1x, q1y, q2x, q2y)) return 0f
        return minOf(
            minOf(pointSegmentDistance(p1x, p1y, q1x, q1y, q2x, q2y), pointSegmentDistance(p2x, p2y, q1x, q1y, q2x, q2y)),
            minOf(pointSegmentDistance(q1x, q1y, p1x, p1y, p2x, p2y), pointSegmentDistance(q2x, q2y, p1x, p1y, p2x, p2y)),
        )
    }

    @Suppress("LongParameterList")
    private fun crosses(
        p1x: Float,
        p1y: Float,
        p2x: Float,
        p2y: Float,
        q1x: Float,
        q1y: Float,
        q2x: Float,
        q2y: Float,
    ): Boolean {
        val d1 = cross(q1x, q1y, q2x, q2y, p1x, p1y)
        val d2 = cross(q1x, q1y, q2x, q2y, p2x, p2y)
        val d3 = cross(p1x, p1y, p2x, p2y, q1x, q1y)
        val d4 = cross(p1x, p1y, p2x, p2y, q2x, q2y)
        // Touching and collinear cases are covered by the endpoint distances.
        return ((d1 > 0f && d2 < 0f) || (d1 < 0f && d2 > 0f)) && ((d3 > 0f && d4 < 0f) || (d3 < 0f && d4 > 0f))
    }

    @Suppress("LongParameterList")
    private fun cross(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        cx: Float,
        cy: Float,
    ): Float = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)

    private fun length(
        inputs: StrokeInputs,
        start: Int,
        end: Int,
    ): Float {
        var sum = 0.0
        for (i in start + 1 until end) {
            val dx = (inputs.x[i] - inputs.x[i - 1]).toDouble()
            val dy = (inputs.y[i] - inputs.y[i - 1]).toDouble()
            sum += sqrt(dx * dx + dy * dy)
        }
        return sum.toFloat()
    }

    private fun slice(
        inputs: StrokeInputs,
        start: Int,
        end: Int,
    ): StrokeInputs {
        val t0 = inputs.tMs[start]
        val t = FloatArray(end - start) { inputs.tMs[start + it] - t0 }
        return StrokeInputs(
            inputs.x.copyOfRange(start, end),
            inputs.y.copyOfRange(start, end),
            t,
            inputs.pressure?.copyOfRange(start, end),
            inputs.tiltDeg?.copyOfRange(start, end),
            inputs.orientationDeg?.copyOfRange(start, end),
            inputs.tool,
        )
    }
}
