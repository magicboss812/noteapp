package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import dev.folio.core.model.BrushSpec

/** Records wet-ink calls; stands in for androidx.ink (which needs native code and a GPU surface). */
internal class FakeWetSurface(
    context: Context,
) : WetSurface {
    override val view = View(context)
    val calls = ArrayList<String>()
    var lastSpec: BrushSpec? = null
    var lastTilt: Boolean? = null
    val lastStrokeToView = Matrix()

    override fun start(
        event: MotionEvent,
        pointerId: Int,
        spec: BrushSpec,
        tilt: Boolean,
        strokeToView: Matrix,
    ) {
        calls += "start $pointerId"
        lastSpec = spec
        lastTilt = tilt
        lastStrokeToView.set(strokeToView)
    }

    override fun add(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "add $pointerId"
    }

    override fun finish(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "finish $pointerId"
    }

    override fun cancel(event: MotionEvent) {
        calls += "cancel"
    }
}
