package dev.folio.core.model.shape

import dev.folio.core.model.Shape
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** What a selection handle of a shape does. */
enum class ShapeHandleKind { RESIZE, ROTATE, POINT }

/**
 * One handle of a selected shape at page position [position]. [index]: RESIZE 0..7 clockwise from the
 * top-left corner (TL, T, TR, R, BR, B, BL, L); POINT = control point index; ROTATE = 0.
 */
data class ShapeHandle(
    val kind: ShapeHandleKind,
    val index: Int,
    val position: PointPt,
)

/**
 * Selection handles of a shape and the edits their drags make (06-ink-input.md#shapes, A-064):
 * 8 resize handles on the unrotated box and a rotate handle above it for boxed kinds, one handle per
 * control point for lines, arrows and polygons.
 */
object ShapeHandles {
    /** Detent spacing of the rotate handle in degrees. */
    const val ROTATE_DETENT_DEG = 15f

    /** The rotate handle snaps to a detent within this many degrees. */
    const val ROTATE_SNAP_DEG = 3f

    /** Smallest box side a resize leaves, in pt. */
    const val MIN_SIDE_PT = 1f

    private const val CORNERS = 8
    private const val FULL_TURN_DEG = 360f
    private const val HALF_TURN_DEG = 180f
    private const val QUARTER_TURN_DEG = 90f

    // Fractions of the box width and height of the 8 resize handles, clockwise from the top-left.
    private val BOX_FX = floatArrayOf(0f, 0.5f, 1f, 1f, 1f, 0.5f, 0f, 0f)
    private val BOX_FY = floatArrayOf(0f, 0f, 0f, 0.5f, 1f, 1f, 1f, 0.5f)

    /** Handles of [shape]; the rotate handle sits [rotateOffsetPt] above the box's top edge. */
    fun handles(
        shape: Shape,
        rotateOffsetPt: Float,
    ): List<ShapeHandle> {
        val out = ArrayList<ShapeHandle>(CORNERS + 1 + shape.points.size)
        val box = RectPt.bounds(shape.points)
        if (shape.kind.hasBoxHandles) {
            for (i in 0 until CORNERS) out += ShapeHandle(ShapeHandleKind.RESIZE, i, ShapeGeometry.toPage(shape, boxPoint(box, i)))
            val top = PointPt(box.center.x, box.top - rotateOffsetPt)
            out += ShapeHandle(ShapeHandleKind.ROTATE, 0, ShapeGeometry.toPage(shape, top))
        }
        if (shape.kind.hasPointHandles) {
            shape.points.forEachIndexed { i, p -> out += ShapeHandle(ShapeHandleKind.POINT, i, ShapeGeometry.toPage(shape, p)) }
        }
        return out
    }

    /** The handle of [handles] nearest to [point] within [radiusPt], points before resize/rotate on ties. */
    fun handleAt(
        handles: List<ShapeHandle>,
        point: PointPt,
        radiusPt: Float,
    ): ShapeHandle? {
        var best: ShapeHandle? = null
        var bestD = radiusPt
        for (h in handles.sortedBy { if (it.kind == ShapeHandleKind.POINT) 0 else 1 }) {
            val d = hypot(h.position.x - point.x, h.position.y - point.y)
            if ((best == null && d <= bestD) || d < bestD) {
                best = h
                bestD = d
            }
        }
        return best
    }

    /**
     * [shape] resized by dragging resize handle [index] to [to] (page pt). The opposite corner or edge
     * stays where it is on the page. Corners keep the aspect ratio when [keepAspect]; edges change one side.
     */
    fun resize(
        shape: Shape,
        index: Int,
        to: PointPt,
        keepAspect: Boolean,
    ): Shape {
        val box = RectPt.bounds(shape.points)
        if (box.widthPt <= 0f && box.heightPt <= 0f) return shape
        val opposite = (index + CORNERS / 2) % CORNERS
        val anchorLocal = boxPoint(box, opposite)
        val anchorPage = ShapeGeometry.toPage(shape, anchorLocal)
        val p = ShapeGeometry.toLocal(shape, to)
        val movesX = index != 1 && index != 5
        val movesY = index != 3 && index != 7
        var sx = if (movesX && box.widthPt > 0f) scaleFor(box, index, p.x - anchorLocal.x, horizontal = true) else 1f
        var sy = if (movesY && box.heightPt > 0f) scaleFor(box, index, p.y - anchorLocal.y, horizontal = false) else 1f
        if (keepAspect && movesX && movesY) {
            val s = max(abs(sx), abs(sy))
            sx = if (sx < 0f) -s else s
            sy = if (sy < 0f) -s else s
        }
        val points = shape.points.map { PointPt(anchorLocal.x + (it.x - anchorLocal.x) * sx, anchorLocal.y + (it.y - anchorLocal.y) * sy) }
        return keepAnchor(shape, ShapeGeometry.withPoints(shape, points), anchorLocal, anchorPage)
    }

    /**
     * [shape] rotated so its rotate handle points at [to] (page pt) from the shape's center; within
     * [ROTATE_SNAP_DEG] of a [ROTATE_DETENT_DEG] multiple the angle snaps to it. Angles stay in (-180, 180].
     */
    fun rotate(
        shape: Shape,
        to: PointPt,
    ): Shape {
        val c = ShapeGeometry.pivot(shape.points)
        val raw = Math.toDegrees(atan2((to.y - c.y).toDouble(), (to.x - c.x).toDouble())).toFloat() + QUARTER_TURN_DEG
        return ShapeGeometry.withPoints(shape, shape.points, normalize(snap(raw)))
    }

    /** [shape] with control point [index] dragged to [to] (page pt); the other points stay where they are. */
    fun movePoint(
        shape: Shape,
        index: Int,
        to: PointPt,
    ): Shape {
        if (index !in shape.points.indices) return shape
        val local = ShapeGeometry.toLocal(shape, to)
        val points = shape.points.toMutableList().also { it[index] = local }
        val ref = if (index == 0) 1 else 0
        val moved = ShapeGeometry.withPoints(shape, points)
        if (ref !in points.indices || shape.rotationDeg == 0f) return moved
        val before = ShapeGeometry.toPage(shape, shape.points[ref])
        val after = ShapeGeometry.toPage(moved, points[ref])
        return translate(moved, before.x - after.x, before.y - after.y)
    }

    /** [shape] moved by ([dxPt], [dyPt]). */
    fun translate(
        shape: Shape,
        dxPt: Float,
        dyPt: Float,
    ): Shape {
        if (dxPt == 0f && dyPt == 0f) return shape
        return ShapeGeometry.withPoints(shape, shape.points.map { PointPt(it.x + dxPt, it.y + dyPt) })
    }

    /** Point [index] of [box]: corners and edge midpoints clockwise from the top-left. */
    fun boxPoint(
        box: RectPt,
        index: Int,
    ): PointPt = PointPt(box.left + box.widthPt * BOX_FX[index], box.top + box.heightPt * BOX_FY[index])

    private fun scaleFor(
        box: RectPt,
        index: Int,
        delta: Float,
        horizontal: Boolean,
    ): Float {
        val side = if (horizontal) box.widthPt else box.heightPt
        // The handle's side relative to the anchor: right/bottom handles are +side away, left/top -side.
        val sign =
            if (horizontal) {
                if (index in 2..4) 1f else -1f
            } else {
                if (index in 4..6) 1f else -1f
            }
        val s = delta * sign / side
        val min = MIN_SIDE_PT / side
        return if (abs(s) < min) (if (s < 0f) -min else min) else s
    }

    private fun keepAnchor(
        old: Shape,
        resized: Shape,
        anchorLocal: PointPt,
        anchorPage: PointPt,
    ): Shape {
        if (old.rotationDeg == 0f) return resized
        // Scaling about the anchor keeps it fixed in local space, but the rotation pivot (box center) moved.
        val after = ShapeGeometry.toPage(resized, anchorLocal)
        return translate(resized, anchorPage.x - after.x, anchorPage.y - after.y)
    }

    private fun snap(deg: Float): Float {
        val nearest = (deg / ROTATE_DETENT_DEG).roundToInt() * ROTATE_DETENT_DEG
        return if (abs(deg - nearest) <= ROTATE_SNAP_DEG) nearest else deg
    }

    private fun normalize(deg: Float): Float {
        var d = deg % FULL_TURN_DEG
        if (d > HALF_TURN_DEG) d -= FULL_TURN_DEG
        if (d <= -HALF_TURN_DEG) d += FULL_TURN_DEG
        return d + 0f // turns -0 into 0
    }
}
