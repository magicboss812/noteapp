package dev.folio.feature.editor.canvas

import android.view.MotionEvent

/** What a finger gesture means for text selection ([TextTouch]); the host routes [PASS], [TAP] and [PAN] on. */
internal enum class TextGesture {
    /** Not a text gesture (yet): the input router gets the event. */
    PASS,

    /** A single tap released in place: the router's tap places the caret. */
    TAP,

    /** The finger left the touch slop: one-finger pan, the router's. */
    PAN,

    /** The gesture began on a selection handle: the selection overlay drags it. */
    HANDLE,

    /** The second tap of a double tap, at its release: select the word there. */
    DOUBLE_TAP,

    /** The host took a long-press: the word under the finger is selected. */
    LONG_PRESS,

    /** A move after a taken long-press: the selection extends by words. */
    LONG_DRAG,

    /** The finger of a taken long-press lifted (or the gesture was canceled). */
    LONG_END,

    /** An event of a taken gesture that needs nothing (another finger landing). */
    CONSUMED,
}

/**
 * Classifies one-finger gestures over the canvas for touch text selection (07-text-engine.md#selection-as-built): a
 * press held [longPressMs] inside [slopPx] is a long-press if [takesLongPress] accepts it at the press point, its
 * moves then extend; a second tap within [doubleTapMs] and [doubleTapSlopPx] of the first is a double tap; a down on
 * a handle ([handleAt]) belongs to the handle until the finger lifts; anything else passes to the router. Stylus and
 * multi-finger gestures always pass. The host runs [longPressDue] from a timer [longPressMs] after the down.
 */
internal class TextTouch(
    private val slopPx: Float,
    private val longPressMs: Long,
    private val doubleTapMs: Long,
    private val doubleTapSlopPx: Float,
    private val handleAt: (xPx: Float, yPx: Float) -> Boolean,
    private val takesLongPress: (xPx: Float, yPx: Float) -> Boolean,
) {
    private var state = IDLE
    private var downXPx = 0f
    private var downYPx = 0f
    private var downTimeMs = 0L
    private var secondTap = false
    private var lastTapUpMs = NO_TAP
    private var lastTapXPx = 0f
    private var lastTapYPx = 0f

    /** True while a press could still become a long-press (the host's timer then calls [longPressDue]). */
    val isPressing: Boolean get() = state == PRESS

    /** True from a taken long-press until its finger lifts. */
    val isLongPressing: Boolean get() = state == LONG

    /** Classifies [event]; [enabled] false (read mode, no text) passes every gesture that starts now. */
    fun onTouch(
        event: MotionEvent,
        enabled: Boolean = true,
    ): TextGesture {
        // HOT PATH: per MotionEvent; fields only, no allocation.
        return when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event, enabled)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown()
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> end(TextGesture.PASS)
            else -> current()
        }
    }

    /** The long-press timer at [nowMs]: [TextGesture.LONG_PRESS] if the press became one, else [TextGesture.PASS]. */
    fun longPressDue(nowMs: Long): TextGesture {
        if (state != PRESS || nowMs - downTimeMs < longPressMs) return TextGesture.PASS
        if (!takesLongPress(downXPx, downYPx)) {
            state = OFF
            return TextGesture.PASS
        }
        state = LONG
        lastTapUpMs = NO_TAP
        return TextGesture.LONG_PRESS
    }

    private fun onDown(
        event: MotionEvent,
        enabled: Boolean,
    ): TextGesture {
        val finger = event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
        downXPx = event.x
        downYPx = event.y
        downTimeMs = event.eventTime
        state =
            when {
                !enabled || !finger -> OFF
                handleAt(event.x, event.y) -> HANDLE_DRAG
                else -> PRESS
            }
        when (state) {
            OFF -> {
                lastTapUpMs = NO_TAP
            }

            PRESS -> {
                secondTap =
                    event.eventTime - lastTapUpMs <= doubleTapMs && near(lastTapXPx, lastTapYPx, event.x, event.y, doubleTapSlopPx)
            }
        }
        return current()
    }

    private fun onPointerDown(): TextGesture {
        if (state == PRESS) state = OFF
        lastTapUpMs = NO_TAP
        return current()
    }

    private fun onMove(event: MotionEvent): TextGesture {
        if (state == PRESS && !near(downXPx, downYPx, event.x, event.y, slopPx)) {
            state = OFF
            lastTapUpMs = NO_TAP
            return TextGesture.PAN
        }
        if (state == PRESS) return longPressDue(event.eventTime)
        return if (state == LONG) TextGesture.LONG_DRAG else current()
    }

    private fun onUp(event: MotionEvent): TextGesture {
        if (state != PRESS) return end(current())
        if (event.eventTime - downTimeMs >= longPressMs) return end(TextGesture.PASS)
        if (secondTap) {
            lastTapUpMs = NO_TAP
            return end(TextGesture.DOUBLE_TAP)
        }
        lastTapUpMs = event.eventTime
        lastTapXPx = event.x
        lastTapYPx = event.y
        return end(TextGesture.TAP)
    }

    // What the rest of the current gesture is.
    private fun current(): TextGesture =
        when (state) {
            HANDLE_DRAG -> TextGesture.HANDLE
            LONG -> TextGesture.CONSUMED
            else -> TextGesture.PASS
        }

    private fun end(gesture: TextGesture): TextGesture {
        val result = if (state == LONG) TextGesture.LONG_END else gesture
        state = IDLE
        return result
    }

    private fun near(
        ax: Float,
        ay: Float,
        bx: Float,
        by: Float,
        reach: Float,
    ): Boolean {
        val dx = bx - ax
        val dy = by - ay
        return dx * dx + dy * dy <= reach * reach
    }

    private companion object {
        const val IDLE = 0
        const val PRESS = 1
        const val OFF = 2
        const val HANDLE_DRAG = 3
        const val LONG = 4
        const val NO_TAP = Long.MIN_VALUE / 2
    }
}
