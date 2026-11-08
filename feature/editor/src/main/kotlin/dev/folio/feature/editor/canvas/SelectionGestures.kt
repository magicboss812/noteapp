package dev.folio.feature.editor.canvas

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import dev.folio.core.model.Document
import dev.folio.core.render.viewport.PointPx
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.render.viewport.toPagePt

/**
 * Finger text selection on the canvas (07-text-engine.md#selection-as-built): presses on the [selection] menu run
 * [onMenu]; otherwise [TextTouch] classifies [host]'s finger events and this acts on its gestures (handle drags,
 * long-press with its drag by words, double tap); every other event goes to [route]. [cancelNavigation] ends the pan
 * or tap a taken gesture might have become.
 */
internal class SelectionGestures(
    private val host: View,
    private val controller: CanvasController,
    private val selection: SelectionOverlay,
    private val document: () -> Document?,
    private val cancelNavigation: () -> Unit,
    private val route: (MotionEvent) -> Unit,
    onMenu: (Int) -> Unit,
) {
    init {
        selection.onMenu = onMenu
    }

    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val touch =
        ViewConfiguration.get(host.context).let { config ->
            TextTouch(
                slopPx = config.scaledTouchSlop.toFloat(),
                longPressMs = longPressMs,
                doubleTapMs = ViewConfiguration.getDoubleTapTimeout().toLong(),
                doubleTapSlopPx = config.scaledDoubleTapSlop.toFloat(),
                handleAt = selection::isOnHandle,
                takesLongPress = ::selectWordAt,
            )
        }
    private val longPress =
        Runnable {
            if (touch.longPressDue(SystemClock.uptimeMillis()) == TextGesture.LONG_PRESS) onLongPress()
        }

    /** One event that no box handle took. */
    fun onTouch(event: MotionEvent) {
        // HOT PATH: per MotionEvent.
        if (selection.menuTouch(event)) return
        val enabled = controller.activeTool != CanvasTool.NAVIGATE && controller.textController != null
        act(touch.onTouch(event, enabled), event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (touch.isPressing) host.postDelayed(longPress, longPressMs)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> host.removeCallbacks(longPress)
        }
    }

    /** The host left the window: no long-press fires later. */
    fun cancel() {
        host.removeCallbacks(longPress)
    }

    private fun act(
        gesture: TextGesture,
        event: MotionEvent,
    ) {
        when (gesture) {
            TextGesture.HANDLE -> if (!selection.handleTouch(event)) route(event)
            TextGesture.DOUBLE_TAP -> if (selectWordAt(event.x, event.y)) onWordSelected() else route(event)
            TextGesture.LONG_PRESS -> onLongPress()
            TextGesture.LONG_DRAG -> extendWordsTo(event.x, event.y)
            TextGesture.LONG_END -> selection.showMenu()
            TextGesture.CONSUMED -> Unit
            TextGesture.PASS, TextGesture.TAP, TextGesture.PAN -> route(event)
        }
    }

    // A long-press took the gesture: the pan it might have become ends, a tick confirms the word.
    private fun onLongPress() {
        host.removeCallbacks(longPress)
        cancelNavigation()
        host.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        selection.hideMenu()
    }

    private fun onWordSelected() {
        cancelNavigation()
        selection.showMenu()
    }

    // The word at view point ([xPx], [yPx]) if it is on the block being edited; true if it was selected.
    private fun selectWordAt(
        xPx: Float,
        yPx: Float,
    ): Boolean {
        val texts = controller.textController ?: return false
        val viewport = controller.viewport
        val pageId = document()?.pages?.getOrNull(viewport.pageIndexAt(yPx))?.id ?: return false
        val pt = viewport.toPagePt(pageId, PointPx(xPx, yPx))
        return texts.selectWordAt(pageId, pt.x, pt.y)
    }

    private fun extendWordsTo(
        xPx: Float,
        yPx: Float,
    ) {
        val texts = controller.textController ?: return
        val viewport = controller.viewport
        val pageId = document()?.pages?.getOrNull(viewport.pageIndexAt(yPx))?.id ?: return
        val pt = viewport.toPagePt(pageId, PointPx(xPx, yPx))
        texts.extendWordsTo(pageId, pt.x, pt.y)
    }
}
