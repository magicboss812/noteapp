package dev.folio.app.spikes

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.annotation.WorkerThread
import androidx.ink.brush.Brush
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.rendering.android.canvas.CanvasStrokeRenderer
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Committed content of the P01-S2 spike: an A4 college-ruled page with seeded ink strokes, drawn
 * for any page region. Stands in for `PageRenderer.draw` (05-canvas-rendering.md#page-renderer).
 */
internal class SpikePage private constructor(
    private val strokes: List<Stroke>,
    private val boundsPt: FloatArray, // left, top, right, bottom per stroke
) {
    val strokeCount: Int get() = strokes.size

    /**
     * Draws paper, template and the strokes intersecting the region onto [canvas], whose matrix
     * already maps page points to device pixels; [toDevice] is that matrix, for the stroke renderer.
     * HOT PATH (render thread): no allocation.
     */
    @WorkerThread
    @Suppress("LongParameterList") // region edges as plain floats avoid a RectF per tile
    fun draw(
        canvas: Canvas,
        painter: Painter,
        leftPt: Float,
        topPt: Float,
        rightPt: Float,
        bottomPt: Float,
        toDevice: Matrix,
    ) {
        val l = max(leftPt, 0f)
        val t = max(topPt, 0f)
        val r = min(rightPt, WIDTH_PT)
        val b = min(bottomPt, HEIGHT_PT)
        if (l >= r || t >= b) return
        canvas.save()
        canvas.clipRect(l, t, r, b)
        canvas.drawRect(l, t, r, b, painter.paper)
        val first = ceil((t - RULE_TOP_PT) / RULE_SPACING_PT).toInt().coerceAtLeast(0)
        var y = RULE_TOP_PT + first * RULE_SPACING_PT
        while (y <= b) {
            canvas.drawLine(l, y, r, y, painter.rule)
            y += RULE_SPACING_PT
        }
        if (MARGIN_X_PT in l..r) canvas.drawLine(MARGIN_X_PT, t, MARGIN_X_PT, b, painter.margin)
        for (i in strokes.indices) {
            val o = i * 4
            if (boundsPt[o] > r || boundsPt[o + 2] < l || boundsPt[o + 1] > b || boundsPt[o + 3] < t) continue
            painter.renderer.draw(canvas, strokes[i], toDevice)
        }
        canvas.restore()
    }

    /** Per-thread drawing state: the ink renderer caches per-stroke data and is not shared across threads. */
    class Painter {
        val renderer: CanvasStrokeRenderer = CanvasStrokeRenderer.create()
        val matrix = Matrix()
        val paper = Paint().apply { color = Color.WHITE }
        val rule =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = RULE_ARGB.toInt()
                strokeWidth = RULE_WIDTH_PT
            }
        val margin =
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = MARGIN_ARGB.toInt()
                strokeWidth = RULE_WIDTH_PT
            }
    }

    companion object {
        const val WIDTH_PT = 595.28f
        const val HEIGHT_PT = 841.89f
        private const val MM_PT = 72f / 25.4f
        private const val RULE_SPACING_PT = 7.1f * MM_PT // college ruling (05-canvas-rendering.md#templates)
        private const val RULE_TOP_PT = 25f * MM_PT
        private const val MARGIN_X_PT = 25f * MM_PT
        private const val RULE_WIDTH_PT = 0.5f
        private const val RULE_ARGB = 0xFFC9D3E0
        private const val MARGIN_ARGB = 0xFFF2A6A6
        private const val BRUSH_EPSILON_PT = 0.05f
        private const val SAMPLE_INTERVAL_MS = 5L

        /** Builds ink strokes (meshes) for [synthetic]; takes a while for 1500 strokes. */
        @WorkerThread
        fun create(synthetic: List<SyntheticStroke>): SpikePage {
            val strokes = ArrayList<Stroke>(synthetic.size)
            val bounds = FloatArray(synthetic.size * 4)
            synthetic.forEachIndexed { i, s ->
                val stroke = s.toInk()
                val box = stroke.shape.computeBoundingBox()
                val o = i * 4
                if (box != null) {
                    bounds[o] = box.xMin
                    bounds[o + 1] = box.yMin
                    bounds[o + 2] = box.xMax
                    bounds[o + 3] = box.yMax
                }
                strokes += stroke
            }
            return SpikePage(strokes, bounds)
        }

        private fun SyntheticStroke.toInk(): Stroke {
            val family =
                when (brush) {
                    SyntheticBrush.PEN -> StockBrushes.pressurePen()
                    SyntheticBrush.MARKER -> StockBrushes.marker()
                    SyntheticBrush.HIGHLIGHTER -> StockBrushes.highlighter()
                }
            val inputs = MutableStrokeInputBatch()
            for (i in xsPt.indices) {
                inputs.add(
                    type = InputToolType.STYLUS,
                    x = xsPt[i],
                    y = ysPt[i],
                    elapsedTimeMillis = i * SAMPLE_INTERVAL_MS,
                    pressure = pressures[i],
                )
            }
            return Stroke(Brush.createWithColorIntArgb(family, argb, sizePt, BRUSH_EPSILON_PT), inputs)
        }
    }
}
