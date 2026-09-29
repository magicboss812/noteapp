package dev.folio.core.ink

import androidx.annotation.WorkerThread
import androidx.ink.brush.Brush
import androidx.ink.brush.BrushFamily
import androidx.ink.brush.InputToolType
import androidx.ink.brush.StockBrushes
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import dev.folio.core.model.BrushKind
import dev.folio.core.model.InkStroke
import kotlin.math.roundToLong

/**
 * Builds androidx.ink [Stroke]s (mesh and outlines) from stored [InkStroke]s for committed-content
 * rendering (05-canvas-rendering.md#page-renderer). Brush families are androidx.ink stock brushes
 * until `BrushCatalog` (P03-T05) maps `BrushSpec` versions to our own families.
 */
object StrokeBuilder {
    /** Brush epsilon in pt: the smallest distance the mesher distinguishes (page space). */
    const val BRUSH_EPSILON_PT = 0.05f

    /**
     * Mesh for [stroke]; meshing is the expensive part, so callers cache the result. Throws
     * IllegalArgumentException for inputs androidx.ink rejects (for example time running backwards).
     */
    @WorkerThread
    fun build(stroke: InkStroke): Stroke {
        val spec = stroke.brush
        val brush = Brush.createWithColorIntArgb(familyOf(spec.kind), spec.argb, spec.sizePt, BRUSH_EPSILON_PT)
        val inputs = stroke.inputs
        val batch = MutableStrokeInputBatch()
        val pressure = inputs.pressure
        for (i in 0 until inputs.size) {
            batch.add(
                type = InputToolType.STYLUS,
                x = inputs.x[i],
                y = inputs.y[i],
                elapsedTimeMillis = inputs.tMs[i].roundToLong(),
                pressure = pressure?.get(i)?.coerceIn(0f, 1f) ?: StrokeInput.NO_PRESSURE,
            )
        }
        return Stroke(brush, batch)
    }

    private fun familyOf(kind: BrushKind): BrushFamily =
        when (kind) {
            BrushKind.BALLPOINT, BrushKind.FOUNTAIN, BrushKind.PENCIL -> StockBrushes.pressurePen()
            BrushKind.MARKER -> StockBrushes.marker()
            BrushKind.HIGHLIGHTER -> StockBrushes.highlighter()
        }
}
