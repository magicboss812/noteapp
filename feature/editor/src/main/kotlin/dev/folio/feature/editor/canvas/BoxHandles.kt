package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.render.viewport.toPagePt

/**
 * The frame of a focused free text box (P06-T17, 10-editor-ui.md#text-tool), a plain view above the selection: an
 * outline, a grip on top and a width handle on each side (shots/G4-text-editing.jpg). A finger or the pen that goes
 * down on the grip moves the box, on a handle resizes it; the outline follows snapped to the grid and the box is
 * placed when it is let go ([handleTouch]). Everything else passes on.
 */
@MainThread
internal class BoxHandles(
    context: Context,
    private val viewport: Viewport,
    private val controller: CanvasController,
    private val document: () -> Document?,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_ARGB
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * density
        }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KNOB_FILL_ARGB }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_ARGB }
    private val pill = RectF()

    private var flowId: FlowId? = null
    private var seen: Document? = null

    // The focused box as the document has it, null for none.
    private var frameId: ObjectId? = null
    private var pageId: PageId? = null
    private var rect: RectPt? = null

    // Where a drag puts it (snapped), shown until the document has the box there; null while nothing moved.
    private var previewPage: PageId? = null
    private var previewRect: RectPt? = null

    // The part held (NONE when none), and where the pointer took hold: px from the box's top-left (grip) or edge.
    private var dragging = NONE
    private var owned = false
    private var grabXPx = 0f
    private var grabYPx = 0f

    /** The box shown (page, rect in page pt), null for none (tests). */
    val shown: Pair<PageId, RectPt>? get() = pageId?.let { page -> rect?.let { page to it } }

    /** True while a grip or handle drag runs. */
    val isDragging: Boolean get() = dragging != NONE

    /** Shows the handles of flow [focused] when it is a free text box (null hides them). */
    fun show(focused: FlowId?) {
        if (focused == flowId) return
        flowId = focused
        if (focused == null) dragging = NONE
        seen = null
        refresh()
    }

    /** Finds the box again in the current document (it moved, resized or reflowed). */
    fun refresh() {
        val doc = document()
        if (doc === seen) return
        seen = doc
        previewPage = null
        previewRect = null
        val wanted = flowId
        var found: FlowFrame? = null
        var on: PageId? = null
        if (wanted != null && doc != null) {
            for ((id, page) in doc.pageBodies) {
                found = page.objects.firstOrNull { it is FlowFrame && it.flow == wanted && it.role == FrameRole.BOX } as FlowFrame?
                if (found != null) {
                    on = id
                    break
                }
            }
        }
        frameId = found?.id
        pageId = on
        rect = found?.rect
        if (found == null) dragging = NONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per viewport change while a box is focused; no allocation.
        val page = previewPage ?: pageId ?: return
        val r = previewRect ?: rect ?: return
        val index = indexOf(page)
        if (index < 0) return
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        val left = x + r.left * scale
        val top = y + r.top * scale
        val right = x + r.right * scale
        val bottom = y + r.bottom * scale
        canvas.drawRect(left, top, right, bottom, outlinePaint)
        val midY = (top + bottom) / 2
        val knob = KNOB_DP * density
        canvas.drawCircle(left, midY, knob, fillPaint)
        canvas.drawCircle(left, midY, knob, outlinePaint)
        canvas.drawCircle(right, midY, knob, fillPaint)
        canvas.drawCircle(right, midY, knob, outlinePaint)
        val cx = (left + right) / 2
        val halfW = GRIP_WIDTH_DP * density / 2
        val halfH = GRIP_HEIGHT_DP * density / 2
        pill.set(cx - halfW, top - halfH, cx + halfW, top + halfH)
        canvas.drawRoundRect(pill, halfH, halfH, fillPaint)
        canvas.drawRoundRect(pill, halfH, halfH, outlinePaint)
        val dot = DOT_DP * density
        val gap = DOT_GAP_DP * density
        canvas.drawCircle(cx - gap, top, dot, dotPaint)
        canvas.drawCircle(cx, top, dot, dotPaint)
        canvas.drawCircle(cx + gap, top, dot, dotPaint)
    }

    /** True if ([xPx], [yPx]) is on the grip or a handle: the pen hovering there keeps the box focused (A-045). */
    fun covers(
        xPx: Float,
        yPx: Float,
    ): Boolean = partAt(xPx, yPx) != NONE

    /**
     * A finger or pen event: a down on the grip or a handle starts a drag, the moves show where the box goes, the up
     * places it. True if the event belongs to such a drag.
     */
    fun handleTouch(event: MotionEvent): Boolean {
        // HOT PATH: per MotionEvent; a bounds check unless a drag runs (a drag move builds its snapped preview rect).
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            // Read mode only navigates (10-editor-ui.md#read-mode): the grip and handles pan like the page around them.
            val inert = event.getToolType(0) == MotionEvent.TOOL_TYPE_ERASER || controller.activeTool == CanvasTool.NAVIGATE
            dragging = if (inert) NONE else grab(event.x, event.y)
            owned = dragging != NONE
        }
        if (!owned) return false
        when (action) {
            MotionEvent.ACTION_MOVE -> if (dragging != NONE) drag(event.x, event.y)

            MotionEvent.ACTION_UP -> if (dragging != NONE) drop()

            // A second finger or a cancel: the box stays where it was; the rest of the gesture is still ours.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = NONE
            owned = false
        }
        return true
    }

    private fun cancelDrag() {
        dragging = NONE
        previewPage = null
        previewRect = null
        invalidate()
    }

    // The part under ([xPx], [yPx]): the grip (a 44 dp target on the top edge's middle), a width handle (44 dp targets
    // on the side edges' middles), or NONE.
    private fun partAt(
        xPx: Float,
        yPx: Float,
    ): Int {
        val page = pageId ?: return NONE
        val r = rect ?: return NONE
        val index = indexOf(page)
        if (index < 0) return NONE
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        val reach = TARGET_DP * density / 2
        val top = y + r.top * scale
        val midY = y + (r.top + r.bottom) / 2 * scale
        val left = x + r.left * scale
        val right = x + r.right * scale
        return when {
            near(xPx, yPx, (left + right) / 2, top, reach) -> GRIP
            near(xPx, yPx, right, midY, reach) -> RIGHT
            near(xPx, yPx, left, midY, reach) -> LEFT
            else -> NONE
        }
    }

    private fun near(
        xPx: Float,
        yPx: Float,
        cx: Float,
        cy: Float,
        reach: Float,
    ): Boolean = xPx in cx - reach..cx + reach && yPx in cy - reach..cy + reach

    private fun grab(
        xPx: Float,
        yPx: Float,
    ): Int {
        val part = partAt(xPx, yPx)
        val page = pageId
        val r = rect
        if (part == NONE || page == null || r == null) return NONE
        val index = indexOf(page)
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        grabXPx =
            when (part) {
                LEFT -> xPx - (x + r.left * scale)
                RIGHT -> xPx - (x + r.right * scale)
                else -> xPx - (x + r.left * scale)
            }
        grabYPx = yPx - (y + r.top * scale)
        return part
    }

    // The outline snapped where the pointer is now: the grip carries the box to the page under its top edge, a
    // handle moves its edge on the box's own page.
    private fun drag(
        xPx: Float,
        yPx: Float,
    ) {
        val doc = document() ?: return
        val from = pageId ?: return
        val r = rect ?: return
        val edgeX = xPx - grabXPx
        val topY = yPx - grabYPx
        val target = if (dragging == GRIP) doc.pages.getOrNull(viewport.pageIndexAt(topY))?.id ?: return else from
        val ref = doc.pageRef(target) ?: return
        val pt = viewport.toPagePt(target, PointPx(edgeX, topY))
        val template = ref.background.template
        previewRect =
            when (dragging) {
                GRIP -> TextZones.movedBox(ref.spec, template, r, pt.x, pt.y)
                LEFT -> TextZones.resizedBox(ref.spec, template, r, pt.x, r.right)
                else -> TextZones.resizedBox(ref.spec, template, r, r.left, pt.x)
            }
        previewPage = target
        invalidate()
    }

    private fun drop() {
        val id = frameId ?: return
        val from = pageId ?: return
        val to = previewPage ?: return
        val r = previewRect ?: return
        if (to == from && r == rect) return
        controller.textController?.placeBox(id, from, to, r)
    }

    private fun indexOf(page: PageId): Int = document()?.pages?.indexOfFirst { it.id == page } ?: -1

    companion object {
        private const val NONE = 0
        private const val GRIP = 1
        private const val LEFT = 2
        private const val RIGHT = 3

        private const val OUTLINE_DP = 1f
        private const val KNOB_DP = 5f
        private const val GRIP_WIDTH_DP = 20f
        private const val GRIP_HEIGHT_DP = 8f
        private const val DOT_DP = 1f
        private const val DOT_GAP_DP = 4f
        private const val TARGET_DP = 44f

        // The accent and a white knob fill (named, no theme inside the canvas view).
        private const val ACCENT_ARGB = 0xFF4B85E0.toInt()
        private const val KNOB_FILL_ARGB = 0xFFFDFDFE.toInt()
    }
}
