package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import androidx.ink.authoring.InProgressStrokeId
import androidx.ink.authoring.InProgressStrokesView
import androidx.ink.brush.Brush
import dev.folio.core.ink.brush.BrushCatalog
import dev.folio.core.ink.brush.BrushTextures
import dev.folio.core.model.BrushSpec

/**
 * Where wet strokes draw (06-ink-input.md#wet-ink): androidx.ink's [InProgressStrokesView] on the
 * device ([InkWetSurface]), a recorder in tests. One stroke at a time; stroke coordinates are page
 * points and [start]'s `strokeToView` maps them to view px.
 */
@MainThread
internal interface WetSurface {
    /** The wet layer, stacked on top of the canvas. */
    val view: View

    /** Starts a stroke for pointer [pointerId] of [event] with [spec] (tilt behaviors when [tilt]). */
    fun start(
        event: MotionEvent,
        pointerId: Int,
        spec: BrushSpec,
        tilt: Boolean,
        strokeToView: Matrix,
    )

    /** Adds [event]'s samples (historical included) of [pointerId] to the stroke. */
    fun add(
        event: MotionEvent,
        pointerId: Int,
    )

    /** Ends the stroke with [event]; the stroke stays visible. */
    fun finish(
        event: MotionEvent,
        pointerId: Int,
    )

    /** Discards the stroke. */
    fun cancel(event: MotionEvent)
}

/** [WetSurface] on androidx.ink: front-buffered wet strokes with catalog brushes and their textures. */
internal class InkWetSurface(
    context: Context,
    private val catalog: BrushCatalog = BrushCatalog.DEFAULT,
) : WetSurface {
    private val ink = InProgressStrokesView(context).apply { textureBitmapStore = BrushTextures }
    private val identity = Matrix()
    private var strokeId: InProgressStrokeId? = null
    private var brushSpec: BrushSpec? = null
    private var brushTilt = false
    private var brush: Brush? = null

    override val view: View get() = ink

    override fun start(
        event: MotionEvent,
        pointerId: Int,
        spec: BrushSpec,
        tilt: Boolean,
        strokeToView: Matrix,
    ) {
        // HOT PATH: per stroke; the brush is rebuilt only when the spec changes.
        strokeId?.let { ink.cancelStroke(it, event) }
        strokeId = ink.startStroke(event, pointerId, brushFor(spec, tilt), identity, strokeToView)
    }

    override fun add(
        event: MotionEvent,
        pointerId: Int,
    ) {
        // HOT PATH: per MotionEvent.
        val id = strokeId ?: return
        ink.addToStroke(event, pointerId, id)
    }

    override fun finish(
        event: MotionEvent,
        pointerId: Int,
    ) {
        val id = strokeId ?: return
        ink.finishStroke(event, pointerId, id)
        strokeId = null
    }

    override fun cancel(event: MotionEvent) {
        val id = strokeId ?: return
        ink.cancelStroke(id, event)
        strokeId = null
    }

    private fun brushFor(
        spec: BrushSpec,
        tilt: Boolean,
    ): Brush {
        val cached = brush
        if (cached != null && spec == brushSpec && tilt == brushTilt) return cached
        return catalog.brush(spec, tilt).also {
            brush = it
            brushSpec = spec
            brushTilt = tilt
        }
    }
}
