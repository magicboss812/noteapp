package dev.folio.core.ink.shape

import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.segmentDistance
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A shape fit to a stroke (06-ink-input.md#shape-recognition): [kind] with control points as `ShapeGeometry` stores
 * them (A-064), turned by [rotationDeg] around the center of their bounds.
 */
data class RecognizedShape(
    val kind: ShapeKind,
    val points: List<PointPt>,
    val rotationDeg: Float = 0f,
) {
    /**
     * This shape while the pen keeps moving after the hold, from [from] (where it held) to [to]: a line or arrow ends at
     * [to]; other kinds scale and turn around their center so that [from] lands on [to] (A-065).
     */
    fun followed(
        from: PointPt,
        to: PointPt,
    ): RecognizedShape {
        if (kind == ShapeKind.LINE || kind == ShapeKind.ARROW) return copy(points = listOf(points[0], to))
        val c = center(points)
        val reach = hypot(from.x - c.x, from.y - c.y)
        if (reach < MIN_REACH_PT) return this
        val scale = (hypot(to.x - c.x, to.y - c.y) / reach).coerceIn(MIN_FOLLOW_SCALE, MAX_FOLLOW_SCALE)
        val turnDeg = Math.toDegrees(atan2(to.y - c.y, to.x - c.x).toDouble() - atan2(from.y - c.y, from.x - c.x).toDouble())
        val moved = points.map { PointPt(c.x + (it.x - c.x) * scale, c.y + (it.y - c.y) * scale) }
        return RecognizedShape(kind, moved, normalizedDeg(rotationDeg + turnDeg.toFloat()))
    }

    private companion object {
        const val MIN_REACH_PT = 0.5f
        const val MIN_FOLLOW_SCALE = 0.05f
        const val MAX_FOLLOW_SCALE = 20f
    }
}

/**
 * Fits shapes to pen strokes (06-ink-input.md#shape-recognition, A-065). Pure; runs once per hold.
 * Open strokes: a line when straight, an arrow when a straight shaft to the point farthest from the start ends in a
 * short head with barbs on both sides. Closed strokes (the ends within a fifth of the size), resampled to
 * [RESAMPLE_POINTS] around the loop: a [PolygonFit] (triangle, rectangle, polygon) or an [EllipseFit]; when both fit,
 * the one nearer to the stroke wins. Anything else (scribbles, letters, open curves) is null: the stroke stays ink.
 */
object ShapeRecognizer {
    /** Points a stroke is resampled to. */
    const val RESAMPLE_POINTS = 64

    /**
     * The shape the first [count] points ([xs], [ys], page pt) of a stroke draw, or null. Strokes whose bounds have a
     * diagonal under [minSizePt] are never shapes.
     */
    fun recognize(
        xs: FloatArray,
        ys: FloatArray,
        count: Int,
        minSizePt: Float = 0f,
    ): RecognizedShape? {
        val size = if (count < MIN_INPUTS) 0f else StrokePoints.diagonal(xs, ys, count)
        val raw = if (size < max(minSizePt, MIN_SIZE_PT)) null else StrokePoints.deduped(xs, ys, count, size * DEDUPE_FRACTION)
        if (raw == null || raw.n < MIN_INPUTS) return null
        val open = raw.resampled(RESAMPLE_POINTS, closed = false)
        return when {
            straight(open, 0, open.n - 1) -> RecognizedShape(ShapeKind.LINE, listOf(open.point(0), open.point(open.n - 1)))
            open.dist(0, open.n - 1) > CLOSE_GAP * size -> arrow(open)
            else -> closed(raw.trimmedOvershoot().resampled(RESAMPLE_POINTS, closed = true), size)
        }
    }

    private fun closed(
        loop: StrokePoints,
        size: Float,
    ): RecognizedShape? {
        val polygon = PolygonFit.fit(loop, size)
        val ellipse = EllipseFit.fit(loop, size)
        return when {
            polygon == null -> ellipse?.shape
            ellipse == null -> polygon.shape
            polygon.meanDistance <= ellipse.meanDistance -> polygon.shape
            else -> ellipse.shape
        }
    }

    // Arrow: a straight shaft from the start to the point farthest from it, then a short head around that tip.
    private fun arrow(p: StrokePoints): RecognizedShape? {
        // The pen passes the tip again between the barbs: take the first pass (the first point within 3 % of the farthest).
        var far = 0f
        for (i in 1 until p.n) far = max(far, p.dist(0, i))
        var tip = (1 until p.n).first { p.dist(0, it) >= (1f - ARROW_TIP_SLACK) * far }
        while (tip + 1 < p.n && p.dist(0, tip + 1) > p.dist(0, tip)) tip++
        val shaft = p.dist(0, tip)
        val head = if (tip < p.n / ARROW_MIN_SHAFT_SHARE || tip == p.n - 1) 0f else p.length(tip, p.n - 1)
        val shaped = head in ARROW_HEAD_MIN * shaft..ARROW_HEAD_MAX * shaft && straight(p, 0, tip) && barbed(p, tip, shaft)
        return if (shaped) RecognizedShape(ShapeKind.ARROW, listOf(p.point(0), p.point(tip))) else null
    }

    // The samples after the tip stay near it, behind it, and reach out to both sides of the shaft.
    private fun barbed(
        p: StrokePoints,
        tip: Int,
        shaft: Float,
    ): Boolean {
        val bx = (p.x[0] - p.x[tip]) / shaft
        val by = (p.y[0] - p.y[tip]) / shaft
        var reach = 0f
        var left = 0f
        var right = 0f
        var ahead = 0f
        for (i in tip + 1 until p.n) {
            val dx = p.x[i] - p.x[tip]
            val dy = p.y[i] - p.y[tip]
            reach = max(reach, hypot(dx, dy))
            ahead = max(ahead, -(dx * bx + dy * by))
            val side = bx * dy - by * dx
            left = max(left, side)
            right = max(right, -side)
        }
        return reach in ARROW_HEAD_MIN_REACH * shaft..ARROW_HEAD_REACH * shaft &&
            ahead <= ARROW_FORWARD_SLACK * shaft &&
            min(left, right) > ARROW_BARB_SIDE * shaft
    }

    // Straight from [from] to [to]: the chord is 98 % of the path, or a wobbly path (chord at least 90 %) stays within 5 %
    // of the chord's length from it (A-065).
    private fun straight(
        p: StrokePoints,
        from: Int,
        to: Int,
    ): Boolean {
        val chord = p.dist(from, to)
        val ratio = chord / p.length(from, to).coerceAtLeast(MIN_SIZE_PT)
        if (ratio < WOBBLY_LINE_STRAIGHTNESS) return false
        var bow = 0f
        for (i in from + 1 until to) bow = max(bow, segmentDistance(p.x[i], p.y[i], p.x[from], p.y[from], p.x[to], p.y[to]))
        return ratio >= LINE_STRAIGHTNESS || bow <= WOBBLY_LINE_BOW * chord
    }

    private const val MIN_INPUTS = 3
    private const val MIN_SIZE_PT = 1f
    private const val DEDUPE_FRACTION = 0.01f
    private const val LINE_STRAIGHTNESS = 0.98f
    private const val WOBBLY_LINE_STRAIGHTNESS = 0.9f
    private const val WOBBLY_LINE_BOW = 0.05f
    private const val CLOSE_GAP = 0.2f

    private const val ARROW_MIN_SHAFT_SHARE = 3
    private const val ARROW_TIP_SLACK = 0.03f
    private const val ARROW_HEAD_MIN = 0.15f
    private const val ARROW_HEAD_MAX = 1.6f
    private const val ARROW_HEAD_REACH = 0.5f
    private const val ARROW_HEAD_MIN_REACH = 0.08f
    private const val ARROW_FORWARD_SLACK = 0.1f
    private const val ARROW_BARB_SIDE = 0.04f
}

private fun center(points: List<PointPt>): PointPt {
    var minX = Float.MAX_VALUE
    var minY = Float.MAX_VALUE
    var maxX = -Float.MAX_VALUE
    var maxY = -Float.MAX_VALUE
    for (p in points) {
        minX = min(minX, p.x)
        maxX = max(maxX, p.x)
        minY = min(minY, p.y)
        maxY = max(maxY, p.y)
    }
    return PointPt((minX + maxX) / 2f, (minY + maxY) / 2f)
}

private fun normalizedDeg(deg: Float): Float {
    var d = deg % FULL_TURN_DEG
    if (d > HALF_TURN_DEG) d -= FULL_TURN_DEG
    if (d <= -HALF_TURN_DEG) d += FULL_TURN_DEG
    return d
}

private const val FULL_TURN_DEG = 360f
private const val HALF_TURN_DEG = 180f
