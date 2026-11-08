package dev.folio.core.model.lasso

import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Handles of a lasso selection box: eight on the box ([fx], [fy] are fractions of its width and height) and the rotate knob above it. */
enum class SelectionHandle(
    val fx: Float,
    val fy: Float,
) {
    TOP_LEFT(0f, 0f),
    TOP(0.5f, 0f),
    TOP_RIGHT(1f, 0f),
    RIGHT(1f, 0.5f),
    BOTTOM_RIGHT(1f, 1f),
    BOTTOM(0.5f, 1f),
    BOTTOM_LEFT(0f, 1f),
    LEFT(0f, 0.5f),
    ROTATE(0.5f, 0f),
    ;

    /** True for the four corners (they keep the aspect ratio). */
    val isCorner: Boolean get() = fx != HALF && fy != HALF && this != ROTATE

    private companion object {
        const val HALF = 0.5f
    }
}

/**
 * Geometry of the lasso selection box (06-ink-input.md#lasso): handle positions and the transform a drag makes. A drag
 * inside moves, a corner scales keeping the aspect around the opposite corner, an edge scales one axis around the
 * opposite edge, the rotate knob turns around the box center with [DETENT_DEG] detents. Pure, in page pt.
 */
object SelectionBox {
    /** Rotation detents. */
    const val DETENT_DEG = 15f

    /** A rotation this near a detent snaps onto it. */
    const val SNAP_DEG = 3f

    private const val FULL_TURN_DEG = 360f
    private const val HALF_TURN_DEG = 180f
    private const val EPS = 1e-3f

    /** Where [handle] of [box] sits; the rotate knob [rotateOffsetPt] above the top edge's middle. */
    fun position(
        box: RectPt,
        handle: SelectionHandle,
        rotateOffsetPt: Float,
    ): PointPt {
        val x = box.left + box.widthPt * handle.fx
        val y = box.top + box.heightPt * handle.fy
        return if (handle == SelectionHandle.ROTATE) PointPt(x, box.top - rotateOffsetPt) else PointPt(x, y)
    }

    /** The handle of [box] nearest [p] within [radiusPt], or null. */
    fun handleAt(
        box: RectPt,
        p: PointPt,
        radiusPt: Float,
        rotateOffsetPt: Float,
    ): SelectionHandle? {
        var best: SelectionHandle? = null
        var bestDistance = radiusPt
        for (h in SelectionHandle.entries) {
            val at = position(box, h, rotateOffsetPt)
            val d = hypot(at.x - p.x, at.y - p.y)
            if (d <= bestDistance) {
                best = h
                bestDistance = d
            }
        }
        return best
    }

    /**
     * The transform of a drag from [from] to [to] on [handle] of [box] (null: inside the box, a move). Scales keep the
     * box at least [minSizePt] on each side it changes (or its size when smaller) and never mirror it.
     */
    fun drag(
        box: RectPt,
        handle: SelectionHandle?,
        from: PointPt,
        to: PointPt,
        minSizePt: Float,
    ): Affine {
        val dx = to.x - from.x
        val dy = to.y - from.y
        val w = box.widthPt
        val h = box.heightPt
        return when {
            handle == null -> {
                Affine.translate(dx, dy)
            }

            handle == SelectionHandle.ROTATE -> {
                val c = box.center
                Affine.rotate(rotation(box, from, to), c.x, c.y)
            }

            handle.isCorner -> {
                val px = box.left + w * (1f - handle.fx)
                val py = box.top + h * (1f - handle.fy)
                val vx = box.left + w * handle.fx - px
                val vy = box.top + h * handle.fy - py
                val len2 = vx * vx + vy * vy
                val s = if (len2 < EPS) 1f else ((vx + dx) * vx + (vy + dy) * vy) / len2
                val sMin = max(minScale(w, minSizePt), minScale(h, minSizePt))
                val clamped = max(s, sMin)
                Affine.scale(clamped, clamped, px, py)
            }

            handle == SelectionHandle.TOP || handle == SelectionHandle.BOTTOM -> {
                // y only.
                val py = box.top + h * (1f - handle.fy)
                val sy = axisScale(h, if (handle.fy == 0f) -dy else dy, minSizePt)
                Affine.scale(1f, sy, box.left, py)
            }

            else -> {
                val px = box.left + w * (1f - handle.fx)
                val sx = axisScale(w, if (handle.fx == 0f) -dx else dx, minSizePt)
                Affine.scale(sx, 1f, px, box.top)
            }
        }
    }

    /**
     * The angle in degrees a rotate drag from [from] to [to] turns [box] around its center (clockwise positive, in
     * -180..180), snapped onto a [DETENT_DEG] multiple within [SNAP_DEG].
     */
    fun rotation(
        box: RectPt,
        from: PointPt,
        to: PointPt,
    ): Float {
        val c = box.center
        val a0 = atan2(from.y - c.y, from.x - c.x)
        val a1 = atan2(to.y - c.y, to.x - c.x)
        var deg = Math.toDegrees((a1 - a0).toDouble()).toFloat()
        if (deg > HALF_TURN_DEG) deg -= FULL_TURN_DEG
        if (deg < -HALF_TURN_DEG) deg += FULL_TURN_DEG
        val detent = (deg / DETENT_DEG).roundToInt() * DETENT_DEG
        return if (abs(deg - detent) <= SNAP_DEG) detent else deg
    }

    // Scale of one axis of length [sizePt] grown by [growPt], at least [minSizePt] long (or as it is when shorter).
    private fun axisScale(
        sizePt: Float,
        growPt: Float,
        minSizePt: Float,
    ): Float {
        if (sizePt < EPS) return 1f
        return max((sizePt + growPt) / sizePt, minScale(sizePt, minSizePt))
    }

    private fun minScale(
        sizePt: Float,
        minSizePt: Float,
    ): Float = if (sizePt < EPS) 0f else minOf(1f, minSizePt / sizePt)
}
