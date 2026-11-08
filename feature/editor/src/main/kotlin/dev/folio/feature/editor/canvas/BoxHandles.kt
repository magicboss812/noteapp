package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.MainThread
import dev.folio.core.model.Document
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.OverlayTextPainter
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import dev.folio.core.render.viewport.toPagePt
import dev.folio.core.text.flow.TextRun
import dev.folio.feature.editor.text.BoxPiece
import dev.folio.feature.editor.text.SelectedBox
import kotlin.math.abs

/**
 * What the canvas host gives the box chrome: the page text runs of a frame as the page shows them ([runsOf]), the
 * text color of a page ([textArgb]), hiding frames from the tiles while the overlay draws them ([hide]), whether the
 * tiles under a page rect are drawn ([isDrawn]) and the host's tap handling for a tap inside a box in Preview ([tap]).
 */
internal class BoxHost(
    val runsOf: (PageId, ObjectId) -> List<TextRun>,
    val textArgb: (PageId) -> Int,
    val hide: (Set<ObjectId>) -> Unit,
    val isDrawn: (PageId, RectPt) -> Boolean,
    val tap: (xPx: Float, yPx: Float, stylus: Boolean) -> Unit,
)

/**
 * The chrome of the selected text box (10-editor-ui.md#text-boxes, P07-T13), a plain view above the selection: an
 * accent outline around every frame of the box, a grip on top, a width knob on each side and the box toolbar. A drag
 * on the grip, or anywhere inside a box in Preview (a finger, or the pen with the Text tool), moves the box: x follows,
 * y in whole lines, and its text moves along drawn here while the tiles leave it out; one move on release. The knobs
 * resize (P06-T17 until P07-T15). The toolbar's Edit and Delete act on release. Everything else passes on.
 */
@MainThread
@Suppress("TooManyFunctions") // drawing, hit testing and the move and resize gestures of one box
internal class BoxHandles(
    context: Context,
    private val viewport: Viewport,
    private val controller: CanvasController,
    private val document: () -> Document?,
    private val host: BoxHost,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val slopPx = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT_ARGB
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * density
        }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KNOB_FILL_ARGB }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ACCENT_ARGB }
    private val painter = OverlayTextPainter()
    private val pill = RectF()
    private val outlineRect = RectF()

    /** The toolbar (tests read where it is). */
    val toolbar = BoxToolbar(density)

    private var box: SelectedBox? = null

    // Where a move or resize puts the first frame's text column, shown until the document has it; null while none.
    private var previewPage: PageId? = null
    private var previewRect: RectPt? = null

    // Frames whose text this view draws instead of the tiles (a move), and since when it waits for the tiles to show the
    // moved text (0: not waiting; the move is still running or nothing moved).
    private var carried: Set<ObjectId> = emptySet()
    private var landingMs = 0L
    private var moving = false

    // The part held (NONE when none), where it went down, and where the pointer took hold of the box (px from the first
    // frame's top-left, or from the held edge).
    private var dragging = NONE
    private var owned = false
    private var downXPx = 0f
    private var downYPx = 0f
    private var grabXPx = 0f
    private var grabYPx = 0f
    private var pressed: BoxButton? = null

    /** The selected box's first frame as shown (page, text column), null for none (tests). */
    val shown: Pair<PageId, RectPt>? get() = box?.pieces?.firstOrNull()?.let { it.pageId to it.rect }

    /** Where a drag shows the box now (page, text column), null while none (tests). */
    val preview: Pair<PageId, RectPt>? get() = previewPage?.let { page -> previewRect?.let { page to it } }

    /** True while a grip, knob or move drag runs. */
    val isDragging: Boolean get() = dragging != NONE && dragging != PENDING

    /** True while the selected box is in Edit. */
    val editing: Boolean get() = box?.editing == true

    /** Shows the chrome of [next] (null hides it). A box placed by a move ends the move once the tiles show it. */
    fun show(next: SelectedBox?) {
        if (next == box) return
        val before = box
        box = next
        if (next == null || next.flowId != before?.flowId) {
            dragging = NONE
            owned = false
        }
        if (moving || (previewRect != null && dragging == NONE)) {
            // The document has the box where the drag left it (or the box went): the tiles take its text back.
            moving = false
            previewPage = null
            previewRect = null
            if (carried.isNotEmpty()) {
                host.hide(emptySet())
                landingMs = SystemClock.uptimeMillis()
            }
        }
        check()
        invalidate()
    }

    /** Stops drawing moved text once the tiles under the box show it (or after [LANDING_MS]); the host calls it per tile. */
    fun check() {
        if (landingMs == 0L) return
        val current = box
        val drawn = current == null || current.pieces.all { host.isDrawn(it.pageId, it.outline) }
        if (drawn || SystemClock.uptimeMillis() - landingMs >= LANDING_MS) {
            landingMs = 0L
            carried = emptySet()
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: per viewport change while a box is selected; no allocation.
        val current = box ?: return
        val pieces = current.pieces
        val first = pieces.firstOrNull() ?: return
        val drag = previewRect
        val dx = if (drag == null) 0f else drag.left - first.rect.left
        if (carried.isNotEmpty()) drawCarried(canvas, current, previewPage, drag)
        for (i in pieces.indices) {
            // The first frame goes where the drag puts it, the others follow in x.
            if (i == 0 &&
                drag != null
            ) {
                drawPiece(canvas, current, pieces[0], previewPage, drag)
            } else {
                drawPiece(canvas, current, pieces[i], dx)
            }
        }
        if (dragging == NONE || dragging == PENDING) drawToolbar(canvas, current)
    }

    // The first frame's outline where a drag puts it ([onPage], text column [drag]), with the grip and knobs.
    private fun drawPiece(
        canvas: Canvas,
        current: SelectedBox,
        piece: BoxPiece,
        onPage: PageId?,
        drag: RectPt,
    ) {
        val index = indexOf(onPage ?: piece.pageId)
        if (index < 0) return
        val o = piece.outline
        val r = piece.rect
        outline(index, o.left + drag.left - r.left, o.top + drag.top - r.top, o.right + drag.right - r.right, o.bottom + drag.top - r.top)
        canvas.drawRect(outlineRect, outlinePaint)
        if (current.movable) drawGripAndKnobs(canvas, outlineRect.left, outlineRect.top, outlineRect.right, outlineRect.bottom)
    }

    // A frame's outline shifted by [dx] (the first one also gets the grip and knobs unless it is body text).
    private fun drawPiece(
        canvas: Canvas,
        current: SelectedBox,
        piece: BoxPiece,
        dx: Float,
    ) {
        val index = indexOf(piece.pageId)
        if (index < 0) return
        val o = piece.outline
        outline(index, o.left + dx, o.top, o.right + dx, o.bottom)
        canvas.drawRect(outlineRect, outlinePaint)
        // The body text of older notes stays where its pages put it: no grip or knobs.
        if (piece == current.pieces[0] && current.movable) {
            drawGripAndKnobs(canvas, outlineRect.left, outlineRect.top, outlineRect.right, outlineRect.bottom)
        }
    }

    // Sets [outlineRect] to page rect ([left], [top], [right], [bottom]) pt of page [index] in view px.
    private fun outline(
        index: Int,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        outlineRect.set(x + left * scale, y + top * scale, x + right * scale, y + bottom * scale)
    }

    // The moved box's text over the tiles that leave it out: the first frame where the drag puts it, the others in x.
    private fun drawCarried(
        canvas: Canvas,
        current: SelectedBox,
        dragPage: PageId?,
        drag: RectPt?,
    ) {
        val pieces = current.pieces
        val first = pieces[0]
        val scale = viewport.scale
        for (i in pieces.indices) {
            val piece = pieces[i]
            val onPage = if (i == 0 && dragPage != null) dragPage else piece.pageId
            val index = indexOf(onPage)
            if (index < 0) continue
            val ox = if (drag == null) 0f else drag.left - first.rect.left
            val oy = if (i == 0 && drag != null) drag.top - first.rect.top else 0f
            canvas.save()
            canvas.translate(viewport.pageOriginViewX(index).toFloat() + ox * scale, viewport.pageOriginViewY(index).toFloat() + oy * scale)
            canvas.scale(scale, scale)
            painter.draw(canvas, host.runsOf(piece.pageId, piece.frameId), WHOLE_PAGE, scale, host.textArgb(onPage))
            canvas.restore()
        }
    }

    private fun drawGripAndKnobs(
        canvas: Canvas,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
    ) {
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

    private fun drawToolbar(
        canvas: Canvas,
        current: SelectedBox,
    ) {
        if (!placeToolbar(current)) return
        toolbar.draw(canvas, current.editing)
    }

    // Puts the toolbar over the first frame's outline as shown now; false when that page is not laid out.
    private fun placeToolbar(current: SelectedBox): Boolean {
        val first = current.pieces.firstOrNull() ?: return false
        val index = indexOf(first.pageId)
        if (index < 0) return false
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        val o = first.outline
        toolbar.place(x + (o.left + o.right) / 2 * scale, y + o.top * scale, y + o.bottom * scale, width.toFloat(), height.toFloat())
        return true
    }

    /**
     * True if ([xPx], [yPx]) is on the grip or a knob: the pen there moves the box rather than writing, so the box
     * being edited drops to Preview first (A-045, D-041).
     */
    fun covers(
        xPx: Float,
        yPx: Float,
    ): Boolean = partAt(xPx, yPx, stylus = true) in GRIP..RIGHT

    /** True if ([xPx], [yPx]) is on the toolbar (the pen taps its buttons). */
    fun toolbarCovers(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val current = box ?: return false
        return placeToolbar(current) && toolbar.pill.contains(xPx, yPx)
    }

    /**
     * A finger or pen event: a down on the toolbar presses a button, on the grip or a knob starts a drag, inside a box in
     * Preview starts a move once it passes the touch slop (a tap there goes to [BoxHost.tap]); the moves show where the
     * box goes, the up places it. True if the event belongs to such a gesture.
     */
    fun handleTouch(event: MotionEvent): Boolean {
        // HOT PATH: per MotionEvent; bounds checks unless a drag runs (a drag move builds its preview rect).
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) down(event)
        if (!owned) return false
        when (action) {
            MotionEvent.ACTION_MOVE -> move(event.x, event.y)

            MotionEvent.ACTION_UP -> up(event)

            // A second finger or a cancel: the box stays where it was; the rest of the gesture is still ours.
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL -> cancelDrag()
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            dragging = NONE
            owned = false
            pressed = null
            invalidate()
        }
        return true
    }

    private fun down(event: MotionEvent) {
        // Read mode only navigates (10-editor-ui.md#read-mode): the chrome pans like the page around it.
        val tool = event.getToolType(0)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS
        val inert = tool == MotionEvent.TOOL_TYPE_ERASER || controller.activeTool == CanvasTool.NAVIGATE
        val x = event.x
        val y = event.y
        pressed = if (inert || !toolbarCovers(x, y)) null else toolbar.buttonAt(x, y)
        dragging = if (inert || pressed != null) NONE else partAt(x, y, stylus)
        owned = pressed != null || dragging != NONE
        downXPx = x
        downYPx = y
        if (dragging != NONE) grab(x, y)
    }

    private fun move(
        xPx: Float,
        yPx: Float,
    ) {
        if (dragging == PENDING) {
            if (abs(xPx - downXPx) < slopPx && abs(yPx - downYPx) < slopPx) return
            dragging = GRIP
        }
        if (dragging == NONE) return
        if (dragging == GRIP) carry()
        drag(xPx, yPx)
    }

    private fun up(event: MotionEvent) {
        val button = pressed
        when {
            button != null -> {
                if (toolbar.buttonAt(event.x, event.y) == button) press(button)
            }

            dragging == PENDING -> {
                host.tap(event.x, event.y, event.getToolType(0) == MotionEvent.TOOL_TYPE_STYLUS)
            }

            dragging != NONE -> {
                drop()
            }
        }
    }

    private fun press(button: BoxButton) {
        val texts = controller.textController ?: return
        when (button) {
            BoxButton.EDIT -> texts.editBox()
            BoxButton.DELETE -> texts.deleteBox()
            BoxButton.COLOR, BoxButton.SIZE, BoxButton.ALIGN, BoxButton.FILL -> Unit
        }
    }

    // The move starts: a box being edited drops to Preview (the field lets go of the pen and the keyboard), and this
    // view draws the box's text while the tiles leave it out.
    private fun carry() {
        val current = box ?: return
        if (current.editing) controller.textController?.blur()
        if (carried.isNotEmpty() && landingMs == 0L) return
        landingMs = 0L
        carried = current.pieces.mapTo(HashSet()) { it.frameId }
        host.hide(carried)
    }

    private fun cancelDrag() {
        dragging = NONE
        clearPreview()
    }

    // The drag ended without a move (refused, or nothing changed): the box and its text are shown where they were.
    private fun clearPreview() {
        moving = false
        previewPage = null
        previewRect = null
        if (carried.isNotEmpty()) {
            carried = emptySet()
            landingMs = 0L
            host.hide(emptySet())
        }
        invalidate()
    }

    // The part under ([xPx], [yPx]): the grip (a 48 dp target on the top edge's middle), a knob (48 dp targets on the
    // side edges' middles), the inside of a box in Preview that a drag may move (a finger, or the pen with the Text
    // tool), or NONE.
    private fun partAt(
        xPx: Float,
        yPx: Float,
        stylus: Boolean,
    ): Int {
        val current = box ?: return NONE
        val first = current.pieces.firstOrNull() ?: return NONE
        val index = indexOf(first.pageId)
        if (index < 0) return NONE
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        val reach = TARGET_DP * density / 2
        val o = first.outline
        val top = y + o.top * scale
        val midY = y + (o.top + o.bottom) / 2 * scale
        val left = x + o.left * scale
        val right = x + o.right * scale
        return when {
            current.movable && near(xPx, yPx, (left + right) / 2, top, reach) -> GRIP
            near(xPx, yPx, right, midY, reach) && current.movable -> RIGHT
            near(xPx, yPx, left, midY, reach) && current.movable -> LEFT
            movesInside(current, stylus) && inside(current, xPx, yPx) -> PENDING
            else -> NONE
        }
    }

    // A drag inside a box moves it in Preview, with a finger or with the pen holding the Text tool (other pens write).
    private fun movesInside(
        current: SelectedBox,
        stylus: Boolean,
    ): Boolean = current.movable && !current.editing && (!stylus || controller.activeTool == CanvasTool.TEXT)

    private fun inside(
        current: SelectedBox,
        xPx: Float,
        yPx: Float,
    ): Boolean {
        for (piece in current.pieces) {
            val index = indexOf(piece.pageId)
            if (index < 0) continue
            val o = piece.outline
            outline(index, o.left, o.top, o.right, o.bottom)
            if (xPx in outlineRect.left..outlineRect.right && yPx in outlineRect.top..outlineRect.bottom) return true
        }
        return false
    }

    private fun near(
        xPx: Float,
        yPx: Float,
        cx: Float,
        cy: Float,
        reach: Float,
    ): Boolean = xPx in cx - reach..cx + reach && yPx in cy - reach..cy + reach

    // Where the pointer took hold: the box's first text column top-left for a move, the held edge for a knob.
    private fun grab(
        xPx: Float,
        yPx: Float,
    ) {
        val first = box?.pieces?.firstOrNull() ?: return
        val index = indexOf(first.pageId)
        val scale = viewport.scale
        val x = viewport.pageOriginViewX(index).toFloat()
        val y = viewport.pageOriginViewY(index).toFloat()
        val r = first.rect
        grabXPx =
            when (dragging) {
                LEFT -> xPx - (x + r.left * scale)
                RIGHT -> xPx - (x + r.right * scale)
                else -> xPx - (x + r.left * scale)
            }
        grabYPx = yPx - (y + r.top * scale)
    }

    // The box where the pointer is now: a move carries it to the page under its top edge (x free, top on a rule), a
    // knob moves its edge on the box's own page (snapped, P06-T17).
    private fun drag(
        xPx: Float,
        yPx: Float,
    ) {
        val doc = document() ?: return
        val first = box?.pieces?.firstOrNull() ?: return
        val r = first.rect
        val edgeX = xPx - grabXPx
        val topY = yPx - grabYPx
        val target = if (dragging == GRIP) doc.pages.getOrNull(viewport.pageIndexAt(topY))?.id ?: return else first.pageId
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
        val current = box ?: return clearPreview()
        val first = current.pieces.firstOrNull() ?: return clearPreview()
        val to = previewPage ?: return clearPreview()
        val r = previewRect ?: return clearPreview()
        val texts = controller.textController
        if (texts == null || (to == first.pageId && r == first.rect)) return clearPreview()
        moving = true
        val done: (Boolean) -> Unit = { placed -> if (!placed) post { clearPreview() } }
        if (dragging == GRIP) {
            texts.moveBox(current.flowId, to, r, done)
        } else {
            texts.placeBox(first.frameId, first.pageId, to, r, done)
        }
    }

    private fun indexOf(page: PageId): Int = document()?.pages?.indexOfFirst { it.id == page } ?: -1

    companion object {
        private const val NONE = 0
        private const val GRIP = 1
        private const val LEFT = 2
        private const val RIGHT = 3

        // Down inside a box in Preview: a move once it passes the touch slop, a tap otherwise.
        private const val PENDING = 4

        private const val OUTLINE_DP = 1.5f
        private const val KNOB_DP = 5f
        private const val GRIP_WIDTH_DP = 20f
        private const val GRIP_HEIGHT_DP = 8f
        private const val DOT_DP = 1f
        private const val DOT_GAP_DP = 4f
        private const val TARGET_DP = 48f

        /** The moved text stays drawn here at most this long after the move while the tiles catch up (like TextHandoff). */
        private const val LANDING_MS = 300L

        // Text runs are painted whole: the overlay clips nothing.
        private val WHOLE_PAGE = RectPt(-1e6f, -1e6f, 1e6f, 1e6f)

        // The accent and a white knob fill (named, no theme inside the canvas view).
        private const val ACCENT_ARGB = 0xFF4B85E0.toInt()
        private const val KNOB_FILL_ARGB = 0xFFFDFDFE.toInt()
    }
}
