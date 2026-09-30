package dev.folio.core.ink

import androidx.annotation.WorkerThread
import androidx.ink.brush.InputToolType
import androidx.ink.strokes.MutableStrokeInputBatch
import androidx.ink.strokes.Stroke
import androidx.ink.strokes.StrokeInput
import androidx.ink.strokes.StrokeInputBatch
import dev.folio.core.ink.brush.BrushCatalog
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.StrokeInputs
import kotlin.math.PI
import kotlin.math.roundToLong

/**
 * Builds androidx.ink [Stroke]s (mesh and outlines) from stored [InkStroke]s for committed-content
 * rendering (05-canvas-rendering.md#page-renderer). Brushes come from [BrushCatalog] with the
 * stroke's stored version; tilt behaviors apply when the stored inputs carry tilt.
 */
object StrokeBuilder {
    private const val DEG_TO_RAD = (PI / 180.0).toFloat()
    private const val MAX_TILT_RAD = (PI / 2.0).toFloat()
    private const val FULL_TURN_RAD = (2.0 * PI).toFloat()

    /**
     * Mesh for [stroke]; meshing is the expensive part, so callers cache the result. Throws
     * IllegalArgumentException for inputs androidx.ink rejects (for example time running backwards).
     */
    @WorkerThread
    fun build(
        stroke: InkStroke,
        catalog: BrushCatalog = BrushCatalog.DEFAULT,
    ): Stroke {
        val inputs = stroke.inputs
        val pressure = inputs.pressure
        val tilt = inputs.tiltDeg
        val orientation = inputs.orientationDeg
        val brush = catalog.brush(stroke.brush, tilt = tilt != null)
        val batch = MutableStrokeInputBatch()
        for (i in 0 until inputs.size) {
            batch.add(
                type = InputToolType.STYLUS,
                x = inputs.x[i],
                y = inputs.y[i],
                elapsedTimeMillis = inputs.tMs[i].roundToLong(),
                pressure = pressure?.get(i)?.coerceIn(0f, 1f) ?: StrokeInput.NO_PRESSURE,
                tiltRadians = tilt?.get(i)?.let { (it * DEG_TO_RAD).coerceIn(0f, MAX_TILT_RAD) } ?: StrokeInput.NO_TILT,
                orientationRadians = orientation?.get(i)?.let { (it * DEG_TO_RAD).mod(FULL_TURN_RAD) } ?: StrokeInput.NO_ORIENTATION,
            )
        }
        return Stroke(brush, batch)
    }

    /**
     * Stored inputs of a finished wet stroke's [batch] (the reverse of [build]; 06-ink-input.md#wet-ink):
     * positions as given (page pt when the stroke was started in page space), times relative to the
     * first sample, and only the channels the batch has.
     */
    fun inputsOf(batch: StrokeInputBatch): StrokeInputs {
        val n = batch.size
        val x = FloatArray(n)
        val y = FloatArray(n)
        val tMs = FloatArray(n)
        val pressure = if (batch.hasPressure()) FloatArray(n) else null
        val tilt = if (batch.hasTilt()) FloatArray(n) else null
        val orientation = if (batch.hasOrientation()) FloatArray(n) else null
        val input = StrokeInput()
        var t0 = 0L
        for (i in 0 until n) {
            batch.populate(i, input)
            if (i == 0) t0 = input.elapsedTimeMillis
            x[i] = input.x
            y[i] = input.y
            tMs[i] = (input.elapsedTimeMillis - t0).toFloat()
            pressure?.set(i, input.pressure)
            tilt?.set(i, input.tiltRadians * RAD_TO_DEG)
            orientation?.set(i, input.orientationRadians * RAD_TO_DEG)
        }
        return StrokeInputs(x, y, tMs, pressure, tilt, orientation, InputTool.STYLUS)
    }

    private const val RAD_TO_DEG = (180.0 / PI).toFloat()
}
