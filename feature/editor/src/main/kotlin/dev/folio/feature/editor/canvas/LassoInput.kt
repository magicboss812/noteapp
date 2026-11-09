package dev.folio.feature.editor.canvas

import android.graphics.Bitmap
import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusTarget
import dev.folio.core.model.ImageObject
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.edit.ObjectCopies
import dev.folio.core.model.edit.SelectionEdit
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.lasso.Lasso
import dev.folio.core.model.lasso.SelectionBox
import dev.folio.core.model.lasso.SelectionHandle
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY
import kotlin.math.hypot

/** Screen sizes of the lasso in px: handle grab radius, rotate knob distance above the box, shortest scaled side, tap slop. */
internal data class LassoSizes(
    val handleRadiusPx: Float,
    val rotateOffsetPx: Float,
    val minSidePx: Float,
    val tapSlopPx: Float,
)

/** A lasso selection: objects [ids] (in z-order) of page [pageId]. */
internal data class LassoSelection(
    val pageId: PageId,
    val ids: List<ObjectId>,
)

/** Objects [ids] of page [pageId] the tiles leave out while the page body is [before] (compared by identity). */
internal class LassoHidden(
    val pageId: PageId,
    val ids: Set<ObjectId>,
    val before: Page,
)

/** The selection drawn into [bitmap]: page region [regionPt] at [scale] px per pt. */
internal class LassoSnapshot(
    val bitmap: Bitmap,
    val regionPt: RectPt,
    val scale: Float,
)

/** What the lasso needs from the canvas host. */
internal interface LassoHost {
    /** Redraws the lasso overlay. */
    fun invalidate()

    /** The tiles leave the objects of [selection] out while the page body is the one shown now (null: leave nothing out). */
    fun hide(selection: LassoSelection?)

    /** The selection inside [box] drawn for the overlay to move, or null. */
    fun snapshot(
        selection: LassoSelection,
        box: RectPt,
    ): LassoSnapshot?

    /** Runs [edit] on [selection] as one undo step; [done] gets whether the session took it (main thread). */
    fun edit(
        selection: LassoSelection,
        edit: SelectionEdit,
        done: (Boolean) -> Unit,
    )

    /** Puts a picture of [objects] of page [pageId] on the system clipboard. */
    fun copyImage(
        pageId: PageId,
        objects: List<PageObject>,
    ) = Unit

    /** Opens the crop and turn screen for image [id] of page [pageId]. */
    fun editImage(
        pageId: PageId,
        id: ObjectId,
    ) = Unit

    /** True when the tiles under [rect] of page [pageId] are drawn and fresh. */
    fun isDrawn(
        pageId: PageId,
        rect: RectPt,
    ): Boolean
}

/**
 * The lasso (06-ink-input.md#lasso, A-066). The pen draws a freeform or rectangle lasso on one page; at the pen up the
 * objects inside by [Lasso] become the selection, shown with its box, handles and action bar ([toolbar]). The pen or a
 * finger dragging inside the box moves the selection, on a handle scales or turns it ([SelectionBox]); the overlay draws
 * a [LassoSnapshot] under the drag's transform while the tiles leave the objects out, and the pen up commits one
 * `SelectionEdit.Transform`. The overlay keeps drawing the snapshot ([pending]) until the tiles show the result. A tap
 * beside the selection drops it; with nothing selected and a filled clipboard it shows the Paste pill there.
 */
@MainThread
@Suppress("TooManyFunctions") // the StylusTarget API plus finger routing, lasso path, drags and action bar presses on one state
internal class LassoInput(
    private val viewport: Viewport,
    private val pageIdAt: (Int) -> PageId?,
    private val indexOf: (PageId) -> Int,
    private val objectsOn: (PageId) -> List<PageObject>?,
    private val spec: () -> LassoSpec,
    private val clipboard: () -> ObjectClipboard,
    private val sizes: LassoSizes,
    private val host: LassoHost,
    /** The action bar; the overlay places and paints it. */
    val toolbar: LassoToolbar,
) : StylusTarget {
    private enum class Phase { IDLE, PATH, DRAG, PRESS }

    private var phase = Phase.IDLE
    private var fingerPointer = -1

    // The lasso path in page pt of [pathPage]; grows (rarely) while drawing.
    private var xs = FloatArray(INITIAL_POINTS)
    private var ys = FloatArray(INITIAL_POINTS)

    /** Points of the lasso path being drawn (or the rectangle's two corners). */
    var pathSize = 0
        private set

    /** Stack page of the lasso path being drawn; -1 while none is. */
    var pathPage = -1
        private set

    /** True while the path is a rectangle from its first to its last point. */
    var rectangle = false
        private set
    private var downXPx = 0f
    private var downYPx = 0f
    private var tapping = false

    /** The selection, or null. */
    var selection: LassoSelection? = null
        private set

    // Cached box of the selection, by the page objects it was computed from.
    private var boxObjects: List<PageObject>? = null
    private var boxCache: RectPt? = null
    private var image: ImageObject? = null

    /** True when the selection is exactly one image (the action bar offers Crop); valid after [box]. */
    val imageSelected: Boolean get() = selection != null && image != null

    // A drag on the selection: [dragHandle] null moves; positions in page pt.
    private var dragHandle: SelectionHandle? = null
    private var dragBox = RectPt.EMPTY
    private var fromX = 0f
    private var fromY = 0f
    private var toX = 0f
    private var toY = 0f

    /** What the overlay moves: the selection drawn at the drag start. */
    var snapshot: LassoSnapshot? = null
        private set

    /** The committed transform the overlay draws until the tiles show it. */
    var pending: Affine? = null
        private set
    private var pendingBox = RectPt.EMPTY
    private var pendingBefore: List<PageObject>? = null

    // The button pressed on the action bar (or popup item when [pressedItem] >= 0).
    private var pressed: LassoButton? = null
    private var pressedItem = -1

    /** Where Paste puts the clipboard (page, point); null hides the Paste pill. */
    var pasteAt: Pair<PageId, PointPt>? = null
        private set

    /** True while a drag moves, scales or turns the selection. */
    val dragging: Boolean get() = phase == Phase.DRAG

    /** The handle being dragged (null for a move or no drag). */
    val draggedHandle: SelectionHandle? get() = if (dragging) dragHandle else null

    /** True when the lasso shows anything (path, selection, Paste pill). */
    val showing: Boolean get() = pathPage >= 0 || selection != null || pasteAt != null || pending != null

    /** Path point [i] in page pt. */
    fun pathX(i: Int): Float = xs[i]

    /** Path point [i] in page pt. */
    fun pathY(i: Int): Float = ys[i]

    /** Stack index of page [id], -1 if it is not laid out. */
    fun pageIndexOf(id: PageId): Int = indexOf(id)

    /** Stack index of the selection's page, -1 without a selection. */
    val selectionPage: Int get() = selection?.let { indexOf(it.pageId) } ?: -1

    /**
     * The selection's box in page pt: the bounds of its objects as they are now (mid-drag: at the drag start), or null; a
     * selection whose objects are all gone (undo) is dropped.
     */
    fun box(): RectPt? {
        val sel = selection ?: return null
        if (dragging) return dragBox
        if (pending != null) return pendingBox
        val objects = objectsOn(sel.pageId) ?: return null
        if (objects !== boxObjects) {
            boxObjects = objects
            val ids = sel.ids.toHashSet()
            val selected = objects.filter { it.id in ids }
            boxCache = if (selected.isEmpty()) null else ObjectCopies.bounds(selected)
            image = selected.singleOrNull() as? ImageObject
        }
        if (boxCache == null) clear()
        return boxCache
    }

    /** The transform the overlay draws the snapshot and box with: the live drag's, the pending one, or null. */
    fun liveAffine(): Affine? = if (dragging) dragAffine() else pending

    /** The turn of a rotate drag in degrees, or null. */
    fun rotationDeg(): Float? =
        if (dragging &&
            dragHandle == SelectionHandle.ROTATE
        ) {
            SelectionBox.rotation(dragBox, PointPt(fromX, fromY), PointPt(toX, toY))
        } else {
            null
        }

    private fun dragAffine(): Affine =
        SelectionBox.drag(dragBox, dragHandle, PointPt(fromX, fromY), PointPt(toX, toY), sizes.minSidePx / viewport.scale)

    /** The rotate knob's distance above the box in page pt at the current zoom. */
    fun rotateOffsetPt(): Float = sizes.rotateOffsetPx / viewport.scale

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        val i = event.findPointerIndex(pointerId)
        if (eraser || i < 0) return
        down(event.getX(i), event.getY(i), finger = false)
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent; stores points, the overlay draws per frame.
        val i = event.findPointerIndex(pointerId)
        if (i < 0) return
        for (h in 0 until event.historySize) move(event.getHistoricalX(i, h), event.getHistoricalY(i, h))
        move(event.getX(i), event.getY(i))
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        val i = event.findPointerIndex(pointerId)
        if (i < 0) return cancel()
        move(event.getX(i), event.getY(i))
        up(event.getX(i), event.getY(i))
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) = cancel()

    /**
     * A finger [event]: a finger down on the selection box, a handle or the action bar is the lasso's until it lifts
     * (true); any other finger gesture is not (false: it pans and zooms).
     */
    fun onFingerTouch(event: MotionEvent): Boolean {
        // HOT PATH: per finger MotionEvent; no allocation.
        val tracking = fingerPointer >= 0
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                down(event.x, event.y, finger = true).also { if (it) fingerPointer = event.getPointerId(0) }
            }

            MotionEvent.ACTION_MOVE -> {
                val i = event.findPointerIndex(fingerPointer)
                if (tracking && i >= 0) move(event.getX(i), event.getY(i))
                tracking
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (tracking) {
                    fingerPointer = -1
                    if (event.actionMasked == MotionEvent.ACTION_UP) up(event.x, event.y) else cancel()
                }
                tracking
            }

            else -> {
                tracking
            }
        }
    }

    // A pen or finger down at view ([xPx], [yPx]); true when the lasso takes the gesture (a finger only on its chrome).
    private fun down(
        xPx: Float,
        yPx: Float,
        finger: Boolean,
    ): Boolean {
        downXPx = xPx
        downYPx = yPx
        if (pressAt(xPx, yPx)) return true
        toolbar.closePopup()
        val grabbed = grab(xPx, yPx)
        if (!grabbed && !finger) startPath(xPx, yPx)
        return grabbed || !finger
    }

    // A down on the action bar or its popup presses it.
    private fun pressAt(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        if (!toolbar.covers(xPx, yPx)) return false
        pressedItem = toolbar.itemAt(xPx, yPx)
        pressed = if (pressedItem >= 0) null else toolbar.buttonAt(xPx, yPx)
        phase = Phase.PRESS
        return true
    }

    // A down inside the selection box or on a handle starts a drag of the selection.
    private fun grab(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val sel = selection ?: return false
        if (pending != null) {
            // The committed move is still on its way: the gesture waits it out; once it arrived the next drag starts.
            if (objectsOn(sel.pageId) === pendingBefore) return swallow()
            pending = null
            pendingBefore = null
            snapshot = null
            boxObjects = null
        }
        return startDrag(sel, xPx, yPx)
    }

    // Starts dragging [sel] when ([xPx], [yPx]) lies inside its box or on a handle.
    private fun startDrag(
        sel: LassoSelection,
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val box = box() ?: return false
        val page = indexOf(sel.pageId)
        if (page < 0) return false
        val x = toPageX(page, xPx)
        val y = toPageY(page, yPx)
        val p = PointPt(x, y)
        val handle = SelectionBox.handleAt(box, p, sizes.handleRadiusPx / viewport.scale, rotateOffsetPt())
        val hit = handle != null || box.contains(p)
        if (hit) {
            dragHandle = handle
            dragBox = box
            fromX = x
            fromY = y
            toX = x
            toY = y
            phase = Phase.DRAG
            snapshot = host.snapshot(sel, box)
            host.hide(sel)
            host.invalidate()
        }
        return hit
    }

    // Takes a gesture that does nothing.
    private fun swallow(): Boolean {
        pressed = null
        pressedItem = -1
        phase = Phase.PRESS
        return true
    }

    private fun startPath(
        xPx: Float,
        yPx: Float,
    ) {
        val page = viewport.pageIndexAt(yPx)
        if (pageIdAt(page) == null) return
        pasteAt = null
        rectangle = spec().rectangle
        pathPage = page
        pathSize = 0
        tapping = true
        phase = Phase.PATH
        add(toPageX(page, xPx), toPageY(page, yPx))
        host.invalidate()
    }

    private fun move(
        xPx: Float,
        yPx: Float,
    ) {
        // HOT PATH: per sample; no allocation unless the path array grows.
        when (phase) {
            Phase.PATH -> {
                if (tapping && hypot(xPx - downXPx, yPx - downYPx) >= sizes.tapSlopPx) tapping = false
                val x = toPageX(pathPage, xPx)
                val y = toPageY(pathPage, yPx)
                if (rectangle) {
                    if (pathSize < 2) add(x, y) else setLast(x, y)
                } else if (hypot(x - xs[pathSize - 1], y - ys[pathSize - 1]) * viewport.scale >= MIN_STEP_PX) {
                    add(x, y)
                }
                host.invalidate()
            }

            Phase.DRAG -> {
                val page = selectionPage
                if (page < 0) return
                toX = toPageX(page, xPx)
                toY = toPageY(page, yPx)
                host.invalidate()
            }

            Phase.IDLE, Phase.PRESS -> {
                Unit
            }
        }
    }

    private fun up(
        xPx: Float,
        yPx: Float,
    ) {
        when (phase) {
            Phase.PATH -> finishPath(xPx, yPx)
            Phase.DRAG -> finishDrag()
            Phase.PRESS -> release(xPx, yPx)
            Phase.IDLE -> Unit
        }
        phase = Phase.IDLE
        host.invalidate()
    }

    /** Drops a lasso being drawn and a drag (the gesture was canceled). */
    fun cancel() {
        if (phase == Phase.DRAG) {
            snapshot = null
            host.hide(null)
        }
        phase = Phase.IDLE
        pathPage = -1
        pathSize = 0
        pressed = null
        host.invalidate()
    }

    /** Drops everything: path, selection, Paste pill, pending transform (another tool, a rejected commit). */
    fun clear() {
        cancel()
        selection = null
        pasteAt = null
        pending = null
        pendingBefore = null
        snapshot = null
        boxObjects = null
        boxCache = null
        toolbar.clear()
        host.hide(null)
        host.invalidate()
    }

    private fun finishPath(
        xPx: Float,
        yPx: Float,
    ) {
        val page = pathPage
        pathPage = -1
        val pageId = pageIdAt(page) ?: return
        if (tapping) {
            tap(pageId, PointPt(toPageX(page, xPx), toPageY(page, yPx)))
            return
        }
        val objects = objectsOn(pageId).orEmpty()
        val kinds = spec().kinds
        val ids =
            if (rectangle) {
                Lasso.select(objects, Lasso.rectangle(PointPt(xs[0], ys[0]), PointPt(xs[pathSize - 1], ys[pathSize - 1])), kinds)
            } else {
                Lasso.select(objects, xs, ys, pathSize, kinds)
            }
        select(if (ids.isEmpty()) null else LassoSelection(pageId, ids))
    }

    // A tap beside the selection drops it; with nothing selected and a filled clipboard it offers Paste there.
    private fun tap(
        pageId: PageId,
        at: PointPt,
    ) {
        if (selection != null || pasteAt != null) {
            select(null)
            pasteAt = null
            toolbar.clear()
        } else if (clipboard().objects.isNotEmpty()) {
            pasteAt = pageId to at
        }
    }

    /** Selects [next] (null: nothing). */
    fun select(next: LassoSelection?) {
        selection = next
        boxObjects = null
        boxCache = null
        if (next == null) toolbar.clear()
        host.invalidate()
    }

    private fun finishDrag() {
        val sel = selection ?: return cancel()
        val affine = dragAffine()
        if (affine == Affine.IDENTITY || (fromX == toX && fromY == toY)) {
            snapshot = null
            host.hide(null)
            return
        }
        pending = affine
        pendingBox = dragBox
        pendingBefore = objectsOn(sel.pageId)
        host.edit(sel, SelectionEdit.Transform(affine)) { accepted ->
            if (!accepted) {
                pending = null
                snapshot = null
                host.hide(null)
                host.invalidate()
            } else {
                check()
            }
        }
    }

    /**
     * The document or the tiles changed: a pending transform ends once its page shows the committed objects and the tiles
     * under the moved box are drawn.
     */
    fun check() {
        val affine = pending ?: return
        val sel = selection ?: return
        if (objectsOn(sel.pageId) === pendingBefore) return
        if (!host.isDrawn(sel.pageId, affine.mapRect(pendingBox).inset(-DRAWN_MARGIN_PT))) return
        pending = null
        pendingBefore = null
        snapshot = null
        boxObjects = null
        host.invalidate()
    }

    // A press on the action bar lifted at view ([xPx], [yPx]): runs the button or popup item still under it.
    private fun release(
        xPx: Float,
        yPx: Float,
    ) {
        val item = toolbar.itemAt(xPx, yPx)
        val button = toolbar.buttonAt(xPx, yPx)
        val kind = toolbar.popupKind
        when {
            pressedItem >= 0 && item == pressedItem && kind != null -> popupItem(kind, item)
            button != null && button == pressed -> press(button)
        }
        pressed = null
        pressedItem = -1
    }

    private fun press(button: LassoButton) {
        when (button) {
            LassoButton.CROP -> {
                val sel = selection ?: return
                image?.let { host.editImage(sel.pageId, it.id) }
            }

            LassoButton.CUT -> {
                copy()
                editSelection(SelectionEdit.Delete, deselect = true)
            }

            LassoButton.COPY -> {
                copy()
            }

            LassoButton.DUPLICATE -> {
                val sel = selection ?: return
                val o = ObjectCopies.DUPLICATE_OFFSET_PT
                add(sel.pageId, ObjectCopies.of(selectedObjects(), Affine.translate(o, o)))
            }

            LassoButton.DELETE -> {
                editSelection(SelectionEdit.Delete, deselect = true)
            }

            LassoButton.COLOR -> {
                toggle(LassoPopup.COLOR)
            }

            LassoButton.ARRANGE -> {
                toggle(LassoPopup.ARRANGE)
            }

            LassoButton.PASTE -> {
                paste()
            }
        }
    }

    private fun toggle(kind: LassoPopup) {
        if (toolbar.popupKind == kind) {
            toolbar.closePopup()
        } else {
            toolbar.placePopup(kind, viewport.viewWidthPx, viewport.viewHeightPx)
        }
    }

    private fun popupItem(
        kind: LassoPopup,
        item: Int,
    ) {
        when (kind) {
            LassoPopup.COLOR -> {
                LassoToolbar.colorOf(item)?.let { editSelection(SelectionEdit.Recolor(it), deselect = false) }
                toolbar.closePopup()
            }

            LassoPopup.ARRANGE -> {
                LassoToolbar.reorderOf(item)?.let { editSelection(SelectionEdit.Reorder(it), deselect = false) }
            }
        }
    }

    private fun selectedObjects(): List<PageObject> {
        val sel = selection ?: return emptyList()
        val ids = sel.ids.toHashSet()
        return objectsOn(sel.pageId).orEmpty().filter { it.id in ids }
    }

    private fun copy() {
        val sel = selection ?: return
        val objects = selectedObjects()
        clipboard().put(ObjectCopies.of(objects))
        host.copyImage(sel.pageId, objects)
    }

    private fun editSelection(
        edit: SelectionEdit,
        deselect: Boolean,
    ) {
        val sel = selection ?: return
        if (deselect) select(null)
        host.edit(sel, edit) { host.invalidate() }
    }

    // Puts [objects] on page [pageId] and selects them.
    private fun add(
        pageId: PageId,
        objects: List<PageObject>,
    ) {
        if (objects.isEmpty()) return
        host.edit(LassoSelection(pageId, emptyList()), SelectionEdit.Add(objects)) { accepted ->
            if (accepted) select(LassoSelection(pageId, objects.map { it.id }))
        }
    }

    // The clipboard with its bounds centered on the Paste point (fresh ids each time, so it pastes again).
    private fun paste() {
        val (pageId, at) = pasteAt ?: return
        pasteAt = null
        toolbar.clear()
        val objects = clipboard().objects
        val c = ObjectCopies.bounds(objects).center
        add(pageId, ObjectCopies.of(objects, Affine.translate(at.x - c.x, at.y - c.y)))
    }

    private fun add(
        x: Float,
        y: Float,
    ) {
        if (pathSize == xs.size) {
            xs = xs.copyOf(xs.size * 2)
            ys = ys.copyOf(ys.size * 2)
        }
        xs[pathSize] = x
        ys[pathSize] = y
        pathSize++
    }

    private fun setLast(
        x: Float,
        y: Float,
    ) {
        xs[pathSize - 1] = x
        ys[pathSize - 1] = y
    }

    private fun toPageX(
        page: Int,
        xPx: Float,
    ): Float = ((xPx - viewport.pageOriginViewX(page)) / viewport.scale).toFloat()

    private fun toPageY(
        page: Int,
        yPx: Float,
    ): Float = ((yPx - viewport.pageOriginViewY(page)) / viewport.scale).toFloat()

    private companion object {
        const val INITIAL_POINTS = 256

        // Lasso points closer than this on screen are dropped.
        const val MIN_STEP_PX = 2f

        // Margin around the moved box whose tiles must be drawn (stroke widths, rounding).
        const val DRAWN_MARGIN_PT = 2f
    }
}
