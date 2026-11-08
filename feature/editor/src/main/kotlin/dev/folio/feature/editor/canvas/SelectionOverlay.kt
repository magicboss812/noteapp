package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.render.viewport.toPagePt
import dev.folio.core.text.flow.CaretSpot
import dev.folio.feature.editor.text.SelectionHighlight

/**
 * The page's part of a text selection (07-text-engine.md#selection-as-built), a plain view above the overlay slot:
 * the boxes of the selected lines outside the live field and a drag handle under each end. A finger that goes down
 * on a handle moves that end ([handleTouch]); everything else passes on to the input router.
 */
@MainThread
internal class SelectionOverlay(
    context: Context,
    private val viewport: Viewport,
    private val controller: CanvasController,
    private val document: () -> Document?,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val boxPaint = Paint().apply { color = BOX_ARGB }
    private val handlePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = HANDLE_ARGB
            strokeWidth = STEM_DP * density
        }
    private var selection: SelectionHighlight? = null

    // The end a finger drags (NONE when none), and where on the handle it took hold, px from the end's caret line.
    private var dragging = NONE
    private var grabXPx = 0f
    private var grabYPx = 0f

    /** The selection shown, null for none (tests). */
    val shown: SelectionHighlight? get() = selection

    /** Shows [next] (null hides it). */
    fun show(next: SelectionHighlight?) {
        if (next === selection) return
        selection = next
        if (next == null) dragging = NONE
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per viewport change while a selection shows; no allocation.
        val current = selection ?: return
        val scale = viewport.scale
        val boxes = current.boxes
        for (i in boxes.indices) {
            val box = boxes[i]
            val index = indexOf(box.pageId)
            if (index >= 0) {
                val x = viewport.pageOriginViewX(index).toFloat()
                val y = viewport.pageOriginViewY(index).toFloat()
                val r = box.rect
                canvas.drawRect(x + r.left * scale, y + r.top * scale, x + r.right * scale, y + r.bottom * scale, boxPaint)
            }
        }
        current.anchor?.let { drawHandle(canvas, it) }
        current.focus?.let { drawHandle(canvas, it) }
    }

    // A stem on the caret line and a round knob under it.
    private fun drawHandle(
        canvas: Canvas,
        spot: CaretSpot,
    ) {
        val index = indexOf(spot.pageId)
        if (index < 0) return
        val x = viewport.pageOriginViewX(index).toFloat() + spot.xPt * viewport.scale
        val top = viewport.pageOriginViewY(index).toFloat() + spot.topPt * viewport.scale
        val bottom = viewport.pageOriginViewY(index).toFloat() + spot.bottomPt * viewport.scale
        val radius = KNOB_DP * density
        canvas.drawLine(x, top, x, bottom, handlePaint)
        canvas.drawCircle(x, bottom + radius, radius, handlePaint)
    }

    /**
     * A finger event: a down on a handle starts dragging that end, the moves until the finger lifts carry it to the
     * text under the finger (where it took hold of the knob). True if the event belongs to a handle drag.
     */
    fun handleTouch(event: MotionEvent): Boolean {
        val was = dragging
        val action = event.actionMasked
        when (action) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER) handleAt(event.x, event.y) else NONE
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging != NONE) drag(event.x - grabXPx, event.y - grabYPx)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = NONE
            }
        }
        val ended = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
        return dragging != NONE || (ended && was != NONE)
    }

    // The handle whose knob (a 44 dp target) is under ([xPx], [yPx]); remembers where on it the finger took hold.
    private fun handleAt(
        xPx: Float,
        yPx: Float,
    ): Int {
        val current = selection ?: return NONE
        return when {
            grabs(current.focus, xPx, yPx) -> FOCUS
            grabs(current.anchor, xPx, yPx) -> ANCHOR
            else -> NONE
        }
    }

    private fun grabs(
        spot: CaretSpot?,
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val index = if (spot == null) -1 else indexOf(spot.pageId)
        if (spot == null || index < 0) return false
        val reach = TARGET_DP * density / 2
        val x = viewport.pageOriginViewX(index).toFloat() + spot.xPt * viewport.scale
        val pageY = viewport.pageOriginViewY(index).toFloat()
        val knobY = pageY + spot.bottomPt * viewport.scale + KNOB_DP * density
        if (xPx !in x - reach..x + reach || yPx !in knobY - reach..knobY + reach) return false
        grabXPx = xPx - x
        grabYPx = yPx - (pageY + (spot.topPt + spot.bottomPt) / 2 * viewport.scale)
        return true
    }

    private fun drag(
        xPx: Float,
        yPx: Float,
    ) {
        val texts = controller.textController ?: return
        val pageId = document()?.pages?.getOrNull(viewport.pageIndexAt(yPx))?.id ?: return
        val pt = viewport.toPagePt(pageId, PointPx(xPx, yPx))
        texts.dragSelection(anchor = dragging == ANCHOR, pageId, pt.x, pt.y)
    }

    private fun indexOf(pageId: PageId): Int = document()?.pages?.indexOfFirst { it.id == pageId } ?: -1

    companion object {
        /** Selection box color: the accent at 25% (named, no theme inside the canvas view). */
        const val BOX_ARGB = 0x404B85E0

        private const val NONE = 0
        private const val ANCHOR = 1
        private const val FOCUS = 2

        private const val KNOB_DP = 8f
        private const val STEM_DP = 2f
        private const val TARGET_DP = 44f

        // The handles in the accent.
        private const val HANDLE_ARGB = 0xFF4B85E0.toInt()
    }
}
