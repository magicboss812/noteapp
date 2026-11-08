package dev.folio.core.model.lasso

import dev.folio.core.model.Attachment
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageObject
import dev.folio.core.model.Shape
import dev.folio.core.model.StickyNote
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.geometry.polygonContains
import kotlin.math.ceil

/** Object kinds a lasso selects; the options row filters by them (06-ink-input.md#lasso). */
enum class LassoKind { INK, SHAPES, TEXT, IMAGES }

/** The [LassoKind] of this object; null for objects no lasso selects (the page body text frames). */
val PageObject.lassoKind: LassoKind?
    get() =
        when (this) {
            is InkStroke -> LassoKind.INK
            is Shape -> LassoKind.SHAPES
            is FlowFrame -> if (role == FrameRole.BOX) LassoKind.TEXT else null
            is StickyNote -> LassoKind.TEXT
            is ImageObject, is Attachment -> LassoKind.IMAGES
        }

/**
 * Lasso membership (06-ink-input.md#lasso): a stroke is selected when at least [STROKE_SHARE] of its input points lie
 * inside the lasso polygon, any other object when its bounds center does. Pure; runs once per lasso, at the pen up.
 */
object Lasso {
    /** Share of a stroke's input points that must lie inside the lasso. */
    const val STROKE_SHARE = 0.6f

    private const val MIN_POLYGON_POINTS = 3

    /**
     * Ids of [objects] (in their order) inside the closed polygon [xs], [ys] (first [n] vertices) whose kind is in
     * [kinds]. Fewer than three vertices select nothing.
     */
    fun select(
        objects: List<PageObject>,
        xs: FloatArray,
        ys: FloatArray,
        n: Int,
        kinds: Set<LassoKind>,
    ): List<ObjectId> {
        if (n < MIN_POLYGON_POINTS) return emptyList()
        val hull = RectPt.bounds(xs.copyOf(n), ys.copyOf(n))
        val out = ArrayList<ObjectId>()
        for (o in objects) {
            val wanted = o.lassoKind?.let { it in kinds } == true
            if (wanted && o.bounds.intersects(hull) && contains(o, xs, ys, n)) out += o.id
        }
        return out
    }

    /** [select] for a polygon given as points. */
    fun select(
        objects: List<PageObject>,
        polygon: List<PointPt>,
        kinds: Set<LassoKind>,
    ): List<ObjectId> {
        val xs = FloatArray(polygon.size) { polygon[it].x }
        val ys = FloatArray(polygon.size) { polygon[it].y }
        return select(objects, xs, ys, polygon.size, kinds)
    }

    /** True when the lasso polygon holds [obj] by the membership rule. */
    fun contains(
        obj: PageObject,
        xs: FloatArray,
        ys: FloatArray,
        n: Int,
    ): Boolean {
        if (obj !is InkStroke) {
            val c = obj.bounds.center
            return polygonContains(xs, ys, n, c.x, c.y)
        }
        return strokeInside(obj, xs, ys, n)
    }

    private fun strokeInside(
        stroke: InkStroke,
        xs: FloatArray,
        ys: FloatArray,
        n: Int,
    ): Boolean {
        val inputs = stroke.inputs
        val needed = ceil(inputs.size * STROKE_SHARE).toInt()
        var inside = 0
        var i = 0
        // Stops once the share is reached or the points left cannot reach it.
        while (i < inputs.size && inside < needed && inside + (inputs.size - i) >= needed) {
            if (polygonContains(xs, ys, n, inputs.x[i], inputs.y[i])) inside++
            i++
        }
        return inputs.size > 0 && inside >= needed
    }

    /** The rectangle lasso from corner [a] to corner [b] as a polygon (four corners, clockwise on screen). */
    fun rectangle(
        a: PointPt,
        b: PointPt,
    ): List<PointPt> {
        val r = RectPt(minOf(a.x, b.x), minOf(a.y, b.y), maxOf(a.x, b.x), maxOf(a.y, b.y))
        return listOf(PointPt(r.left, r.top), PointPt(r.right, r.top), PointPt(r.right, r.bottom), PointPt(r.left, r.bottom))
    }
}
