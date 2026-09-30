package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusTarget

/**
 * Picks the stylus target per gesture: the [eraser] for the pen's eraser end (TOOL_TYPE_ERASER), when
 * [tool] is [CanvasTool.ERASER], or when [buttonErases] the down event (a held stylus button, only if
 * the stylus reports buttons; 06-ink-input.md#stylus-capabilities), else the [pen]. The whole gesture
 * stays with the target picked at down.
 */
@MainThread
internal class StylusTools(
    private val pen: StylusTarget,
    private val eraser: StylusTarget,
    private val buttonErases: (MotionEvent) -> Boolean = { false },
    private val tool: () -> CanvasTool,
) : StylusTarget {
    private var active: StylusTarget? = null

    /** True when a stylus down (or hover) [event] erases; [eraserEnd] for TOOL_TYPE_ERASER. */
    fun erases(
        event: MotionEvent,
        eraserEnd: Boolean,
    ): Boolean = eraserEnd || tool() == CanvasTool.ERASER || buttonErases(event)

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        val target = if (erases(event, eraser)) this.eraser else pen
        active = target
        target.onStylusDown(event, pointerId, eraser)
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent.
        active?.onStylusMove(event, pointerId)
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        active?.onStylusUp(event, pointerId)
        active = null
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        active?.onStylusCancel(event, pointerId)
        active = null
    }
}
