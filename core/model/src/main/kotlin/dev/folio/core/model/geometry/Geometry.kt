package dev.folio.core.model.geometry

import kotlin.math.hypot

private const val MIN_POLYGON_POINTS = 3

/** Even-odd point-in-polygon test; the polygon is closed implicitly. Fewer than 3 points contain nothing. */
fun polygonContains(
    points: List<PointPt>,
    p: PointPt,
): Boolean {
    if (points.size < MIN_POLYGON_POINTS) return false
    var inside = false
    var j = points.size - 1
    for (i in points.indices) {
        val pi = points[i]
        val pj = points[j]
        if ((pi.y > p.y) != (pj.y > p.y)) {
            val xCross = (pj.x - pi.x) * (p.y - pi.y) / (pj.y - pi.y) + pi.x
            if (p.x < xCross) inside = !inside
        }
        j = i
    }
    return inside
}

/** Distance from [p] to the segment [a]-[b] (to [a] if the segment is a point). */
fun segmentDistance(
    p: PointPt,
    a: PointPt,
    b: PointPt,
): Float = segmentDistance(p.x, p.y, a.x, a.y, b.x, b.y)

/** Allocation-free form of [segmentDistance] for hit tests over stroke arrays. */
fun segmentDistance(
    px: Float,
    py: Float,
    ax: Float,
    ay: Float,
    bx: Float,
    by: Float,
): Float {
    val dx = bx - ax
    val dy = by - ay
    val lenSq = dx * dx + dy * dy
    if (lenSq == 0f) return hypot(px - ax, py - ay)
    val t = (((px - ax) * dx + (py - ay) * dy) / lenSq).coerceIn(0f, 1f)
    return hypot(px - (ax + t * dx), py - (ay + t * dy))
}

/** Total length of the open polyline through [points]. */
fun polylineLength(points: List<PointPt>): Float {
    var sum = 0f
    for (i in 1 until points.size) {
        sum += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
    }
    return sum
}

/** Total length of the open polyline through parallel coordinate arrays. */
fun polylineLength(
    xs: FloatArray,
    ys: FloatArray,
): Float {
    var sum = 0f
    for (i in 1 until xs.size) {
        sum += hypot(xs[i] - xs[i - 1], ys[i] - ys[i - 1])
    }
    return sum
}
