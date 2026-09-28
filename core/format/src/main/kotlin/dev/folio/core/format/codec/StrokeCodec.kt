package dev.folio.core.format.codec

import dev.folio.core.model.InputTool
import dev.folio.core.model.StrokeInputs
import kotlin.math.roundToLong
import dev.folio.core.format.proto.v1.InputTool as PbInputTool
import dev.folio.core.format.proto.v1.StrokeInputs as PbStrokeInputs

/**
 * Quantized delta encoding of stroke samples (04-file-format.md#stroke-encoding): positions in 1/64 pt,
 * time in 0.1 ms, pressure 0..1023, tilt and orientation in 0.1 deg. Quantization is applied to the
 * absolute values first and deltas are taken between quantized values, so errors never accumulate.
 */
internal object StrokeCodec {
    const val POS_UNITS_PER_PT = 64.0
    const val TIME_UNITS_PER_MS = 10.0
    const val PRESSURE_MAX = 1023
    const val ANGLE_UNITS_PER_DEG = 10.0
    private const val TILT_MAX = 900
    private const val ORIENTATION_MAX = 1800

    /** Max samples accepted from a file (untrusted input bound). */
    const val MAX_SAMPLES = 1_000_000

    fun encode(inputs: StrokeInputs): PbStrokeInputs {
        val n = inputs.size
        val qx = LongArray(n) { (inputs.x[it] * POS_UNITS_PER_PT).roundToLong() }
        val qy = LongArray(n) { (inputs.y[it] * POS_UNITS_PER_PT).roundToLong() }
        val qt = LongArray(n) { (inputs.tMs[it] * TIME_UNITS_PER_MS).roundToLong() }
        return PbStrokeInputs(
            origin_x = if (n == 0) 0L else qx[0],
            origin_y = if (n == 0) 0L else qy[0],
            dx = deltas(qx),
            dy = deltas(qy),
            dt = deltas(qt),
            pressure = inputs.pressure?.map { (it * PRESSURE_MAX).roundToLong().toInt().coerceIn(0, PRESSURE_MAX) } ?: emptyList(),
            tilt = inputs.tiltDeg?.map { (it * ANGLE_UNITS_PER_DEG).roundToLong().toInt().coerceIn(0, TILT_MAX) } ?: emptyList(),
            orientation =
                inputs.orientationDeg?.map {
                    (it * ANGLE_UNITS_PER_DEG).roundToLong().toInt().coerceIn(-ORIENTATION_MAX, ORIENTATION_MAX)
                } ?: emptyList(),
            tool = encodeTool(inputs.tool),
        )
    }

    /** Throws [CorruptDataException] for inconsistent channel sizes. */
    fun decode(pb: PbStrokeInputs): StrokeInputs {
        val n = pb.dx.size
        corruptIf(n > MAX_SAMPLES) { "stroke has $n samples" }
        corruptIf(pb.dy.size != n || pb.dt.size != n) { "stroke channel sizes differ" }
        corruptIf(pb.pressure.isNotEmpty() && pb.pressure.size != n) { "pressure size differs" }
        corruptIf(pb.tilt.isNotEmpty() && pb.tilt.size != n) { "tilt size differs" }
        corruptIf(pb.orientation.isNotEmpty() && pb.orientation.size != n) { "orientation size differs" }
        return StrokeInputs(
            x = integrate(pb.origin_x, pb.dx, POS_UNITS_PER_PT),
            y = integrate(pb.origin_y, pb.dy, POS_UNITS_PER_PT),
            tMs = integrate(0L, pb.dt, TIME_UNITS_PER_MS),
            pressure = if (pb.pressure.isEmpty()) null else FloatArray(n) { pb.pressure[it].toFloat() / PRESSURE_MAX },
            tiltDeg = if (pb.tilt.isEmpty()) null else FloatArray(n) { (pb.tilt[it] / ANGLE_UNITS_PER_DEG).toFloat() },
            orientationDeg =
                if (pb.orientation.isEmpty()) null else FloatArray(n) { (pb.orientation[it] / ANGLE_UNITS_PER_DEG).toFloat() },
            tool = decodeTool(pb.tool),
        )
    }

    private fun deltas(q: LongArray): List<Int> {
        val out = ArrayList<Int>(q.size)
        for (i in q.indices) {
            val d = if (i == 0) 0L else q[i] - q[i - 1]
            require(d in Int.MIN_VALUE..Int.MAX_VALUE) { "stroke delta out of range: $d" }
            out += d.toInt()
        }
        return out
    }

    private fun integrate(
        origin: Long,
        deltas: List<Int>,
        unitsPerValue: Double,
    ): FloatArray {
        val out = FloatArray(deltas.size)
        var q = origin
        for (i in deltas.indices) {
            q += deltas[i]
            out[i] = (q / unitsPerValue).toFloat()
        }
        return out
    }

    private fun encodeTool(tool: InputTool): PbInputTool =
        when (tool) {
            InputTool.STYLUS -> PbInputTool.STYLUS
            InputTool.ERASER_END -> PbInputTool.ERASER_END
            InputTool.SYNTHETIC -> PbInputTool.SYNTHETIC
        }

    private fun decodeTool(tool: PbInputTool): InputTool =
        when (tool) {
            PbInputTool.ERASER_END -> InputTool.ERASER_END
            PbInputTool.SYNTHETIC -> InputTool.SYNTHETIC
            PbInputTool.STYLUS, PbInputTool.INPUT_TOOL_UNSPECIFIED -> InputTool.STYLUS
        }
}

/** Invalid content found while decoding; turned into `FormatError.Corrupt` at the codec boundary. */
internal class CorruptDataException(
    detail: String,
) : Exception(detail)

internal inline fun corruptIf(
    condition: Boolean,
    detail: () -> String,
) {
    if (condition) throw CorruptDataException(detail())
}
