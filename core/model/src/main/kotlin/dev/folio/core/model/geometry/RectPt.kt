package dev.folio.core.model.geometry

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Axis-aligned rectangle in page space (PDF points). Edges are inclusive: a zero-width rect (the bounds
 * of a vertical line) is a valid, non-empty rect. [EMPTY] is the identity for [union].
 */
data class RectPt(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float,
) {
    /** Width in pt (0 for [EMPTY]). */
    val widthPt: Float get() = if (isEmpty) 0f else right - left

    /** Height in pt (0 for [EMPTY]). */
    val heightPt: Float get() = if (isEmpty) 0f else bottom - top

    /** True when the rect contains no point (inverted edges or NaN). */
    val isEmpty: Boolean get() = !(left <= right && top <= bottom)

    /** Center point. */
    val center: PointPt get() = PointPt((left + right) / 2f, (top + bottom) / 2f)

    /** Smallest rect containing both. */
    fun union(other: RectPt): RectPt =
        when {
            other.isEmpty -> this
            isEmpty -> other
            else -> RectPt(min(left, other.left), min(top, other.top), max(right, other.right), max(bottom, other.bottom))
        }

    /** True if the rects share at least one point (touching edges count). */
    fun intersects(other: RectPt): Boolean =
        !isEmpty &&
            !other.isEmpty &&
            left <= other.right &&
            other.left <= right &&
            top <= other.bottom &&
            other.top <= bottom

    /** True if [p] lies inside or on the edge. */
    fun contains(p: PointPt): Boolean = contains(p.x, p.y)

    /** True if (x, y) lies inside or on the edge. */
    fun contains(
        x: Float,
        y: Float,
    ): Boolean = x >= left && x <= right && y >= top && y <= bottom

    /** True if [other] lies completely inside this rect. */
    fun contains(other: RectPt): Boolean =
        !isEmpty &&
            !other.isEmpty &&
            other.left >= left &&
            other.right <= right &&
            other.top >= top &&
            other.bottom <= bottom

    /** Shrinks by [dPt] on every side (negative grows). May become empty. */
    fun inset(dPt: Float): RectPt = if (isEmpty) this else RectPt(left + dPt, top + dPt, right - dPt, bottom - dPt)

    /** Moves by (dx, dy). */
    fun offset(
        dxPt: Float,
        dyPt: Float,
    ): RectPt = if (isEmpty) this else RectPt(left + dxPt, top + dyPt, right + dxPt, bottom + dyPt)

    /** Distance from (x, y) to the nearest point of the rect; 0 inside. */
    fun distanceTo(
        x: Float,
        y: Float,
    ): Float {
        if (isEmpty) return Float.POSITIVE_INFINITY
        val dx = max(max(left - x, 0f), x - right)
        val dy = max(max(top - y, 0f), y - bottom)
        return hypot(dx, dy)
    }

    /** Constants and factories. */
    companion object {
        /** The empty rect; `EMPTY.union(r) == r`. */
        val EMPTY = RectPt(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY)

        /** Rect from an origin and a size. */
        fun ofSize(
            leftPt: Float,
            topPt: Float,
            widthPt: Float,
            heightPt: Float,
        ): RectPt = RectPt(leftPt, topPt, leftPt + widthPt, topPt + heightPt)

        /** Bounds of parallel coordinate arrays ([EMPTY] if there are none). */
        fun bounds(
            xs: FloatArray,
            ys: FloatArray,
        ): RectPt {
            if (xs.isEmpty()) return EMPTY
            var l = xs[0]
            var t = ys[0]
            var r = l
            var b = t
            for (i in 1 until xs.size) {
                l = min(l, xs[i])
                r = max(r, xs[i])
                t = min(t, ys[i])
                b = max(b, ys[i])
            }
            return RectPt(l, t, r, b)
        }

        /** Bounds of [points] ([EMPTY] if there are none). */
        fun bounds(points: List<PointPt>): RectPt = points.fold(EMPTY) { acc, p -> acc.union(RectPt(p.x, p.y, p.x, p.y)) }
    }
}
