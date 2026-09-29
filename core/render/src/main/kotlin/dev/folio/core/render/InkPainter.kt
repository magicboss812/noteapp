package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.annotation.WorkerThread
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.folio.core.common.FolioLog
import dev.folio.core.ink.StrokeBuilder
import dev.folio.core.model.InkStroke

/** Paints committed ink strokes for [PageRenderer]. One instance per drawing thread. */
interface InkPainter {
    /**
     * Draws [stroke] (object [index] of [content]) onto [canvas], whose matrix already maps page pt to
     * device px; [toDevice] is that matrix. Implementations may cache meshes in [PageContent.meshSlot].
     */
    @WorkerThread
    fun draw(
        canvas: Canvas,
        stroke: InkStroke,
        content: PageContent,
        index: Int,
        toDevice: Matrix,
    )

    /** Builds what [draw] would cache for [stroke] (object [index] of [content]) without drawing. */
    @WorkerThread
    fun prepare(
        stroke: InkStroke,
        content: PageContent,
        index: Int,
    ) = Unit
}

/** androidx.ink [CanvasStrokeRenderer] with meshes from [StrokeBuilder], cached per page snapshot. */
class AndroidInkPainter : InkPainter {
    // Created on first use: background-only renderers (and JVM tests) never load the ink natives.
    private val renderer by lazy(LazyThreadSafetyMode.NONE) { CanvasStrokeRenderer.create() }

    override fun draw(
        canvas: Canvas,
        stroke: InkStroke,
        content: PageContent,
        index: Int,
        toDevice: Matrix,
    ) {
        val mesh = mesh(stroke, content, index) ?: return
        renderer.draw(canvas, mesh, toDevice)
    }

    override fun prepare(
        stroke: InkStroke,
        content: PageContent,
        index: Int,
    ) {
        mesh(stroke, content, index)
    }

    private fun mesh(
        stroke: InkStroke,
        content: PageContent,
        index: Int,
    ): Stroke? =
        when (val cached = content.meshSlot(index)) {
            is Stroke -> cached
            null -> build(stroke).also { content.setMeshSlot(index, it ?: UNDRAWABLE) }
            else -> null // UNDRAWABLE
        }

    private fun build(stroke: InkStroke): Stroke? =
        try {
            StrokeBuilder.build(stroke)
        } catch (e: IllegalArgumentException) {
            // Inputs androidx.ink rejects (a hostile or damaged file): skip the stroke, keep the page.
            FolioLog.w(TAG, "stroke ${stroke.id.value} not drawable: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "InkPainter"

        /** Slot marker for strokes androidx.ink rejected, so they are not rebuilt on every tile. */
        val UNDRAWABLE = Any()
    }
}
