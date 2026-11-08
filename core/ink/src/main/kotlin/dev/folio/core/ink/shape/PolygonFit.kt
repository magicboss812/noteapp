package dev.folio.core.ink.shape

import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.segmentDistance
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** A candidate fit with the mean distance of the stroke from it, over the stroke's size. */
internal class Fit(
    val shape: RecognizedShape,
    val meanDistance: Float,
)

/**
 * Polygon fit of a closed loop (06-ink-input.md#shape-recognition, A-065): ShortStraw corners (Wolin et al. 2008: local
 * minima of the chord over 2W samples below 95 % of its median), refined by splitting edges that bow, merging corners
 * under W samples apart and dropping turns under 25 deg. 3..8 vertices with straight edges: a triangle, a rectangle
 * (turns 90 +- 20 deg, axis snap within 8 deg) or a polygon.
 */
internal object PolygonFit {
    /** The polygon [loop] (evenly resampled, closed) draws, or null; [size] is the stroke's bounds diagonal. */
    fun fit(
        loop: StrokePoints,
        size: Float,
    ): Fit? {
        val corners = shortStraw(loop)
        splitCurvedEdges(loop, corners)
        mergeCloseCorners(loop, corners)
        dropFlatCorners(loop, corners)
        val straightEdges = corners.indices.all { k -> loop.bow(corners[k], corners[(k + 1) % corners.size]) <= EDGE_MAX_BOW }
        val vertices = corners.map(loop::point)
        val mean = if (corners.size in MIN_VERTICES..MAX_VERTICES && straightEdges) meanDistance(loop, vertices) / size else Float.MAX_VALUE
        if (mean > MAX_DISTANCE) return null
        val shape =
            when {
                vertices.size == TRIANGLE_VERTICES -> RecognizedShape(ShapeKind.TRIANGLE, vertices)
                vertices.size == RECTANGLE_VERTICES && rightAngled(vertices) -> rectangle(vertices)
                else -> RecognizedShape(ShapeKind.POLYGON, vertices)
            }
        return Fit(shape, mean)
    }

    private fun shortStraw(p: StrokePoints): MutableList<Int> {
        val n = p.n
        val straw = FloatArray(n) { i -> p.dist((i - STRAW_WINDOW + n) % n, (i + STRAW_WINDOW) % n) }
        val threshold = straw.sortedArray()[n / 2] * STRAW_THRESHOLD
        val corners = ArrayList<Int>()
        val start = (0 until n).firstOrNull { straw[it] >= threshold } ?: 0
        var best = -1
        for (step in 1..n) {
            val i = (start + step) % n
            if (straw[i] < threshold) {
                if (best < 0 || straw[i] < straw[best]) best = i
            } else if (best >= 0) {
                corners += best
                best = -1
            }
        }
        if (corners.size < 2) {
            // Too few corners to have edges (a smooth loop): start from a point and the one farthest from it.
            val from = corners.firstOrNull() ?: 0
            corners.clear()
            corners += from
            corners += (0 until n).maxBy { p.dist(from, it) }
        }
        corners.sort()
        return corners
    }

    // A corner ShortStraw missed bends its edge: split the most bowed edge at its farthest sample until all are straight.
    private fun splitCurvedEdges(
        p: StrokePoints,
        corners: MutableList<Int>,
    ) {
        while (corners.size <= MAX_SPLIT_VERTICES) {
            val worst = corners.indices.maxBy { k -> p.bow(corners[k], corners[(k + 1) % corners.size]) }
            val a = corners[worst]
            val b = corners[(worst + 1) % corners.size]
            val far = p.farthestBetween(a, b)
            if (p.bow(a, b) <= SPLIT_BOW || far < 0) return
            corners += far
            corners.sort()
        }
    }

    // Corners fewer than STRAW_WINDOW samples apart are one corner (a rounded one split in two): keep the sharper.
    private fun mergeCloseCorners(
        p: StrokePoints,
        corners: MutableList<Int>,
    ) {
        var k = 0
        while (corners.size > MIN_VERTICES && k < corners.size) {
            val next = (k + 1) % corners.size
            if ((corners[next] - corners[k] + p.n) % p.n >= STRAW_WINDOW) {
                k++
            } else {
                corners.removeAt(if (turnAt(p, corners, k) < turnAt(p, corners, next)) k else next)
                k = 0
            }
        }
    }

    // Vertices where the outline turns less than MIN_TURN_DEG are no corners (wobbles, a start mid-edge).
    private fun dropFlatCorners(
        p: StrokePoints,
        corners: MutableList<Int>,
    ) {
        while (corners.size > MIN_VERTICES) {
            val flattest = corners.indices.minBy { turnAt(p, corners, it) }
            if (turnAt(p, corners, flattest) >= MIN_TURN_DEG) return
            corners.removeAt(flattest)
        }
    }

    private fun turnAt(
        p: StrokePoints,
        corners: List<Int>,
        k: Int,
    ): Float =
        turnDeg(
            p.point(corners[(k - 1 + corners.size) % corners.size]),
            p.point(corners[k]),
            p.point(corners[(k + 1) % corners.size]),
        )

    private fun rightAngled(v: List<PointPt>): Boolean =
        v.indices.all { k -> abs(turnDeg(v[(k + v.size - 1) % v.size], v[k], v[(k + 1) % v.size]) - RIGHT_DEG) <= RIGHT_TOLERANCE_DEG }

    // A rectangle along the edges' mean direction (mod 90 deg), its sides the averages of the opposite corners.
    private fun rectangle(v: List<PointPt>): RecognizedShape {
        var c4 = 0.0
        var s4 = 0.0
        for (k in v.indices) {
            val a = v[k]
            val b = v[(k + 1) % v.size]
            val angle = atan2((b.y - a.y).toDouble(), (b.x - a.x).toDouble())
            c4 += cos(QUARTER_TURNS * angle)
            s4 += sin(QUARTER_TURNS * angle)
        }
        var deg = Math.toDegrees(atan2(s4, c4) / QUARTER_TURNS).toFloat()
        if (abs(deg) < AXIS_SNAP_DEG) deg = 0f
        val cx = v.sumOf { it.x.toDouble() }.toFloat() / v.size
        val cy = v.sumOf { it.y.toDouble() }.toFloat() / v.size
        val rad = Math.toRadians(deg.toDouble())
        val cosA = cos(rad).toFloat()
        val sinA = sin(rad).toFloat()
        // Corners in the rectangle's frame (turned back by deg around their centroid).
        val lx = v.map { cosA * (it.x - cx) + sinA * (it.y - cy) }.sorted()
        val ly = v.map { -sinA * (it.x - cx) + cosA * (it.y - cy) }.sorted()
        val halfW = (lx[2] + lx[3] - lx[0] - lx[1]) / 4f
        val halfH = (ly[2] + ly[3] - ly[0] - ly[1]) / 4f
        val mx = (lx[0] + lx[1] + lx[2] + lx[3]) / 4f
        val my = (ly[0] + ly[1] + ly[2] + ly[3]) / 4f
        val centerX = cx + cosA * mx - sinA * my
        val centerY = cy + sinA * mx + cosA * my
        return RecognizedShape(
            ShapeKind.RECTANGLE,
            listOf(PointPt(centerX - halfW, centerY - halfH), PointPt(centerX + halfW, centerY + halfH)),
            deg,
        )
    }

    private fun meanDistance(
        p: StrokePoints,
        v: List<PointPt>,
    ): Float {
        var sum = 0f
        for (i in 0 until p.n) {
            var best = Float.MAX_VALUE
            for (k in v.indices) {
                val a = v[k]
                val b = v[(k + 1) % v.size]
                best = min(best, segmentDistance(p.x[i], p.y[i], a.x, a.y, b.x, b.y))
            }
            sum += best
        }
        return sum / p.n
    }

    private const val STRAW_WINDOW = 3
    private const val STRAW_THRESHOLD = 0.95f
    private const val SPLIT_BOW = 0.06f
    private const val EDGE_MAX_BOW = 0.09f
    private const val MIN_TURN_DEG = 25f
    private const val MIN_VERTICES = 3
    private const val MAX_VERTICES = 8
    private const val MAX_SPLIT_VERTICES = 12
    private const val TRIANGLE_VERTICES = 3
    private const val RECTANGLE_VERTICES = 4
    private const val MAX_DISTANCE = 0.04f
    private const val RIGHT_DEG = 90f
    private const val RIGHT_TOLERANCE_DEG = 20f
    private const val QUARTER_TURNS = 4.0
    private const val AXIS_SNAP_DEG = 8f
}
