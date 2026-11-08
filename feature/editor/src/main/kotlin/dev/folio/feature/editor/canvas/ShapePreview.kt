package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.folio.core.model.Shape
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.model.shape.hasBoxHandles
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
    private val knobRadiusPx = KNOB_DP * resources.displayMetrics.density
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = OUTLINE_DP * resources.displayMetrics.density
            color = ACCENT_ARGB
        }
    private val knobFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = KNOB_FILL_ARGB }

    /** Where the shapes come from; set once by the host. */
    var input: ShapeInput? = null

    /** The draft drawn by the last frame (tests read it). */
    var shown: Shape? = null
        private set

    /** The selected shape the last frame drew handles for (tests read it). */
    var selectedShown: Shape? = null
        private set

    init {
        setWillNotDraw(false)
    }

    override fun onDraw(canvas: Canvas) {
        val source = input ?: return
        source.pending?.let { draw(canvas, it.pageIndex, it.shape) }
        drawSelection(canvas, source)
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

    // The selected shape's box (unrotated bounds turned with it) and handles, screen sized; mid-drag the edited shape too.
    private fun drawSelection(
        canvas: Canvas,
        source: ShapeInput,
    ) {
        val page = source.selectionPage
        val shape = if (page >= 0) source.selected() else null
        selectedShown = shape
        if (shape == null) return
        if (source.editing) draw(canvas, page, shape)
        val x0 = viewport.pageOriginViewX(page).toFloat()
        val y0 = viewport.pageOriginViewY(page).toFloat()
        val scale = viewport.scale
        if (shape.kind.hasBoxHandles) {
            val box = RectPt.bounds(shape.points)
            val c = ShapeGeometry.pivot(shape.points)
            canvas.save()
            canvas.translate(x0, y0)
            canvas.rotate(shape.rotationDeg, c.x * scale, c.y * scale)
            canvas.drawRect(box.left * scale, box.top * scale, box.right * scale, box.bottom * scale, outlinePaint)
            canvas.restore()
        }
        for (h in source.handlesOf(shape)) {
            val x = x0 + h.position.x * scale
            val y = y0 + h.position.y * scale
            canvas.drawCircle(x, y, knobRadiusPx, knobFill)
            canvas.drawCircle(x, y, knobRadiusPx, outlinePaint)
        }
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

        // Selection chrome like the text box handles (BoxHandles).
        const val KNOB_DP = 5f
        const val OUTLINE_DP = 1.5f
        const val ACCENT_ARGB = 0xFF4B85E0.toInt()
        const val KNOB_FILL_ARGB = 0xFFFDFDFE.toInt()
    }
}
