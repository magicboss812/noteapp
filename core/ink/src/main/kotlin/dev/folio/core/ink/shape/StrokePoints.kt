package dev.folio.core.ink.shape

import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.segmentDistance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Points [x], [y] (first [n] used, page pt) of a stroke being fit; indexes wrap around for closed loops. */
internal class StrokePoints(
    val x: FloatArray,
    val y: FloatArray,
    val n: Int,
) {
    fun point(i: Int) = PointPt(x[i], y[i])

    fun dist(
        i: Int,
        j: Int,
    ): Float = hypot(x[i] - x[j], y[i] - y[j])

    /** Path length, closed: back to the start. */
    fun length(closed: Boolean): Float {
        var sum = length(0, n - 1)
        if (closed && n > 1) sum += hypot(x[0] - x[n - 1], y[0] - y[n - 1])
        return sum
    }

    /** Path length from sample [from] to [to]. */
    fun length(
        from: Int,
        to: Int,
    ): Float {
        var sum = 0f
        for (i in from + 1..to) sum += hypot(x[i] - x[i - 1], y[i] - y[i - 1])
        return sum
    }

    /** How far the samples between [a] and [b] (going forward around the loop) bow from the chord, over its length. */
    fun bow(
        a: Int,
        b: Int,
    ): Float {
        val chord = dist(a, b)
        if (chord <= 0f) return Float.MAX_VALUE
        val far = farthestBetween(a, b)
        return if (far < 0) 0f else segmentDistance(far, a, b) / chord
    }

    /** The sample between [a] and [b] (forward around the loop) farthest from their chord; -1 if none lies between. */
    fun farthestBetween(
        a: Int,
        b: Int,
    ): Int {
        var far = -1
        var farDistance = -1f
        var i = (a + 1) % n
        while (i != b) {
            val d = segmentDistance(i, a, b)
            if (d > farDistance) {
                far = i
                farDistance = d
            }
            i = (i + 1) % n
        }
        return far
    }

    private fun segmentDistance(
        i: Int,
        a: Int,
        b: Int,
    ): Float = segmentDistance(x[i], y[i], x[a], y[a], x[b], y[b])

    /** [count] samples evenly spaced along these; closed: around the loop back to the start, the start not repeated. */
    fun resampled(
        count: Int,
        closed: Boolean,
    ): StrokePoints {
        val step = length(closed) / if (closed) count else count - 1
        val rx = FloatArray(count)
        val ry = FloatArray(count)
        rx[0] = x[0]
        ry[0] = y[0]
        var filled = 1
        var along = step
        val segments = if (closed) n else n - 1
        var k = 0
        while (k < segments && filled < count) {
            val ax = x[k]
            val ay = y[k]
            val bx = x[(k + 1) % n]
            val by = y[(k + 1) % n]
            val seg = hypot(bx - ax, by - ay)
            while (along <= seg && filled < count) {
                val t = if (seg > 0f) along / seg else 0f
                rx[filled] = ax + (bx - ax) * t
                ry[filled] = ay + (by - ay) * t
                filled++
                along += step
            }
            along -= seg
            k++
        }
        while (filled < count) {
            rx[filled] = x[n - 1]
            ry[filled] = y[n - 1]
            filled++
        }
        return StrokePoints(rx, ry, count)
    }

    /** A closing stroke often runs past its start: these cut where their last stretch comes nearest to the start. */
    fun trimmedOvershoot(): StrokePoints {
        val from = (n * OVERSHOOT_SEARCH_FROM).toInt().coerceAtLeast(1)
        var end = n - 1
        for (i in from until n) if (dist(0, i) < dist(0, end)) end = i
        if (end == n - 1) return this
        return StrokePoints(x.copyOf(end + 1), y.copyOf(end + 1), end + 1)
    }

    companion object {
        private const val OVERSHOOT_SEARCH_FROM = 0.7f

        /** The first [count] samples, dropping those nearer than [stepPt] to the last kept one (a resting pen adds no path). */
        fun deduped(
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
            stepPt: Float,
        ): StrokePoints {
            val x = FloatArray(count)
            val y = FloatArray(count)
            var n = 0
            for (i in 0 until count) {
                if (n == 0 || i == count - 1 || hypot(xs[i] - x[n - 1], ys[i] - y[n - 1]) >= stepPt) {
                    x[n] = xs[i]
                    y[n] = ys[i]
                    n++
                }
            }
            return StrokePoints(x, y, n)
        }

        /** Diagonal of the bounds of the first [count] samples. */
        fun diagonal(
            xs: FloatArray,
            ys: FloatArray,
            count: Int,
        ): Float {
            var minX = Float.MAX_VALUE
            var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE
            var maxY = -Float.MAX_VALUE
            for (i in 0 until count) {
                minX = min(minX, xs[i])
                maxX = max(maxX, xs[i])
                minY = min(minY, ys[i])
                maxY = max(maxY, ys[i])
            }
            return hypot(maxX - minX, maxY - minY)
        }
    }
}

/** How far the outline turns at [b] coming from [a] and going to [c], 0..180 deg. */
internal fun turnDeg(
    a: PointPt,
    b: PointPt,
    c: PointPt,
): Float {
    val inAngle = atan2(b.y - a.y, b.x - a.x)
    val outAngle = atan2(c.y - b.y, c.x - b.x)
    var d = abs(outAngle - inAngle).toDouble()
    if (d > PI) d = 2 * PI - d
    return Math.toDegrees(d).toFloat()
}
