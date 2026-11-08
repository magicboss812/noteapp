package dev.folio.feature.editor.canvas

import android.view.KeyEvent
import androidx.annotation.MainThread
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toPagePt
import dev.folio.feature.editor.text.TextFieldSpec

/** Arrow keys that leave the live field ([spec], null while none is shown): they move or extend into the flow around it. */
@MainThread
internal class FieldNavigation(
    private val viewport: Viewport,
    private val controller: CanvasController,
    private val spec: () -> TextFieldSpec?,
) {
    // Up on the first line, Down on the last: the block (or table row) above or below, at the caret's column. At the
    // edge of the text nothing moves.
    fun moveVertically(
        up: Boolean,
        caretXPx: Float,
    ) {
        val current = spec() ?: return
        val texts = controller.textController ?: return
        val (xPt, yPt) = beyond(current, up, caretXPx)
        texts.moveFocusAt(current.pageId, xPt, yPt)
    }

    // Shift+arrows past the field (07-text-engine.md#selection-as-built): Left and Right step into the next block, Up and
    // Down go to the line above or below at the caret's column.
    fun extend(
        keyCode: Int,
        caretXPx: Float,
    ) {
        val current = spec() ?: return
        val texts = controller.textController ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                texts.extendHorizontally(forward = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT)
            }

            else -> {
                val (xPt, yPt) = beyond(current, up = keyCode == KeyEvent.KEYCODE_DPAD_UP, caretXPx)
                texts.extendTo(current.pageId, xPt, yPt)
            }
        }
    }

    // The point half a grid unit above or below the field's block (or table row) at the caret's column, page pt.
    private fun beyond(
        current: TextFieldSpec,
        up: Boolean,
        caretXPx: Float,
    ): Pair<Float, Float> {
        val xPt = viewport.toPagePt(current.pageId, PointPx(caretXPx, 0f)).x
        val unitPt = current.layout.unitPt
        val cell = current.cell
        val topPt = current.placement.topPt + (cell?.topPt ?: 0f)
        val heightPt = cell?.heightPt ?: current.layout.heightPt
        return xPt to if (up) topPt - unitPt / 2 else topPt + heightPt + unitPt / 2
    }
}
