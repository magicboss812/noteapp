package dev.folio.core.render

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.shape.ShapeGeometry

/**
 * Paints [Shape]s in page pt (06-ink-input.md#shapes): fill under the outline, round joins and caps,
 * dashes of 3 x 2 outline widths, open arrow heads. Shared by tiles, export and the editor's live
 * preview. Not thread-safe: one instance per drawing thread.
 */
class ShapePainter {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }
    private val path = Path()
    private val oval = RectF()
    private var dash: DashPathEffect? = null
    private var dashWidthPt = Float.NaN

    /** Draws [shape] into [canvas], whose matrix maps page pt to device pixels. */
    fun draw(
        canvas: Canvas,
        shape: Shape,
    ) {
        val pts = shape.points
        if (pts.size < 2) return
        val pivot = ShapeGeometry.pivot(pts)
        val rotated = shape.rotationDeg != 0f
        if (rotated) {
            canvas.save()
            canvas.rotate(shape.rotationDeg, pivot.x, pivot.y)
        }
        buildPath(shape)
        val fillArgb = shape.fillArgb
        if (fillArgb != null) {
            fill.color = fillArgb
            if (shape.kind == ShapeKind.ELLIPSE) canvas.drawOval(oval, fill) else canvas.drawPath(path, fill)
        }
        outline.color = shape.stroke.argb
        outline.strokeWidth = shape.stroke.widthPt
        outline.pathEffect = if (shape.stroke.dashed) dashFor(shape.stroke.widthPt) else null
        if (shape.kind == ShapeKind.ELLIPSE) canvas.drawOval(oval, outline) else canvas.drawPath(path, outline)
        if (shape.kind == ShapeKind.ARROW) drawHead(canvas, shape)
        if (rotated) canvas.restore()
    }

    private fun buildPath(shape: Shape) {
        val pts = shape.points
        path.rewind()
        when (shape.kind) {
            ShapeKind.LINE, ShapeKind.ARROW -> {
                path.moveTo(pts[0].x, pts[0].y)
                path.lineTo(pts[1].x, pts[1].y)
            }

            ShapeKind.RECTANGLE -> {
                val b = RectPt.bounds(pts)
                path.addRect(b.left, b.top, b.right, b.bottom, Path.Direction.CW)
            }

            ShapeKind.ELLIPSE -> {
                val b = RectPt.bounds(pts)
                oval.set(b.left, b.top, b.right, b.bottom)
            }

            ShapeKind.TRIANGLE, ShapeKind.POLYGON -> {
                path.moveTo(pts[0].x, pts[0].y)
                for (i in 1 until pts.size) path.lineTo(pts[i].x, pts[i].y)
                path.close()
            }
        }
    }

    private fun drawHead(
        canvas: Canvas,
        shape: Shape,
    ) {
        val tip = shape.points[1]
        val (left, right) = ShapeGeometry.arrowHead(shape.points[0], tip, shape.stroke.widthPt)
        outline.pathEffect = null // a dashed head would vanish at small sizes
        path.rewind()
        path.moveTo(left.x, left.y)
        path.lineTo(tip.x, tip.y)
        path.lineTo(right.x, right.y)
        canvas.drawPath(path, outline)
    }

    private fun dashFor(widthPt: Float): DashPathEffect {
        val cached = dash
        if (cached != null && dashWidthPt == widthPt) return cached
        val on = widthPt * DASH_ON_WIDTHS
        // Round caps add half a width at each end of a dash: widen the gap by one width to keep it visible.
        val off = widthPt * (DASH_OFF_WIDTHS + 1f)
        return DashPathEffect(floatArrayOf(on, off), 0f).also {
            dash = it
            dashWidthPt = widthPt
        }
    }

    private companion object {
        const val DASH_ON_WIDTHS = 3f
        const val DASH_OFF_WIDTHS = 2f
    }
}
