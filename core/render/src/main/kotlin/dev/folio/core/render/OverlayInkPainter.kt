package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.Matrix
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.Stroke
import dev.folio.core.common.FolioLog
import dev.folio.core.ink.StrokeBuilder
import dev.folio.core.ink.brush.BrushTextures
import dev.folio.core.model.InkStroke

/**
 * Draws single ink strokes outside the tiles (canvas overlays, such as a snapped straight highlighter line) with the
 * tiles' meshes and brushes, so a hand-over to the tiles changes no pixel. Keeps the mesh of the last stroke drawn.
 */
class OverlayInkPainter {
    // Created on first use: overlays that never draw a stroke never load the ink natives.
    private val renderer by lazy(LazyThreadSafetyMode.NONE) { CanvasStrokeRenderer.create(BrushTextures) }
    private var lastStroke: InkStroke? = null
    private var lastMesh: Stroke? = null

    /** Draws [stroke] onto [canvas], whose matrix maps page pt to view px; [toView] is that page-to-view matrix. */
    fun draw(
        canvas: Canvas,
        stroke: InkStroke,
        toView: Matrix,
    ) {
        val mesh = if (stroke === lastStroke) lastMesh else build(stroke)
        lastStroke = stroke
        lastMesh = mesh
        if (mesh != null) renderer.draw(canvas, mesh, toView)
    }

    private fun build(stroke: InkStroke): Stroke? =
        try {
            StrokeBuilder.build(stroke)
        } catch (e: IllegalArgumentException) {
            FolioLog.w(TAG, "overlay stroke not drawable: ${e.message}")
            null
        }

    private companion object {
        const val TAG = "OverlayInkPainter"
    }
}
