package dev.folio.core.model.geometry

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 2D affine transform in page space: x' = a*x + c*y + tx, y' = b*x + d*y + ty
 * (the column order of android.graphics.Matrix: [a c tx; b d ty; 0 0 1]).
 */
data class Affine(
    val a: Float,
    val b: Float,
    val c: Float,
    val d: Float,
    val tx: Float,
    val ty: Float,
) {
    /** Determinant of the linear part. */
    val determinant: Float get() = (a.toDouble() * d - b.toDouble() * c).toFloat()

    /** True if the transform can be inverted. */
    val isInvertible: Boolean get() = abs(determinant) > MIN_DETERMINANT

    /** `this ∘ other`: the result maps p to `this.map(other.map(p))` (apply [other] first). */
    fun compose(other: Affine): Affine {
        // Double intermediates: float products lose ~1e-4 pt on translations of a few hundred pt.
        val a0 = a.toDouble()
        val b0 = b.toDouble()
        val c0 = c.toDouble()
        val d0 = d.toDouble()
        return Affine(
            a = (a0 * other.a + c0 * other.b).toFloat(),
            b = (b0 * other.a + d0 * other.b).toFloat(),
            c = (a0 * other.c + c0 * other.d).toFloat(),
            d = (b0 * other.c + d0 * other.d).toFloat(),
            tx = (a0 * other.tx + c0 * other.ty + tx).toFloat(),
            ty = (b0 * other.tx + d0 * other.ty + ty).toFloat(),
        )
    }

    /** Applies this transform after [first]; same as `compose(first)`. Reads left to right in pipelines. */
    fun after(first: Affine): Affine = compose(first)

    /** The inverse transform. Throws for singular transforms (programmer error; check [isInvertible]). */
    fun invert(): Affine {
        val det = a.toDouble() * d - b.toDouble() * c
        require(abs(det) > MIN_DETERMINANT) { "Affine is not invertible: $this" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(
            a = ia.toFloat(),
            b = ib.toFloat(),
            c = ic.toFloat(),
            d = id.toFloat(),
            tx = (-(ia * tx + ic * ty)).toFloat(),
            ty = (-(ib * tx + id * ty)).toFloat(),
        )
    }

    /** Maps x of point (x, y). */
    fun mapX(
        x: Float,
        y: Float,
    ): Float = a * x + c * y + tx

    /** Maps y of point (x, y). */
    fun mapY(
        x: Float,
        y: Float,
    ): Float = b * x + d * y + ty

    /** Maps a point. */
    fun mapPoint(p: PointPt): PointPt = PointPt(mapX(p.x, p.y), mapY(p.x, p.y))

    /** Axis-aligned bounds of the mapped rect (all four corners). */
    fun mapRect(r: RectPt): RectPt {
        if (r.isEmpty) return r
        val x0 = mapX(r.left, r.top)
        val y0 = mapY(r.left, r.top)
        val x1 = mapX(r.right, r.top)
        val y1 = mapY(r.right, r.top)
        val x2 = mapX(r.right, r.bottom)
        val y2 = mapY(r.right, r.bottom)
        val x3 = mapX(r.left, r.bottom)
        val y3 = mapY(r.left, r.bottom)
        return RectPt(
            min(min(x0, x1), min(x2, x3)),
            min(min(y0, y1), min(y2, y3)),
            max(max(x0, x1), max(x2, x3)),
            max(max(y0, y1), max(y2, y3)),
        )
    }

    /** Factories. */
    companion object {
        private const val MIN_DETERMINANT = 1e-12

        /** The identity transform. */
        val IDENTITY = Affine(1f, 0f, 0f, 1f, 0f, 0f)

        /** Translation by (dx, dy). */
        fun translate(
            dxPt: Float,
            dyPt: Float,
        ): Affine = Affine(1f, 0f, 0f, 1f, dxPt, dyPt)

        /** Scale by (sx, sy) around the pivot (px, py). */
        fun scale(
            sx: Float,
            sy: Float,
            pivotX: Float = 0f,
            pivotY: Float = 0f,
        ): Affine = Affine(sx, 0f, 0f, sy, pivotX - sx * pivotX, pivotY - sy * pivotY)

        /** Rotation by [angleDeg] (clockwise on screen, y down) around the pivot (px, py). */
        fun rotate(
            angleDeg: Float,
            pivotX: Float = 0f,
            pivotY: Float = 0f,
        ): Affine {
            val rad = Math.toRadians(angleDeg.toDouble())
            val cosV = cos(rad).toFloat()
            val sinV = sin(rad).toFloat()
            return Affine(
                a = cosV,
                b = sinV,
                c = -sinV,
                d = cosV,
                tx = pivotX - cosV * pivotX + sinV * pivotY,
                ty = pivotY - sinV * pivotX - cosV * pivotY,
            )
        }
    }
}
