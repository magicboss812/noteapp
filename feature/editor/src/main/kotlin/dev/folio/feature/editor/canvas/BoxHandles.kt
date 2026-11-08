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
import dev.folio.core.model.Borders
import dev.folio.core.model.BoxAlign
import dev.folio.core.model.BoxWidth
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextAlign
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
import dev.folio.feature.editor.text.WidthPreview
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
 * y in whole lines, and its text moves along drawn here while the tiles leave it out; one move on release. A knob
 * moves its border smoothly (P07-T15): the text rewraps live from a layout made off the main thread, one resize on
 * release. The toolbar's buttons act on release. Everything else passes on.
 */
@MainThread
@Suppress("TooManyFunctions", "LargeClass") // drawing, hit testing and the move, resize and toolbar gestures of one box
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
    private val edgeShift = FloatArray(EDGES)

    /** The toolbar (tests read where it is). */
    val toolbar = BoxToolbar(density)

    private var box: SelectedBox? = null

    // Where a move or resize puts the first frame's text column, shown until the document has it; null while none.
    private var previewPage: PageId? = null
    private var previewRect: RectPt? = null

    // An arrow key move (P07-T14) while [keyed]: the first frame's text column top-left on [keyPage], kept as floats so
    // a frame of the move allocates nothing.
    private var keyed = false
    private var keyPage: PageId? = null
    private var keyLeftPt = 0f
    private var keyTopPt = 0f

    // Frames whose text this view draws instead of the tiles (a move), and since when it waits for the tiles to show the
    // moved text (0: not waiting; the move is still running or nothing moved).
    private var carried: Set<ObjectId> = emptySet()
    private var landingMs = 0L
    private var moving = false

    // A knob drag (P07-T15) from its grab until the resized box lands: the box laid out at the knob's borders once the
    // text engine made it ([resized]), whether a layout is being made, and the borders to lay out next; [resizeCount]
    // tells a layout of an earlier drag from one of this drag.
    private var resizing = false
    private var resizeCount = 0
    private var resized: WidthPreview? = null
    private var widthAsked = false
    private var widthNext: Borders? = null

    // The part held (NONE when none), where it went down, and where the pointer took hold of the box (px from the first
    // frame's top-left, or from the held edge).
    private var dragging = NONE
    private var owned = false
    private var downXPx = 0f
    private var downYPx = 0f
    private var grabXPx = 0f
    private var grabYPx = 0f
    private var pressed: BoxButton? = null

    // The open toolbar popup (Alignment P07-T16, Font size and Color P07-T17), null for none, and the item pressed in it
    // (-1 for none).
    private var open: BoxPopup? = null
    private var pressedItem = -1

    /** The open toolbar popup, null for none (tests). */
    val openPopup: BoxPopup? get() = open

    /** True while the Alignment popup is open (tests). */
    val alignsOpen: Boolean get() = open == BoxPopup.ALIGN

    /** The selected box's first frame as shown (page, text column), null for none (tests). */
    val shown: Pair<PageId, RectPt>? get() = box?.pieces?.firstOrNull()?.let { it.pageId to it.rect }

    /** Where a drag or an arrow key move shows the box now (page, text column), null while none (tests). */
    val preview: Pair<PageId, RectPt>?
        get() {
            val first = box?.pieces?.firstOrNull()
            val page = keyPage
            if (keyed && first != null && page != null) {
                return page to RectPt(keyLeftPt, keyTopPt, keyLeftPt + first.rect.widthPt, keyTopPt + first.rect.heightPt)
            }
            return previewPage?.let { p -> previewRect?.let { p to it } }
        }

    /** True while an arrow key move runs (from [beginKeys] until [endKeys] or the box goes). */
    val keysHeld: Boolean get() = keyed

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
            open = null
            keyed = false
            keyPage = null
            endResize()
        }
        if (keyed && next != null) {
            // An earlier key move landed while keys still move the box: this view keeps drawing its text.
            moving = false
            previewPage = null
            previewRect = null
            landingMs = 0L
            carried = next.pieces.mapTo(HashSet()) { it.frameId }
            host.hide(carried)
        } else if (moving || (previewRect != null && dragging == NONE)) {
            // The document has the box where the drag left it (or the box went): the tiles take its text back.
            moving = false
            previewPage = null
            previewRect = null
            endResize()
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
        val page = if (keyed) keyPage else previewPage
        val shifted = keyed || previewRect != null
        moveOffsets(first.rect)
        val dl = edgeShift[0]
        val dt = edgeShift[1]
        val dr = edgeShift[2]
        // A resize rewraps the text in place: nothing moves it.
        if (carried.isNotEmpty()) drawCarried(canvas, current, page, if (resizing) 0f else dl, if (resizing) 0f else dt)
        for (i in pieces.indices) {
            // The first frame goes where the move puts it, the others follow in x (a resize: by their borders).
            if (i == 0 && shifted) {
                drawPiece(canvas, current, pieces[0], page, dl, dt, dr)
            } else {
                drawPiece(canvas, current, pieces[i], dl, dr)
            }
        }
        // The toolbar shows again once the moved box landed where the document has it.
        if ((dragging == NONE || dragging == PENDING) && !shifted) drawToolbar(canvas, current)
    }

    // How far a key move or a drag moves the left, top and right edges of the first frame's text column [r], pt, into
    // [edgeShift] (a knob moves one edge; nothing moves without either).
    private fun moveOffsets(r: RectPt) {
        val drag = previewRect
        when {
            keyed -> {
                edgeShift[0] = keyLeftPt - r.left
                edgeShift[1] = keyTopPt - r.top
                edgeShift[2] = keyLeftPt - r.left
            }

            drag != null -> {
                edgeShift[0] = drag.left - r.left
                edgeShift[1] = drag.top - r.top
                edgeShift[2] = drag.right - r.right
            }

            else -> {
                edgeShift.fill(0f)
            }
        }
    }

    // The first frame's outline where a move puts it ([onPage]; left, top and right edges moved by [dl], [dt], [dr] pt),
    // with the grip and knobs.
    @Suppress("LongParameterList") // the three edge offsets keep the move free of a rect per frame
    private fun drawPiece(
        canvas: Canvas,
        current: SelectedBox,
        piece: BoxPiece,
        onPage: PageId?,
        dl: Float,
        dt: Float,
        dr: Float,
    ) {
        val index = indexOf(onPage ?: piece.pageId)
        if (index < 0) return
        val o = piece.outline
        val w = widthOutline(piece)
        if (w !=
            null
        ) {
            outline(index, w.left, w.top, w.right, w.bottom)
        } else {
            outline(index, o.left + dl, o.top + dt, o.right + dr, o.bottom + dt)
        }
        canvas.drawRect(outlineRect, outlinePaint)
        if (current.movable) drawGripAndKnobs(canvas, outlineRect.left, outlineRect.top, outlineRect.right, outlineRect.bottom)
    }

    // A frame's outline with its left and right edges moved by [dl] and [dr] (the first one also gets the grip and
    // knobs unless it is body text).
    private fun drawPiece(
        canvas: Canvas,
        current: SelectedBox,
        piece: BoxPiece,
        dl: Float,
        dr: Float,
    ) {
        val index = indexOf(piece.pageId)
        if (index < 0) return
        val o = piece.outline
        val w = widthOutline(piece)
        if (w != null) outline(index, w.left, w.top, w.right, w.bottom) else outline(index, o.left + dl, o.top, o.right + dr, o.bottom)
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

    // The moved box's text over the tiles that leave it out: the first frame on [dragPage] moved by ([dx], [dy]) pt, the
    // others by [dx].
    private fun drawCarried(
        canvas: Canvas,
        current: SelectedBox,
        dragPage: PageId?,
        dx: Float,
        dy: Float,
    ) {
        val pieces = current.pieces
        val scale = viewport.scale
        for (i in pieces.indices) {
            val piece = pieces[i]
            val onPage = if (i == 0 && dragPage != null) dragPage else piece.pageId
            val index = indexOf(onPage)
            if (index < 0) continue
            val oy = if (i == 0) dy else 0f
            canvas.save()
            canvas.translate(viewport.pageOriginViewX(index).toFloat() + dx * scale, viewport.pageOriginViewY(index).toFloat() + oy * scale)
            canvas.scale(scale, scale)
            val runs = resized?.runs?.get(piece.frameId) ?: host.runsOf(piece.pageId, piece.frameId)
            painter.draw(canvas, runs, WHOLE_PAGE, scale, host.textArgb(onPage))
            canvas.restore()
        }
    }

    // The outline of [piece] in the knob's width preview, null without one.
    private fun widthOutline(piece: BoxPiece): RectPt? = resized?.outlines?.get(piece.frameId)

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
        val auto = host.textArgb(current.pieces.first().pageId)
        toolbar.draw(canvas, current.editing, current.fill, current.align, current.looks, auto)
        if (open != null) toolbar.drawPopup(canvas, current.align, current.looks, auto)
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
        open?.let { toolbar.placePopup(it, width.toFloat(), height.toFloat()) }
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

    /** True if ([xPx], [yPx]) is on the toolbar or its open popup (the pen taps their buttons). */
    fun toolbarCovers(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val current = box ?: return false
        if (!placeToolbar(current)) return false
        return toolbar.pill.contains(xPx, yPx) || (open != null && toolbar.popup.contains(xPx, yPx))
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
            pressedItem = -1
            invalidate()
        }
        return true
    }

    private fun down(event: MotionEvent) {
        // A touch ends a key move where it is (keys still held move nothing more, P07-T14).
        if (keyed) endKeys()
        // Read mode only navigates (10-editor-ui.md#read-mode): the chrome pans like the page around it.
        val tool = event.getToolType(0)
        val stylus = tool == MotionEvent.TOOL_TYPE_STYLUS
        val inert = tool == MotionEvent.TOOL_TYPE_ERASER || controller.activeTool == CanvasTool.NAVIGATE
        val x = event.x
        val y = event.y
        val covered = !inert && pressAt(x, y)
        dragging = if (inert || covered) NONE else partAt(x, y, stylus)
        owned = pressed != null || pressedItem >= 0 || dragging != NONE
        downXPx = x
        downYPx = y
        if (dragging == LEFT || dragging == RIGHT) {
            endResize()
            resizing = true
        }
        if (dragging != NONE) grab(x, y)
    }

    // A down at ([xPx], [yPx]) on the toolbar or the open popup presses what is there; a touch anywhere but on the
    // popup or its button closes the popup. True if the toolbar or popup covers the point.
    private fun pressAt(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val covered = toolbarCovers(xPx, yPx)
        val popup = open
        pressedItem = if (covered && popup != null) toolbar.itemAt(xPx, yPx) else -1
        pressed = if (!covered || pressedItem >= 0) null else toolbar.buttonAt(xPx, yPx)
        if (popup != null && pressedItem < 0 && pressed != popup.button) {
            open = null
            invalidate()
        }
        return covered
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
        carry()
        drag(xPx, yPx)
    }

    private fun up(event: MotionEvent) {
        val button = pressed
        val item = pressedItem
        when {
            item >= 0 -> {
                if (toolbar.itemAt(event.x, event.y) == item) choose(item)
            }

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
            BoxButton.FILL -> texts.fillWidth()
            BoxButton.ALIGN -> toggle(BoxPopup.ALIGN)
            BoxButton.SIZE -> toggle(BoxPopup.SIZE)
            BoxButton.COLOR -> toggle(BoxPopup.COLOR)
        }
    }

    private fun toggle(kind: BoxPopup) {
        open = if (open == kind) null else kind
    }

    // Item [item] of the open popup picked: the box takes it (Preview stays; no keyboard). The size popup stays open, so
    // smaller and larger can be pressed again; the others close.
    private fun choose(item: Int) {
        val texts = controller.textController ?: return
        val current = box ?: return
        when (open) {
            BoxPopup.ALIGN -> {
                open = null
                BoxToolbar.alignOf(item)?.let(texts::alignBox)
            }

            BoxPopup.SIZE -> {
                texts.sizeBox(BoxToolbar.sizeOf(item, current.looks.sizeRatio))
            }

            BoxPopup.COLOR -> {
                open = null
                texts.colorBox(BoxToolbar.colorOf(item))
            }

            null -> {
                Unit
            }
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
        endResize()
        // Keys still move the box: this view goes on drawing its text.
        if (carried.isNotEmpty() && !keyed) {
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
    // knob moves its border smoothly inside the page text zone (P07-T15) and the text rewraps there.
    private fun drag(
        xPx: Float,
        yPx: Float,
    ) {
        val doc = document() ?: return
        val current = box ?: return
        val first = current.pieces.firstOrNull() ?: return
        val r = first.rect
        val edgeX = xPx - grabXPx
        val topY = yPx - grabYPx
        val target = if (dragging == GRIP) doc.pages.getOrNull(viewport.pageIndexAt(topY))?.id ?: return else first.pageId
        val ref = doc.pageRef(target) ?: return
        val pt = viewport.toPagePt(target, PointPx(edgeX, topY))
        val template = ref.background.template
        if (dragging == GRIP) {
            previewRect = TextZones.movedBox(ref.spec, template, r, pt.x, pt.y)
        } else {
            val zoneRect = TextZones.boxZone(ref.spec, template)
            val zone = Borders(zoneRect.left, zoneRect.right)
            val shown = Borders(r.left, r.right)
            // In Center both borders move alike around the middle (P07-T16).
            val borders =
                if (current.align == TextAlign.CENTER) {
                    BoxAlign.symmetricKnob(zone, shown, pt.x)
                } else {
                    BoxWidth.knob(zone, shown, dragging == LEFT, pt.x)
                }
            val before = previewRect
            previewRect = r.copy(left = borders.leftPt, right = borders.rightPt)
            if (before == null || before.left != borders.leftPt || before.right != borders.rightPt) askWidth(current.flowId, borders)
        }
        previewPage = target
        invalidate()
    }

    // Asks the text engine for box [flowId] laid out between [borders]; while a layout is being made only the newest
    // borders wait, so a fast drag lays out at most one width behind the knob.
    private fun askWidth(
        flowId: FlowId,
        borders: Borders,
    ) {
        val texts = controller.textController ?: return
        if (widthAsked) {
            widthNext = borders
            return
        }
        widthAsked = true
        widthNext = null
        val drag = resizeCount
        texts.previewWidth(flowId, borders) { preview ->
            widthAsked = false
            if (resizing && box?.flowId == flowId) {
                if (preview != null && drag == resizeCount) resized = preview
                invalidate()
                widthNext?.let { askWidth(flowId, it) }
            }
        }
    }

    // The knob drag is over (landed, refused, or another box): the width preview goes.
    private fun endResize() {
        resizing = false
        resizeCount++
        resized = null
        widthNext = null
    }

    private fun drop() {
        val current = box ?: return clearPreview()
        val first = current.pieces.firstOrNull() ?: return clearPreview()
        val to = previewPage ?: return clearPreview()
        val r = previewRect ?: return clearPreview()
        place(current, first, to, r, move = dragging == GRIP)
    }

    // The box goes to text column [r] of page [to] (a move, else a knob's new borders for all its frames) as one undo
    // step; the preview stays until the document has it, or clears when the step is refused or changes nothing.
    private fun place(
        current: SelectedBox,
        first: BoxPiece,
        to: PageId,
        r: RectPt,
        move: Boolean,
    ) {
        val texts = controller.textController
        if (texts == null || (to == first.pageId && r == first.rect)) return clearPreview()
        moving = true
        val done: (Boolean) -> Unit = { placed -> if (!placed) post { clearPreview() } }
        if (move) {
            texts.moveBox(current.flowId, to, r, done)
        } else {
            texts.resizeBox(current.flowId, Borders(r.left, r.right), done)
        }
    }

    /**
     * Starts an arrow key move of the box in Preview (P07-T14): this view draws the box and its text from now on and
     * returns where its first text column is (where a move that is still landing put it). Null without a movable box in
     * Preview, or while a drag runs.
     */
    fun beginKeys(): Pair<PageId, RectPt>? {
        val current = box ?: return null
        val first = current.pieces.firstOrNull() ?: return null
        if (!current.movable || current.editing || dragging != NONE) return null
        val landingPage = previewPage
        val landing = previewRect
        val page = if (landingPage != null && landing != null) landingPage else first.pageId
        val start = if (landingPage != null && landing != null) landing else first.rect
        keyed = true
        keyPage = page
        keyLeftPt = start.left
        keyTopPt = start.top
        carry()
        invalidate()
        return page to RectPt(start.left, start.top, start.left + first.rect.widthPt, start.top + first.rect.heightPt)
    }

    /** Shows the key move's first text column with its top-left at ([leftPt], [topPt]) of its page. */
    fun keyMove(
        leftPt: Float,
        topPt: Float,
    ) {
        // HOT PATH: once per display frame while an arrow key is held; no allocation.
        if (!keyed || (leftPt == keyLeftPt && topPt == keyTopPt)) return
        keyLeftPt = leftPt
        keyTopPt = topPt
        invalidate()
    }

    /** Ends the key move: the box goes where the keys left it, as one undo step (like the drop of a drag). */
    fun endKeys() {
        if (!keyed) return
        keyed = false
        val page = keyPage
        keyPage = null
        val current = box
        val first = current?.pieces?.firstOrNull()
        if (current == null || first == null || page == null) {
            clearPreview()
        } else {
            val r = RectPt(keyLeftPt, keyTopPt, keyLeftPt + first.rect.widthPt, keyTopPt + first.rect.heightPt)
            previewPage = page
            previewRect = r
            place(current, first, page, r, move = true)
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

        // Left, top and right: the edges a move shifts.
        private const val EDGES = 3

        /** The moved text stays drawn here at most this long after the move while the tiles catch up (like TextHandoff). */
        private const val LANDING_MS = 300L

        // Text runs are painted whole: the overlay clips nothing.
        private val WHOLE_PAGE = RectPt(-1e6f, -1e6f, 1e6f, 1e6f)

        // The accent and a white knob fill (named, no theme inside the canvas view).
        private const val ACCENT_ARGB = 0xFF4B85E0.toInt()
        private const val KNOB_FILL_ARGB = 0xFFFDFDFE.toInt()
    }
}
