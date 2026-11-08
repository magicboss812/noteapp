package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusTarget
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.model.shape.ShapeHandle
import dev.folio.core.model.shape.ShapeHandles
import dev.folio.core.model.shape.dragShape
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import kotlin.math.hypot

/** Screen sizes of the Shape tool in px: polygon close radius, handle grab radius, rotate handle offset; double-tap time. */
internal data class ShapeInputSizes(
    val closeRadiusPx: Float,
    val handleRadiusPx: Float,
    val rotateOffsetPx: Float,
    val doubleTapMs: Long,
)

/** Object [id] on page [pageId] the tiles leave out while the preview draws it; [keep]: a version the tiles show again. */
internal data class HiddenShape(
    val pageId: PageId,
    val id: ObjectId,
    val keep: Shape?,
) {
    /** True when [page] holds a version of the shape the tiles leave out. */
    fun hidesIn(page: Page): Boolean = page.id == pageId && page.objects.any { it.id == id && it != keep }
}

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
 * called (its tiles are drawn) or [reset]. The pen on a shape selects it; on the selected shape's handle or
 * body a drag resizes, turns, moves a point or moves it (one `UpdateObjects`), the tiles leaving it out
 * meanwhile ([onHide]); the pen beside every shape drops the selection and draws.
 */
@MainThread
@Suppress("TooManyFunctions") // the StylusTarget API plus draw, polygon and selection-edit gestures sharing one pen state
internal class ShapeInput(
    private val viewport: Viewport,
    private val pageIdAt: (Int) -> PageId?,
    private val objectsOn: (PageId) -> List<PageObject>?,
    private val spec: () -> ShapeSpec,
    private val sizes: ShapeInputSizes,
    private val invalidate: () -> Unit,
    private val onHide: (HiddenShape?) -> Unit,
    private val onCommit: (PageId, Shape, Boolean) -> Unit,
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

    // Selection (06-ink-input.md#shapes): the selected shape's page and id; a drag on it edits a copy of [original].
    private var selectedPage: PageId? = null
    private var selectedId: ObjectId? = null
    private var original: Shape? = null
    private var grabbed: ShapeHandle? = null

    /** The committed shape while its tiles are not drawn yet. */
    var pending: PendingShape? = null
        private set

    /** Vertices of the polygon being tapped out, on [draftPage]. */
    val polygonVertices: List<PointPt> get() = polygon.vertices

    /** Page (stack index) the [draft] is on; -1 without a draft. */
    val draftPage: Int get() = if (dragging || !polygon.isEmpty) pageIndex else -1

    /** Page (stack index) of the selected shape; -1 without a selection. */
    var selectionPage = -1
        private set

    /** True while a drag moves, resizes or turns the selected shape. */
    val editing: Boolean get() = original != null

    /** The selected shape as it looks now (mid-drag: edited), or null; a shape gone from its page drops the selection. */
    fun selected(): Shape? {
        val id = selectedId ?: return null
        val page = selectedPage ?: return null
        if (editing) return edited()
        pending?.shape?.takeIf { it.id == id }?.let { return it }
        val shape = objectsOn(page)?.firstOrNull { it.id == id } as? Shape
        if (shape == null) clearSelection()
        return shape
    }

    /** Handles of [shape] at the current zoom. */
    fun handlesOf(shape: Shape): List<ShapeHandle> = ShapeHandles.handles(shape, sizes.rotateOffsetPx / viewport.scale)

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
        val pageId = pageIdAt(page) ?: return
        if (current.kind == ShapeKind.POLYGON && !polygon.isEmpty && page != polygon.pageIndex) polygon.clear()
        pageIndex = page
        startX = toPageX(event.getX(index))
        startY = toPageY(event.getY(index))
        endX = startX
        endY = startY
        buttonHeld = event.isButtonPressed(MotionEvent.BUTTON_STYLUS_PRIMARY)
        if (polygon.isEmpty && grab(pageId, page)) return
        clearSelection()
        dragging = true
        invalidate()
    }

    // The pen on the selected shape's handle or body edits it; on another shape it selects that one and moves it.
    private fun grab(
        pageId: PageId,
        page: Int,
    ): Boolean {
        val at = PointPt(startX, startY)
        val radiusPt = sizes.handleRadiusPx / viewport.scale
        val selected = if (selectedPage == pageId) selected() else null
        val handle = selected?.let { ShapeHandles.handleAt(handlesOf(it), at, radiusPt) }
        val target =
            when {
                selected != null && (handle != null || ShapeGeometry.hits(selected, at, radiusPt)) -> selected
                else -> objectsOn(pageId)?.lastOrNull { it is Shape && ShapeGeometry.hits(it, at, radiusPt) } as? Shape
            } ?: return false
        selectedPage = pageId
        selectedId = target.id
        selectionPage = page
        original = target
        grabbed = if (target === selected) handle else null
        onHide(HiddenShape(pageId, target.id, keep = null))
        invalidate()
        return true
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent; stores the position, the preview builds the shape per frame.
        if (!dragging && !editing) return
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
        if (editing) {
            onStylusMove(event, pointerId)
            finishEdit()
            return
        }
        if (!dragging) return
        onStylusMove(event, pointerId)
        dragging = false
        val pageId = pageIdAt(pageIndex)
        if (pageId == null) {
            reset()
            return
        }
        if (current.kind == ShapeKind.POLYGON) {
            val closeRadiusPt = sizes.closeRadiusPx / viewport.scale
            val vertices = polygon.tap(pageIndex, PointPt(endX, endY), event.eventTime, closeRadiusPt, sizes.doubleTapMs)
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
        if (editing) {
            original = null
            onHide(null)
        }
        invalidate()
    }

    private fun finishEdit() {
        val before = original ?: return
        val after = edited()
        val page = selectedPage
        original = null
        if (page == null || after == before) {
            onHide(null)
        } else {
            pending = PendingShape(page, selectionPage, after)
            onHide(HiddenShape(page, after.id, keep = after))
            onCommit(page, after, false)
        }
        invalidate()
    }

    private fun edited(): Shape {
        val before = original ?: error("no shape grabbed")
        return dragShape(before, grabbed, PointPt(startX, startY), PointPt(endX, endY), keepAspect = constrained())
    }

    private fun clearSelection() {
        if (selectedId == null) return
        selectedId = null
        selectedPage = null
        selectionPage = -1
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
        original = null
        polygon.clear()
        clearSelection()
        onHide(null)
        invalidate()
    }

    /** Drops the draft, the polygon vertices and the selection (another tool took the pen); a pending shape stays. */
    fun cancelDraft() {
        if (draftPage < 0 && selectedId == null) return
        dragging = false
        polygon.clear()
        clearSelection()
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
        onCommit(pageId, shape, true)
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
