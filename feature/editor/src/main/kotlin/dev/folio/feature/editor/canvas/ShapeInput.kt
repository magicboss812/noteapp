package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusTarget
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import kotlin.math.hypot

/** A committed [shape] on page [pageId] (stack [pageIndex]) that the preview draws until its tiles are drawn. */
internal data class PendingShape(
    val pageId: PageId,
    val pageIndex: Int,
    val shape: Shape,
)

/**
 * Vertices of a polygon being tapped out (06-ink-input.md#shapes): every tap adds one; a tap on the first
 * vertex or a second tap on the last one within the double-tap time closes it once it has
 * [ShapeGeometry.MIN_POLYGON_VERTICES]. A tap on another page starts over there.
 */
internal class PolygonDraft {
    private val points = ArrayList<PointPt>()
    private var lastTapMs = Long.MIN_VALUE

    /** Page (stack index) of the vertices; -1 while empty. */
    var pageIndex = -1
        private set

    /** The vertices so far. */
    val vertices: List<PointPt> get() = points

    /** True while no vertex is placed. */
    val isEmpty: Boolean get() = points.isEmpty()

    /**
     * A tap at [p] on page [page] at [timeMs]: adds a vertex, or closes the polygon and returns its
     * vertices (the draft is empty again). [closeRadiusPt]: how near the first or last vertex closes.
     */
    fun tap(
        page: Int,
        p: PointPt,
        timeMs: Long,
        closeRadiusPt: Float,
        doubleTapMs: Long,
    ): List<PointPt>? {
        if (page != pageIndex) clear()
        pageIndex = page
        val closes =
            points.size >= ShapeGeometry.MIN_POLYGON_VERTICES &&
                (near(points.first(), p, closeRadiusPt) || (near(points.last(), p, closeRadiusPt) && timeMs - lastTapMs <= doubleTapMs))
        if (closes) return points.toList().also { clear() }
        // A double tap before the polygon can close only adds its first tap.
        if (points.isEmpty() || !near(points.last(), p, closeRadiusPt) || timeMs - lastTapMs > doubleTapMs) points += p
        lastTapMs = timeMs
        return null
    }

    /** Drops every vertex. */
    fun clear() {
        points.clear()
        pageIndex = -1
        lastTapMs = Long.MIN_VALUE
    }

    private fun near(
        a: PointPt,
        b: PointPt,
        radiusPt: Float,
    ): Boolean = hypot(a.x - b.x, a.y - b.y) <= radiusPt
}

/**
 * The Shape tool on the stylus route (06-ink-input.md#shapes): a drag from down to up draws [spec]'s kind on
 * the page under the pen (constrained while the spec says so or the stylus button is held); polygons collect
 * taps in a [PolygonDraft]. Moves only store the pen position and invalidate the preview, which asks
 * [draft] once per frame. A finished shape goes to [onCommit] and stays the [draft] until [committed] is
 * called (its tiles are drawn) or [reset].
 */
@MainThread
internal class ShapeInput(
    private val viewport: Viewport,
    private val pageIdAt: (Int) -> PageId?,
    private val spec: () -> ShapeSpec,
    private val closeRadiusPx: Float,
    private val doubleTapMs: Long,
    private val invalidate: () -> Unit,
    private val onCommit: (PageId, Shape) -> Unit,
) : StylusTarget {
    private val polygon = PolygonDraft()
    private var current = ShapeSpec.DEFAULT
    private var dragging = false
    private var pageIndex = -1
    private var startX = 0f
    private var startY = 0f
    private var endX = 0f
    private var endY = 0f
    private var buttonHeld = false

    /** The committed shape while its tiles are not drawn yet. */
    var pending: PendingShape? = null
        private set

    /** Vertices of the polygon being tapped out, on [draftPage]. */
    val polygonVertices: List<PointPt> get() = polygon.vertices

    /** Page (stack index) the [draft] is on; -1 without a draft. */
    val draftPage: Int get() = if (dragging || !polygon.isEmpty) pageIndex else -1

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        val index = event.findPointerIndex(pointerId)
        if (eraser || index < 0) return
        current = spec()
        if (current.kind != ShapeKind.POLYGON) polygon.clear()
        val page = viewport.pageIndexAt(event.getY(index))
        if (pageIdAt(page) == null) return
        if (current.kind == ShapeKind.POLYGON && !polygon.isEmpty && page != polygon.pageIndex) polygon.clear()
        pageIndex = page
        startX = toPageX(event.getX(index))
        startY = toPageY(event.getY(index))
        endX = startX
        endY = startY
        buttonHeld = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        dragging = true
        invalidate()
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent; stores the position, the preview builds the shape per frame.
        if (!dragging) return
        val index = event.findPointerIndex(pointerId)
        if (index < 0) return
        endX = toPageX(event.getX(index))
        endY = toPageY(event.getY(index))
        buttonHeld = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        invalidate()
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        if (!dragging) return
        onStylusMove(event, pointerId)
        dragging = false
        val pageId = pageIdAt(pageIndex)
        if (pageId == null) {
            reset()
            return
        }
        if (current.kind == ShapeKind.POLYGON) {
            val closeRadiusPt = closeRadiusPx / viewport.scale
            val vertices = polygon.tap(pageIndex, PointPt(endX, endY), event.eventTime, closeRadiusPt, doubleTapMs)
            if (vertices != null) commit(pageId, vertices)
        } else {
            ShapeGeometry.dragPoints(current.kind, PointPt(startX, startY), PointPt(endX, endY), constrained())?.let { commit(pageId, it) }
        }
        invalidate()
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        dragging = false
        invalidate()
    }

    /** The shape being drawn in page pt of [draftPage], or null (the preview asks once per frame). */
    fun draft(): Shape? =
        when {
            draftPage < 0 -> null
            current.kind == ShapeKind.POLYGON -> polygonDraft()
            else -> ShapeGeometry.dragPoints(current.kind, PointPt(startX, startY), PointPt(endX, endY), constrained())?.let(::create)
        }

    /** The pending shape's tiles are drawn: the preview stops drawing it. */
    fun committed() {
        pending = null
        invalidate()
    }

    /** Drops the draft, the polygon vertices and any pending shape (tool change, rejected commit). */
    fun reset() {
        pending = null
        dragging = false
        polygon.clear()
        invalidate()
    }

    /** Drops the draft and the polygon vertices (another tool took the pen); a pending shape stays. */
    fun cancelDraft() {
        if (draftPage < 0) return
        dragging = false
        polygon.clear()
        invalidate()
    }

    private fun polygonDraft(): Shape? {
        val pts = ArrayList<PointPt>(polygon.vertices)
        if (dragging) pts += PointPt(endX, endY)
        return when (pts.size) {
            0, 1 -> null
            2 -> ShapeGeometry.create(DRAFT_ID, ShapeKind.LINE, pts, current.stroke, null)
            else -> create(pts)
        }
    }

    private fun commit(
        pageId: PageId,
        points: List<PointPt>,
    ) {
        val shape = ShapeGeometry.create(ObjectId.random(), current.kind, points, current.stroke, current.fillArgb)
        pending = PendingShape(pageId, pageIndex, shape)
        onCommit(pageId, shape)
    }

    private fun create(points: List<PointPt>): Shape =
        ShapeGeometry.create(DRAFT_ID, current.kind, points, current.stroke, current.fillArgb)

    private fun constrained(): Boolean = current.constrain || buttonHeld

    private fun toPageX(xPx: Float): Float = ((xPx - viewport.pageOriginViewX(pageIndex)) / viewport.scale).toFloat()

    private fun toPageY(yPx: Float): Float = ((yPx - viewport.pageOriginViewY(pageIndex)) / viewport.scale).toFloat()

    private companion object {
        val DRAFT_ID = ObjectId("shape-draft")
    }
}
