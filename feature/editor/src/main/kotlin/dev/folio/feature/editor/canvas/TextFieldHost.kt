package dev.folio.feature.editor.canvas

import android.content.Context
import android.widget.FrameLayout
import androidx.annotation.MainThread
import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.render.PageRenderer
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.render.viewport.toPagePt
import dev.folio.feature.editor.text.TextFieldSpec

/**
 * Keeps the live text field ([LiveTextField]) in the canvas [overlay]: created with the first block that gets focus,
 * shown for the [TextFieldSpec] it is given, moved with [viewport], hidden when the spec is null or its page is not
 * laid out. Edits go to [controller]'s text controller; Ctrl and Alt chords go to [onShortcut].
 */
@MainThread
internal class TextFieldHost(
    private val context: Context,
    private val overlay: FrameLayout,
    private val viewport: Viewport,
    private val controller: CanvasController,
    private val document: () -> Document?,
) {
    private var view: LiveTextField? = null
    private var spec: TextFieldSpec? = null
    private var pageIndex = -1

    /** Runs the editor shortcut for a chord typed in the field (key code, ctrl, shift, alt); true if one ran. */
    var onShortcut: (Int, Boolean, Boolean, Boolean) -> Boolean = { _, _, _, _ -> false }

    /** The live field, null until a block was focused for the first time. */
    val liveField: LiveTextField? get() = view

    /** Shows the field for [next], or removes it for null. */
    fun show(next: TextFieldSpec?) {
        spec = next
        refresh()
    }

    /** Shows the current spec again against the current document and viewport (they changed or got laid out). */
    fun refresh() {
        val current = spec
        val index = if (current == null) -1 else indexOf(current.pageId)
        if (current == null || index < 0) {
            view?.hide()
            return
        }
        val paper = document()?.page(current.pageId)?.background?.paperArgb
        val argb = if (paper == null) DEFAULT_TEXT_ARGB else PageRenderer.textArgb(paper)
        fieldView().show(current, argb, viewport, index)
    }

    /** Moves the field with the viewport. */
    fun place() {
        // HOT PATH: per viewport change; no allocation.
        val current = spec ?: return
        val index = indexOf(current.pageId)
        if (index >= 0) view?.place(viewport, index)
    }

    private fun fieldView(): LiveTextField =
        view ?: LiveTextField(context).also {
            it.onEdit = { edit -> controller.textController?.edit(edit) }
            it.onShortcut = { keyCode, ctrl, shift, alt -> onShortcut(keyCode, ctrl, shift, alt) }
            it.onEscape = { controller.textController?.blur() }
            it.onVertical = ::moveVertically
            overlay.addView(it)
            view = it
        }

    // Up on the first line, Down on the last: the block above or below, at the caret's column. At the edge of the text nothing moves.
    private fun moveVertically(
        up: Boolean,
        caretXPx: Float,
    ) {
        val current = spec ?: return
        val texts = controller.textController ?: return
        val xPt = viewport.toPagePt(current.pageId, PointPx(caretXPx, 0f)).x
        val unitPt = current.layout.unitPt
        val yPt = if (up) current.placement.topPt - unitPt / 2 else current.placement.topPt + current.layout.heightPt + unitPt / 2
        texts.moveFocusAt(current.pageId, xPt, yPt)
    }

    private fun indexOf(pageId: PageId): Int {
        if (viewport.layout == null && viewport.mode !is ViewportMode.Canvas) return -1
        val pages = document()?.pages ?: return -1
        if (pages.getOrNull(pageIndex)?.id == pageId) return pageIndex
        pageIndex = pages.indexOfFirst { it.id == pageId }
        return pageIndex
    }

    private companion object {
        const val DEFAULT_TEXT_ARGB = 0xFF1B1D22.toInt()
    }
}
