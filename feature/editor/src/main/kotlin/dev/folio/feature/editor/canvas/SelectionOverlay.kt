package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
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
 * the boxes of the selected lines and a drag handle under each end. A finger that goes down on a handle moves that
 * end ([handleTouch]). After a touch selection a floating menu (Cut, Copy, Paste, Select all) shows above it
 * ([showMenu], [menuTouch]); everything else passes on to the input router.
 */
@MainThread
@Suppress("TooManyFunctions") // boxes, handle drags and the floating menu share one selection and one draw pass
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

    // The floating menu: armed by a touch selection, laid out in view px by [layoutMenu], the button a finger pressed.
    private val menuPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = MENU_ARGB }
    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = LABEL_ARGB
            textSize = LABEL_DP * density
        }
    private val labelWidthsPx = FloatArray(MENU_LABELS.size) { labelPaint.measureText(MENU_LABELS[it]) }
    private val menuRect = RectF()
    private var menuArmed = false
    private var pressed = NONE

    /** The selection shown, null for none (tests). */
    val shown: SelectionHighlight? get() = selection

    /** True while the floating menu shows (tests). */
    val isMenuShown: Boolean get() = layoutMenu()

    /** Runs a menu choice: `android.R.id.cut`, `copy`, `paste` or `selectAll`. */
    var onMenu: (Int) -> Unit = {}

    /** Shows [next] (null hides it, and the menu until the next touch selection). */
    fun show(next: SelectionHighlight?) {
        if (next === selection) return
        selection = next
        if (next == null) {
            dragging = NONE
            menuArmed = false
        }
        invalidate()
    }

    /** A touch selection ended (long-press, double tap, handle released): the menu shows above it. */
    fun showMenu() {
        menuArmed = true
        invalidate()
    }

    /** The menu hides while a selection gesture runs. */
    fun hideMenu() {
        if (!menuArmed) return
        menuArmed = false
        invalidate()
    }

    /** True if ([xPx], [yPx]) is on a handle's knob target. */
    fun isOnHandle(
        xPx: Float,
        yPx: Float,
    ): Boolean = handleAt(xPx, yPx) != NONE

    /** True if ([xPx], [yPx]) is on the menu: the pen taps it instead of writing. */
    fun menuCovers(
        xPx: Float,
        yPx: Float,
    ): Boolean = layoutMenu() && menuRect.contains(xPx, yPx)

    /**
     * A finger or pen event on the menu: a down on a button presses it, its release over the same button runs it.
     * True if the event belongs to a menu press.
     */
    fun menuTouch(event: MotionEvent): Boolean {
        val was = pressed
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> pressed = if (menuCovers(event.x, event.y)) buttonAt(event.x) else NONE
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressed = NONE
        }
        val release = event.actionMasked == MotionEvent.ACTION_UP && was != NONE
        if (release && menuCovers(event.x, event.y) && buttonAt(event.x) == was) choose(was)
        return if (event.actionMasked == MotionEvent.ACTION_DOWN) pressed != NONE else was != NONE
    }

    /** Writes the view-px center of the menu button that runs [id] into [out]; false while the menu is hidden (tests). */
    fun menuButtonCenter(
        id: Int,
        out: FloatArray,
    ): Boolean {
        if (!layoutMenu()) return false
        val pad = BUTTON_PAD_DP * density
        var left = menuRect.left
        for (i in labelWidthsPx.indices) {
            val right = left + labelWidthsPx[i] + 2 * pad
            if (MENU_IDS[i] == id) {
                out[0] = (left + right) / 2
                out[1] = menuRect.centerY()
                return true
            }
            left = right
        }
        return false
    }

    private fun choose(button: Int) {
        if (button != SELECT_ALL) menuArmed = false
        invalidate()
        onMenu(MENU_IDS[button])
    }

    // The button under [xPx] of the laid out menu.
    private fun buttonAt(xPx: Float): Int {
        val pad = BUTTON_PAD_DP * density
        var left = menuRect.left
        for (i in labelWidthsPx.indices) {
            val right = left + labelWidthsPx[i] + 2 * pad
            if (xPx < right || i == labelWidthsPx.lastIndex) return i
            left = right
        }
        return NONE
    }

    // Places the menu centered above the selection's lines (below them when the top has no room), inside the view;
    // false while it does not show.
    private fun layoutMenu(): Boolean {
        // HOT PATH: per draw while a selection shows; no allocation.
        val current = selection
        if (!menuArmed || current == null || dragging != NONE) return false
        var top = Float.MAX_VALUE
        var bottom = -Float.MAX_VALUE
        var left = Float.MAX_VALUE
        var right = -Float.MAX_VALUE
        val scale = viewport.scale
        val boxes = current.boxes
        for (i in boxes.indices) {
            val index = indexOf(boxes[i].pageId)
            if (index < 0) continue
            val x = viewport.pageOriginViewX(index).toFloat()
            val y = viewport.pageOriginViewY(index).toFloat()
            val r = boxes[i].rect
            top = minOf(top, y + r.top * scale)
            bottom = maxOf(bottom, y + r.bottom * scale)
            left = minOf(left, x + r.left * scale)
            right = maxOf(right, x + r.right * scale)
        }
        if (top == Float.MAX_VALUE) return false
        val pad = BUTTON_PAD_DP * density
        var widthPx = 0f
        for (w in labelWidthsPx) widthPx += w + 2 * pad
        val heightPx = MENU_HEIGHT_DP * density
        val gap = MENU_GAP_DP * density
        val margin = MENU_MARGIN_DP * density
        val menuLeft = ((left + right - widthPx) / 2).coerceIn(margin, maxOf(margin, width - margin - widthPx))
        val above = top - gap - heightPx
        // Below: past the handles' knobs.
        val menuTop = if (above >= margin) above else bottom + 2 * KNOB_DP * density + gap
        menuRect.set(menuLeft, menuTop, menuLeft + widthPx, menuTop + heightPx)
        return true
    }

    private fun drawMenu(canvas: Canvas) {
        if (!layoutMenu()) return
        val radius = menuRect.height() / 2
        canvas.drawRoundRect(menuRect, radius, radius, menuPaint)
        val pad = BUTTON_PAD_DP * density
        val baseline = menuRect.centerY() - (labelPaint.ascent() + labelPaint.descent()) / 2
        var x = menuRect.left
        for (i in MENU_LABELS.indices) {
            canvas.drawText(MENU_LABELS[i], x + pad, baseline, labelPaint)
            x += labelWidthsPx[i] + 2 * pad
        }
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
        drawMenu(canvas)
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
                val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                // Read mode only navigates (10-editor-ui.md#read-mode).
                dragging = if (finger && controller.activeTool != CanvasTool.NAVIGATE) handleAt(event.x, event.y) else NONE
                if (dragging != NONE) menuArmed = false
            }

            MotionEvent.ACTION_MOVE -> {
                if (dragging != NONE) drag(event.x - grabXPx, event.y - grabYPx)
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = NONE
                if (was != NONE) showMenu()
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

        // The floating menu: a dark pill like the box toolbar (10-editor-ui.md#text-boxes), the same size at every zoom.
        private const val MENU_ARGB = 0xF0202124.toInt()
        private const val LABEL_ARGB = 0xFFFFFFFF.toInt()
        private const val LABEL_DP = 14f
        private const val MENU_HEIGHT_DP = 44f
        private const val BUTTON_PAD_DP = 14f
        private const val MENU_GAP_DP = 12f
        private const val MENU_MARGIN_DP = 8f
        private const val SELECT_ALL = 3
        private val MENU_LABELS = arrayOf("Cut", "Copy", "Paste", "Select all")
        private val MENU_IDS = intArrayOf(android.R.id.cut, android.R.id.copy, android.R.id.paste, android.R.id.selectAll)
    }
}
