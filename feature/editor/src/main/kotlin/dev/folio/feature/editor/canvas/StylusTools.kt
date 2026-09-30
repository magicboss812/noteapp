package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.input.StylusTarget

/**
 * Picks the stylus target per gesture: the [eraser] for the pen's eraser end (TOOL_TYPE_ERASER) or when
 * [tool] is [CanvasTool.ERASER], else the [pen]. The whole gesture stays with the target picked at down.
 */
@MainThread
internal class StylusTools(
    private val pen: StylusTarget,
    private val eraser: StylusTarget,
    private val tool: () -> CanvasTool,
) : StylusTarget {
    private var active: StylusTarget? = null

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        val target = if (eraser || tool() == CanvasTool.ERASER) this.eraser else pen
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
