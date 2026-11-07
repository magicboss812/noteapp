package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread

/** Pointer downs and taps the canvas host saw since it was created (debug automation finds dropped input, P06-T18). */
@MainThread
class TouchCounts internal constructor(
    private val caretTapCount: () -> Int,
) {
    /** Finger (or mouse) gestures that reached the host. */
    var fingerDowns = 0
        private set

    /** Stylus or eraser gestures that reached the host. */
    var stylusDowns = 0
        private set

    /** Finger taps the gestures reported. */
    var fingerTaps = 0
        internal set

    /** Taps (finger or Text tool pen) that asked the text controller for a caret. */
    val caretTaps: Int get() = caretTapCount()

    internal fun down(event: MotionEvent) {
        // HOT PATH: per gesture start; two compares.
        val tool = event.getToolType(0)
        if (tool == MotionEvent.TOOL_TYPE_STYLUS || tool == MotionEvent.TOOL_TYPE_ERASER) stylusDowns++ else fingerDowns++
    }
}
