package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InputTool
import dev.folio.core.model.PageId
import dev.folio.core.model.StrokeInputs

/** Records wet-ink calls; stands in for androidx.ink (which needs native code and a GPU surface). */
internal class FakeWetSurface(
    context: Context,
) : WetSurface {
    override val view = View(context)
    override var onFinished: (List<WetStroke>) -> Unit = {}
    val calls = ArrayList<String>()
    var lastSpec: BrushSpec? = null
    var lastTilt: Boolean? = null
    var lastPage: PageId? = null
    val lastStrokeToView = Matrix()
    val removed = ArrayList<Any>()
    private var next = 0

    override fun start(
        event: MotionEvent,
        pointerId: Int,
        spec: BrushSpec,
        tilt: Boolean,
        strokeToView: Matrix,
        page: PageId,
    ) {
        calls += "start $pointerId"
        lastSpec = spec
        lastTilt = tilt
        lastPage = page
        lastStrokeToView.set(strokeToView)
    }

    /** Reports a finished stroke through [page] points ([xs], [ys]) with the last spec; returns its key. */
    fun deliver(
        page: PageId,
        xs: FloatArray,
        ys: FloatArray,
    ): Any {
        val key = "wet-${next++}"
        val inputs = StrokeInputs(xs, ys, FloatArray(xs.size) { it * 5f }, null, null, null, InputTool.STYLUS)
        onFinished(listOf(WetStroke(key, page, requireNotNull(lastSpec), inputs)))
        return key
    }

    override fun remove(keys: Collection<Any>) {
        removed.addAll(keys)
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
