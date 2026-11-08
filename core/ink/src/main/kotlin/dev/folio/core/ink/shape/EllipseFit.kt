package dev.folio.core.ink.shape

import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Ellipse fit of a closed loop (06-ink-input.md#shape-recognition): principal axes of the samples around their centroid,
 * then least squares for u x'^2 + v y'^2 = 1 along them. Accepted below a mean normalized residual |r - 1| of 0.08; a
 * circle when the axes are within 10 %, else snapped to the page axes within 8 deg.
 */
internal object EllipseFit {
    /** The ellipse [loop] (evenly resampled, closed) draws, or null; [size] is the stroke's bounds diagonal. */
    fun fit(
        loop: StrokePoints,
        size: Float,
    ): Fit? {
        val frame = Frame.of(loop)
        val (u, v) = coefficients(loop, frame) ?: return null
        var residual = 0.0
        var distance = 0.0
        for (i in 0 until loop.n) {
            val lx = frame.localX(loop, i)
            val ly = frame.localY(loop, i)
            val r = sqrt(u * lx * lx + v * ly * ly)
            residual += abs(r - 1)
            if (r > EPSILON) distance += hypot(lx, ly) * abs(1 - 1 / r) // along the ray from the center
        }
        if (residual / loop.n >= MAX_RESIDUAL) return null
        return Fit(shape(frame, (1 / sqrt(u)).toFloat(), (1 / sqrt(v)).toFloat()), (distance / loop.n).toFloat() / size)
    }

    // u and v of u x'^2 + v y'^2 = 1 by least squares in [frame]; null when degenerate or not an ellipse.
    private fun coefficients(
        loop: StrokePoints,
        frame: Frame,
    ): Pair<Double, Double>? {
        var x4 = 0.0
        var y4 = 0.0
        var x2y2 = 0.0
        var x2 = 0.0
        var y2 = 0.0
        for (i in 0 until loop.n) {
            val lx = frame.localX(loop, i)
            val ly = frame.localY(loop, i)
            x4 += lx * lx * lx * lx
            y4 += ly * ly * ly * ly
            x2y2 += lx * lx * ly * ly
            x2 += lx * lx
            y2 += ly * ly
        }
        val det = x4 * y4 - x2y2 * x2y2
        val u = (x2 * y4 - y2 * x2y2) / det
        val v = (y2 * x4 - x2 * x2y2) / det
        return if (abs(det) < EPSILON || u <= 0 || v <= 0) null else u to v
    }

    // The ellipse with semi-axes [a] (along the frame's x) and [b], as corners of its unrotated box plus a turn in -45..45 deg.
    private fun shape(
        frame: Frame,
        a: Float,
        b: Float,
    ): RecognizedShape {
        var rx = a
        var ry = b
        var deg = Math.toDegrees(frame.angle).toFloat()
        if (abs(deg) > HALF_RIGHT_DEG) {
            deg -= if (deg > 0) RIGHT_DEG else -RIGHT_DEG
            rx = b
            ry = a
        }
        if (min(rx, ry) / max(rx, ry) > CIRCLE_AXIS_RATIO) {
            rx = (rx + ry) / 2f
            ry = rx
            deg = 0f
        } else if (abs(deg) < AXIS_SNAP_DEG) {
            deg = 0f
        }
        val x = frame.cx.toFloat()
        val y = frame.cy.toFloat()
        return RecognizedShape(ShapeKind.ELLIPSE, listOf(PointPt(x - rx, y - ry), PointPt(x + rx, y + ry)), deg)
    }

    /** Centroid ([cx], [cy]) and principal axis [angle] (rad) of a loop's samples. */
    private class Frame(
        val cx: Double,
        val cy: Double,
        val angle: Double,
    ) {
        private val cosA = cos(angle)
        private val sinA = sin(angle)

        fun localX(
            p: StrokePoints,
            i: Int,
        ): Double = cosA * (p.x[i] - cx) + sinA * (p.y[i] - cy)

        fun localY(
            p: StrokePoints,
            i: Int,
        ): Double = -sinA * (p.x[i] - cx) + cosA * (p.y[i] - cy)

        companion object {
            fun of(p: StrokePoints): Frame {
                var cx = 0.0
                var cy = 0.0
                for (i in 0 until p.n) {
                    cx += p.x[i]
                    cy += p.y[i]
                }
                cx /= p.n
                cy /= p.n
                var sxx = 0.0
                var syy = 0.0
                var sxy = 0.0
                for (i in 0 until p.n) {
                    val dx = p.x[i] - cx
                    val dy = p.y[i] - cy
                    sxx += dx * dx
                    syy += dy * dy
                    sxy += dx * dy
                }
                return Frame(cx, cy, atan2(2 * sxy, sxx - syy) / 2)
            }
        }
    }

    private const val EPSILON = 1e-9
    private const val MAX_RESIDUAL = 0.08
    private const val CIRCLE_AXIS_RATIO = 0.9f
    private const val RIGHT_DEG = 90f
    private const val HALF_RIGHT_DEG = 45f
    private const val AXIS_SNAP_DEG = 8f
}
