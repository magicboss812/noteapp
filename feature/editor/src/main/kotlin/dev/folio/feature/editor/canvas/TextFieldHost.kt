package dev.folio.feature.editor.canvas

import android.content.Context
import android.text.TextUtils
import android.view.View
import android.widget.FrameLayout
import androidx.annotation.MainThread
import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.tiles.TileLayer
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.text.flow.EditedBlock
import dev.folio.feature.editor.text.TextFieldSpec
import kotlin.math.max
import kotlin.math.min

/**
 * Keeps the live text field ([LiveTextField]) in the canvas [overlay]: created with the first block that gets focus,
 * shown for the [TextFieldSpec] it is given, moved with [viewport], hidden when the spec is null or its page is not
 * laid out. Edits go to [controller]'s text controller; Ctrl and Alt chords go to [onShortcut]. A [TextHandoff]
 * switches blocks between the tiles and the field: [isDrawn] tells when the content tiles under a block are fresh,
 * [invalidateTiles] redraws them in the run that shows or hides the field.
 */
@MainThread
internal class TextFieldHost(
    private val context: Context,
    private val overlay: FrameLayout,
    private val viewport: Viewport,
    private val controller: CanvasController,
    isDrawn: (TextFieldSpec) -> Boolean,
    private val invalidateTiles: () -> Unit,
    private val onCaretChanged: () -> Unit,
    private val document: () -> Document?,
) {
    private var view: LiveTextField? = null
    private var edited: EditedTextView? = null
    private var ghost: TextGhost? = null

    // The block laid out from the field text before the model answered for it, and the shown block's text color.
    private var typed: EditedBlock? = null
    private var textArgb = DEFAULT_TEXT_ARGB
    private var spec: TextFieldSpec? = null
    private var pageIndex = -1
    private var tapNs = 0L
    private val handoff = TextHandoff(isDrawn, ::capture, ::applyHandoff)
    private val expire = Runnable { handoff.expire() }
    private val navigation = FieldNavigation(viewport, controller) { spec }

    /** Runs the editor shortcut for a chord typed in the field (key code, ctrl, shift, alt); true if one ran. */
    var onShortcut: (Int, Boolean, Boolean, Boolean) -> Boolean = { _, _, _, _ -> false }

    /** The live field, null until [prepare] or the first focused block built it. */
    val liveField: LiveTextField? get() = view

    /** The overlay that draws the edited block, null until [prepare] (tests). */
    internal val editedView: EditedTextView? get() = edited

    /** A finger tap may focus a block now ([nowNs], System.nanoTime): the field that shows next traces tap to caret. */
    fun tapped(nowNs: Long) {
        tapNs = nowNs
        caretTaps++
    }

    /** Taps that asked for a caret so far (debug automation). */
    var caretTaps = 0
        private set

    /** Shows the field for [next], or removes it for null; call after the page text that goes with it was applied. */
    fun show(next: TextFieldSpec?) {
        val moved = next != null && next.focus.revision != spec?.focus?.revision
        spec = next
        if (moved) typed = null
        handoff.show(next)
        if (handoff.isPending) {
            overlay.removeCallbacks(expire)
            overlay.postDelayed(expire, MAX_HANDOFF_MS)
        }
        // A Text tool creation shows page text without a field first: the tap waits for the next field, for a while.
        val sinceTapNs = System.nanoTime() - tapNs
        if (moved && tapNs != 0L && sinceTapNs < MAX_TAP_TRACE_NS) {
            PerfMonitor.record(TRACE_ARRIVE, sinceTapNs)
            view?.takeIf { it.spec === next }?.traceCaretFrom(tapNs)
        }
        if (moved) tapNs = 0L
    }

    /** Content tiles arrived or were requested: a masked field or a leaving block's picture may give way now. */
    fun check() = handoff.check()

    /** True while the field is masked or a leaving block's picture is shown (tests). */
    val isHandingOff: Boolean get() = handoff.isPending

    /** The picture of the block that left the field, null when none is shown (tests). */
    internal val ghostView: View? get() = ghost?.takeIf { it.visibility == View.VISIBLE }

    /** Shows the current spec again against the current document and viewport (they changed or got laid out). */
    fun refresh() {
        val current = spec
        val index = if (current == null) -1 else indexOf(current.pageId)
        if (current == null || index < 0) {
            view?.hide()
            typed = null
            edited?.show(null, textArgb, EditedTextView.NO_CARET, withText = false)
            return
        }
        val paper = document()?.page(current.pageId)?.background?.paperArgb
        textArgb = if (paper == null) DEFAULT_TEXT_ARGB else PageRenderer.textArgb(paper)
        prepare().show(current, textArgb, viewport, index, handoff.isMasked, overlaid = current.drawnByOverlay)
        showEdited()
    }

    /**
     * Shows the edited block as the field holds it on the canvas overlay (A-058): the model's layout once it has that
     * source, before that the block laid out again from the field text, so a keystroke shows in its own frame.
     */
    private fun showEdited() {
        // HOT PATH: per keystroke and caret move; a retype allocates the new layout only when the text changed.
        val target = edited ?: return
        val field = view
        val current = spec?.takeIf { it.drawnByOverlay }
        val laidOut = current?.edited
        if (field == null || laidOut == null) {
            target.show(null, textArgb, EditedTextView.NO_CARET, withText = false)
            return
        }
        val text = field.text
        val block =
            when {
                TextUtils.equals(text, laidOut.source) -> laidOut
                typed != null && TextUtils.equals(text, typed?.source) -> typed
                else -> controller.textController?.retype(text.toString())?.also { typed = it }
            } ?: laidOut
        val start = field.selectionStart
        val end = field.selectionEnd
        val caretAt = if (start == end && end >= 0) end else EditedTextView.NO_CARET
        target.show(block, textArgb, caretAt, withText = !handoff.isMasked)
    }

    // The tiles and the field change in this run: both show in the same frame.
    private fun applyHandoff() {
        invalidateTiles()
        refresh()
        if (handoff.leaving == null) {
            ghost?.hide()
            edited?.settled()
        }
        if (!handoff.isPending) overlay.removeCallbacks(expire)
    }

    // The block that left the field stays drawn until the tiles show it: the overlay keeps its laid-out text, a table
    // cell's field is captured as a picture.
    private fun capture(leaving: TextFieldSpec) {
        val overlaid = edited?.shown
        if (leaving.drawnByOverlay && overlaid != null) {
            edited?.leave(overlaid)
            return
        }
        val field = view ?: return
        val index = indexOf(leaving.pageId)
        val picture =
            ghost ?: TextGhost(context).also {
                overlay.addView(it)
                ghost = it
            }
        picture.capture(field, viewport, index)
    }

    /**
     * A stylus about to write at ([xPx], [yPx]) (host view px) over the shown field ends the editing: HyperOS hands a
     * stylus stream over a focused editor to its handwriting service and cancels our stroke, whatever the editor
     * declares (A-045). Hidden at once, so a hover clears the way before the pen touches. True if it ended the editing.
     */
    fun yieldToStylus(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        // HOT PATH: per stylus hover event and stylus down; a bounds check, no allocation.
        val field = view ?: return false
        if (spec == null || field.visibility != View.VISIBLE) return false
        if (!field.covers(xPx, yPx)) return false
        spec = null
        handoff.show(null) // hides the field; its block stays as a picture until the tiles draw it
        controller.textController?.blur()
        return true
    }

    /** Moves the field with the viewport. */
    fun place() {
        // HOT PATH: per viewport change; no allocation.
        ghost?.place(viewport)
        edited?.invalidate()
        val current = spec ?: return
        val index = indexOf(current.pageId)
        if (index >= 0) view?.place(viewport, index)
    }

    /**
     * The live field, created hidden on the first call: the host calls it once the main thread is idle after attach,
     * so the first tap that focuses a block does not pay for building the EditText (P06-T18).
     */
    fun prepare(): LiveTextField =
        view ?: LiveTextField(context).also {
            // Under the field, which draws nothing while the overlay draws its block.
            edited = EditedTextView(context, viewport, ::indexOf).also(overlay::addView)
            it.onEdit = { edit ->
                controller.textController?.edit(edit)
                showEdited()
            }
            it.onSelection = { start, end, extending ->
                controller.textController?.select(start, end, it.spec?.cell?.cell, extending)
                showEdited()
            }
            it.onShortcut = { keyCode, ctrl, shift, alt -> onShortcut(keyCode, ctrl, shift, alt) }
            it.onEscape = { controller.textController?.blur() }
            it.onKeyboardHidden = { controller.textController?.blur() }
            it.onVertical = navigation::moveVertically
            it.onExtend = navigation::extend
            it.onSelectAll = { controller.textController?.selectAll() }
            it.onCopy = { cut -> controller.textController?.let { texts -> if (cut) texts.cutSelection() else texts.copySelection() } }
            it.onCaretChanged = onCaretChanged
            overlay.addView(it)
            view = it
        }

    private fun indexOf(pageId: PageId): Int {
        if (viewport.layout == null && viewport.mode !is ViewportMode.Canvas) return -1
        val pages = document()?.pages ?: return -1
        if (pages.getOrNull(pageIndex)?.id == pageId) return pageIndex
        pageIndex = pages.indexOfFirst { it.id == pageId }
        return pageIndex
    }

    companion object {
        /** From a tap that may focus until the canvas gets the field for it (the rest of `text:caret` is the frame). */
        const val TRACE_ARRIVE = "text:arrive"

        private const val DEFAULT_TEXT_ARGB = 0xFF1B1D22.toInt()

        // Longest wait for the tiles before the field shows its text and the picture goes anyway.
        private const val MAX_HANDOFF_MS = 300L

        // A field that shows later than this after a tap did not come from it.
        private const val MAX_TAP_TRACE_NS = 1_000_000_000L
    }
}

// The overlay draws the block from its source layout; a table cell's field draws the cell itself.
private val TextFieldSpec.drawnByOverlay: Boolean get() = edited != null && cell == null

/**
 * Puts the top and bottom of the caret's line (host view px) into [out]: where the overlay draws it when it draws the
 * block (a paragraph continued on the next page has its caret there), else the field's own. False without a shown field.
 */
internal fun TextFieldHost.caretLinePx(out: FloatArray): Boolean {
    val field = liveField ?: return false
    val shown = field.spec
    if (field.visibility != View.VISIBLE || shown == null) return false
    val overlaid = shown.drawnByOverlay && editedView?.linePx(field.selectionEnd.coerceAtLeast(0), out) == true
    if (!overlaid) {
        out[0] = field.caretLineTopPx()
        out[1] = field.caretLineBottomPx()
    }
    return true
}

/** The field and overlay state in one line (debug automation): selection, overlay caret, blink, block, caret spot. */
internal fun TextFieldHost.describe(): String {
    val field = liveField
    val overlay = editedView
    val block = overlay?.shown
    val caret = overlay?.caretOffset ?: EditedTextView.NO_CARET
    val spot = if (caret >= 0) block?.caretSpot(caret) else null
    return "field=${field?.visibility} spec=${field?.spec != null} overlaid=${field?.spec?.drawnByOverlay} " +
        "sel=${field?.selectionStart}..${field?.selectionEnd} len=${field?.text?.length} " +
        "block=${block?.source?.length} pieces=${block?.pieces?.size} lines=${block?.layout?.lineCount} " +
        "caret=$caret blink=${overlay?.blinkOn} text=${overlay?.drawsText} left=${overlay?.left != null} " +
        "spot=${spot?.let { "${it.pageId.value}@${it.topPt}" }} handoff=$isHandingOff"
}

// The overlay fills the host view, so the field's position in it is in host px.
private fun View.covers(
    xPx: Float,
    yPx: Float,
): Boolean = xPx >= x && xPx <= x + width && (yPx >= y && yPx <= y + height)

/**
 * True when every tile of this layer under the on-screen part of [spec]'s block (one grid unit around it for glyph
 * overhang) is fresh in a view of [widthPx] x [heightPx], so the next frame draws the block as the page text now has it.
 */
internal fun TileLayer.drawsBlockOf(
    spec: TextFieldSpec,
    viewport: Viewport,
    widthPx: Float,
    heightPx: Float,
): Boolean {
    val stack = viewport.layout ?: return true
    val page = spec.pageId
    val index = stack.indexOf(page)
    val marginPt = spec.layout.unitPt
    val left = spec.placement.leftPt - marginPt
    val top = spec.placement.topPt - marginPt
    val right = spec.placement.leftPt + spec.placement.widthPt + marginPt
    val bottom = spec.placement.topPt + spec.layout.heightPt + marginPt
    var drawn = true
    viewport.forEachVisiblePage(widthPx, heightPx) { i, _, _, l, t, r, b ->
        if (i == index) drawn = isDrawn(page.value, max(l, left), max(t, top), min(r, right), min(b, bottom), viewport.scale)
    }
    return drawn
}
