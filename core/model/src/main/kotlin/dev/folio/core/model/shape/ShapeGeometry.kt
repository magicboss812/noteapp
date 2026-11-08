package dev.folio.core.model.shape

import dev.folio.core.model.ObjectId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.geometry.polygonContains
import dev.folio.core.model.geometry.segmentDistance
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sin

/** True for kinds with an inside (lines and arrows never fill). */
val ShapeKind.fillable: Boolean get() = this != ShapeKind.LINE && this != ShapeKind.ARROW

/** True for kinds whose control points are dragged one by one (06-ink-input.md#shapes). */
val ShapeKind.hasPointHandles: Boolean get() = this == ShapeKind.LINE || this == ShapeKind.ARROW || this == ShapeKind.POLYGON

/** True for kinds with a resize box and rotate handle (lines and arrows edit by their two points, A-064). */
val ShapeKind.hasBoxHandles: Boolean get() = this != ShapeKind.LINE && this != ShapeKind.ARROW

/**
 * Shape geometry (06-ink-input.md#shapes, A-064). Control points per kind: LINE and ARROW = start, end;
 * RECTANGLE and ELLIPSE = two opposite corners of the unrotated box; TRIANGLE = three vertices;
 * POLYGON = its vertices, closed. Shapes rotate by `rotationDeg` around the center of their points' bounds.
 */
object ShapeGeometry {
    /** Constraint step for line angles in degrees. */
    const val ANGLE_STEP_DEG = 15f

    /** Drags shorter than this (pt) make no shape. */
    const val MIN_DRAG_PT = 2f

    /** Fewest vertices of a polygon. */
    const val MIN_POLYGON_VERTICES = 3

    private const val ARROW_HEAD_ANGLE_DEG = 28.0
    private const val ARROW_HEAD_WIDTHS = 4f
    private const val ARROW_HEAD_MIN_PT = 8f
    private const val ARROW_HEAD_MAX_LENGTH_FRACTION = 0.5f
    private const val ELLIPSE_SEGMENTS = 48

    /**
     * Control points for a drag from [start] to [end] creating [kind], or null when the drag is too short.
     * [constrain] snaps line angles to [ANGLE_STEP_DEG] and makes boxes square. POLYGON is not dragged.
     */
    fun dragPoints(
        kind: ShapeKind,
        start: PointPt,
        end: PointPt,
        constrain: Boolean,
    ): List<PointPt>? {
        if (hypot(end.x - start.x, end.y - start.y) < MIN_DRAG_PT) return null
        return when (kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                listOf(start, if (constrain) snapAngle(start, end) else end)
            }

            ShapeKind.RECTANGLE, ShapeKind.ELLIPSE -> {
                listOf(start, if (constrain) square(start, end) else end)
            }

            ShapeKind.TRIANGLE -> {
                val e = if (constrain) square(start, end) else end
                val box = RectPt(minOf(start.x, e.x), minOf(start.y, e.y), max(start.x, e.x), max(start.y, e.y))
                listOf(PointPt(box.center.x, box.top), PointPt(box.right, box.bottom), PointPt(box.left, box.bottom))
            }

            ShapeKind.POLYGON -> {
                null
            }
        }
    }

    /** A new unrotated shape from control [points]. */
    fun create(
        id: ObjectId,
        kind: ShapeKind,
        points: List<PointPt>,
        stroke: StrokeStyle,
        fillArgb: Int?,
    ): Shape = Shape(id, kind, points, 0f, stroke, if (kind.fillable) fillArgb else null, bounds(kind, points, 0f, stroke.widthPt))

    /** Center the shape rotates around: the center of its points' bounds. */
    fun pivot(points: List<PointPt>): PointPt = RectPt.bounds(points).center

    /** Page-space bounds of a shape including the outline width and, for arrows, the head. */
    fun bounds(
        kind: ShapeKind,
        points: List<PointPt>,
        rotationDeg: Float,
        widthPt: Float,
    ): RectPt {
        val local = RectPt.bounds(points)
        if (points.isEmpty()) return local
        var r = local
        if (kind == ShapeKind.ARROW && points.size >= 2) {
            val (left, right) = arrowHead(points[0], points[1], widthPt)
            r = r.union(RectPt.bounds(listOf(left, right)))
        }
        val c = local.center
        val rotated = if (rotationDeg == 0f) r else Affine.rotate(rotationDeg, c.x, c.y).mapRect(r)
        return rotated.inset(-widthPt / 2f)
    }

    /**
     * The two barb ends of an arrow head at [end] pointing away from [start]. Length 4 outline widths
     * (at least 8 pt), at most half the shaft.
     */
    fun arrowHead(
        start: PointPt,
        end: PointPt,
        widthPt: Float,
    ): Pair<PointPt, PointPt> {
        val shaft = hypot(end.x - start.x, end.y - start.y)
        val len = max(ARROW_HEAD_MIN_PT, widthPt * ARROW_HEAD_WIDTHS).coerceAtMost(shaft * ARROW_HEAD_MAX_LENGTH_FRACTION)
        val back = atan2((start.y - end.y).toDouble(), (start.x - end.x).toDouble())
        val spread = Math.toRadians(ARROW_HEAD_ANGLE_DEG)
        return PointPt(end.x + len * cos(back + spread).toFloat(), end.y + len * sin(back + spread).toFloat()) to
            PointPt(end.x + len * cos(back - spread).toFloat(), end.y + len * sin(back - spread).toFloat())
    }

    /**
     * The outline in local (unrotated) coordinates as a polyline: closed kinds repeat their first point
     * at the end; ellipses are approximated by [ELLIPSE_SEGMENTS] segments. Arrow heads are not included.
     */
    fun localOutline(shape: Shape): List<PointPt> {
        val p = shape.points
        if (p.isEmpty()) return emptyList()
        return when (shape.kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                p
            }

            ShapeKind.RECTANGLE -> {
                val b = RectPt.bounds(p)
                listOf(
                    PointPt(b.left, b.top),
                    PointPt(b.right, b.top),
                    PointPt(b.right, b.bottom),
                    PointPt(b.left, b.bottom),
                    PointPt(b.left, b.top),
                )
            }

            ShapeKind.ELLIPSE -> {
                val b = RectPt.bounds(p)
                val c = b.center
                List(ELLIPSE_SEGMENTS + 1) { i ->
                    val a = 2.0 * PI * i / ELLIPSE_SEGMENTS
                    PointPt(c.x + b.widthPt / 2f * cos(a).toFloat(), c.y + b.heightPt / 2f * sin(a).toFloat())
                }
            }

            ShapeKind.TRIANGLE, ShapeKind.POLYGON -> {
                p + p[0]
            }
        }
    }

    /**
     * True when [point] (page pt) hits [shape]: within [tolerancePt] plus half the outline width of the
     * outline (or an arrow barb), or inside a filled shape.
     */
    fun hits(
        shape: Shape,
        point: PointPt,
        tolerancePt: Float,
    ): Boolean {
        if (!shape.bounds.inset(-tolerancePt).contains(point)) return false
        val local = toLocal(shape, point)
        val outline = localOutline(shape)
        val reach = tolerancePt + shape.stroke.widthPt / 2f
        return (shape.fillArgb != null && polygonContains(outline, local)) ||
            nearPolyline(outline, local, reach) ||
            nearArrowHead(shape, local, reach)
    }

    /** [point] (page pt) in the shape's unrotated frame. */
    fun toLocal(
        shape: Shape,
        point: PointPt,
    ): PointPt {
        if (shape.rotationDeg == 0f) return point
        val c = pivot(shape.points)
        return Affine.rotate(-shape.rotationDeg, c.x, c.y).mapPoint(point)
    }

    /** Local point [local] of [shape] in page space. */
    fun toPage(
        shape: Shape,
        local: PointPt,
    ): PointPt {
        if (shape.rotationDeg == 0f) return local
        val c = pivot(shape.points)
        return Affine.rotate(shape.rotationDeg, c.x, c.y).mapPoint(local)
    }

    /** [shape] with new local [points] and [rotationDeg], bounds recomputed. */
    fun withPoints(
        shape: Shape,
        points: List<PointPt>,
        rotationDeg: Float = shape.rotationDeg,
    ): Shape =
        shape.copy(points = points, rotationDeg = rotationDeg, bounds = bounds(shape.kind, points, rotationDeg, shape.stroke.widthPt))

    /** [shape] with a new outline style, bounds recomputed. */
    fun restyled(
        shape: Shape,
        stroke: StrokeStyle,
        fillArgb: Int?,
    ): Shape =
        shape.copy(
            stroke = stroke,
            fillArgb = if (shape.kind.fillable) fillArgb else null,
            bounds = bounds(shape.kind, shape.points, shape.rotationDeg, stroke.widthPt),
        )
}

private fun nearPolyline(
    outline: List<PointPt>,
    p: PointPt,
    reach: Float,
): Boolean = (1 until outline.size).any { segmentDistance(p, outline[it - 1], outline[it]) <= reach }

private fun nearArrowHead(
    shape: Shape,
    p: PointPt,
    reach: Float,
): Boolean {
    if (shape.kind != ShapeKind.ARROW || shape.points.size < 2) return false
    val tip = shape.points[1]
    val (left, right) = ShapeGeometry.arrowHead(shape.points[0], tip, shape.stroke.widthPt)
    return segmentDistance(p, tip, left) <= reach || segmentDistance(p, tip, right) <= reach
}

private fun snapAngle(
    start: PointPt,
    end: PointPt,
): PointPt {
    val len = hypot(end.x - start.x, end.y - start.y)
    val deg = Math.toDegrees(atan2((end.y - start.y).toDouble(), (end.x - start.x).toDouble()))
    val snapped = Math.toRadians((deg / ShapeGeometry.ANGLE_STEP_DEG).roundToInt() * ShapeGeometry.ANGLE_STEP_DEG.toDouble())
    return PointPt(start.x + len * cos(snapped).toFloat(), start.y + len * sin(snapped).toFloat())
}

private fun square(
    start: PointPt,
    end: PointPt,
): PointPt {
    val side = max(abs(end.x - start.x), abs(end.y - start.y))
    return PointPt(start.x + if (end.x < start.x) -side else side, start.y + if (end.y < start.y) -side else side)
}
