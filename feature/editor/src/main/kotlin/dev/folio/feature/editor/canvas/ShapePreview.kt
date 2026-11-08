package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.folio.core.model.Shape
import dev.folio.core.render.ShapePainter
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageOriginViewX
import dev.folio.core.render.viewport.pageOriginViewY

/**
 * Draws the Shape tool's live shape and polygon vertices over the content tiles (05-canvas-rendering.md
 * overlay: shape preview), plus a just committed shape until its tiles show it. Same painter as the tiles,
 * so the handoff does not change a pixel.
 */
internal class ShapePreview(
    context: Context,
    private val viewport: Viewport,
) : View(context) {
    private val painter = ShapePainter()
    private val vertexPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val vertexRadiusPx = VERTEX_RADIUS_DP * resources.displayMetrics.density

    /** Where the shapes come from; set once by the host. */
    var input: ShapeInput? = null

    /** The draft drawn by the last frame (tests read it). */
    var shown: Shape? = null
        private set

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val source = input ?: return
        source.pending?.let { draw(canvas, it.pageIndex, it.shape) }
        val page = source.draftPage
        val draft = if (page >= 0) source.draft() else null
        shown = draft
        if (page < 0) return
        if (draft != null) draw(canvas, page, draft)
        val vertices = source.polygonVertices
        if (vertices.isEmpty()) return
        val x0 = viewport.pageOriginViewX(page).toFloat()
        val y0 = viewport.pageOriginViewY(page).toFloat()
        vertexPaint.color = draft?.stroke?.argb ?: VERTEX_ARGB
        for (v in vertices) canvas.drawCircle(x0 + v.x * viewport.scale, y0 + v.y * viewport.scale, vertexRadiusPx, vertexPaint)
    }

    private fun draw(
        canvas: Canvas,
        page: Int,
        shape: Shape,
    ) {
        canvas.save()
        canvas.translate(viewport.pageOriginViewX(page).toFloat(), viewport.pageOriginViewY(page).toFloat())
        canvas.scale(viewport.scale, viewport.scale)
        painter.draw(canvas, shape)
        canvas.restore()
    }

    private companion object {
        const val VERTEX_RADIUS_DP = 3f
        const val VERTEX_ARGB = 0xFF1A1A1A.toInt()
    }
}
