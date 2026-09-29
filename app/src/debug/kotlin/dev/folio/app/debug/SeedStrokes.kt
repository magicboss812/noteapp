package dev.folio.app.debug

import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.StrokeInputs
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Parsed `seed-strokes n[,page]`: [count] strokes on stack page [page] (1-based). */
internal data class SeedStrokes(
    val count: Int,
    val page: Int,
) {
    companion object {
        const val MAX_COUNT = 5000

        fun parse(arg: String?): SeedStrokes? {
            val parts = arg?.split(',')?.takeIf { it.size in 1..2 } ?: return null
            val count = parts[0].toIntOrNull()?.takeIf { it in 1..MAX_COUNT } ?: return null
            val page = if (parts.size == 2) parts[1].toIntOrNull()?.takeIf { it >= 1 } else 1
            return page?.let { SeedStrokes(count, it) }
        }
    }
}

/**
 * Handwriting-like strokes for perf runs (the P01-S2 tile spike mix, 12-performance.md#measurement):
 * short smoothed random walks, 60% ballpoint, 25% marker, 15% highlighter, 20..80 samples at 5 ms.
 * Deterministic for a seed; ids are derived from the seed and index.
 */
internal object SyntheticStrokes {
    const val SEED = 20260927L

    fun generate(
        count: Int,
        widthPt: Float,
        heightPt: Float,
        seed: Long = SEED,
    ): List<InkStroke> {
        val random = Random(seed)
        return List(count) { i -> stroke(random, ObjectId("seed-$seed-$i"), widthPt, heightPt) }
    }

    private fun stroke(
        random: Random,
        id: ObjectId,
        widthPt: Float,
        heightPt: Float,
    ): InkStroke {
        val roll = random.nextFloat()
        val kind =
            when {
                roll < PEN_SHARE -> BrushKind.BALLPOINT
                roll < PEN_SHARE + MARKER_SHARE -> BrushKind.MARKER
                else -> BrushKind.HIGHLIGHTER
            }
        val sizePt =
            when (kind) {
                BrushKind.MARKER -> random.range(MARKER_MIN_PT, MARKER_MAX_PT)
                BrushKind.HIGHLIGHTER -> random.range(HIGHLIGHTER_MIN_PT, HIGHLIGHTER_MAX_PT)
                else -> random.range(PEN_MIN_PT, PEN_MAX_PT)
            }
        val argb =
            if (kind == BrushKind.HIGHLIGHTER) {
                HIGHLIGHTER_COLORS[random.nextInt(HIGHLIGHTER_COLORS.size)]
            } else {
                INK_COLORS[random.nextInt(INK_COLORS.size)]
            }
        val n = random.nextInt(MIN_POINTS, MAX_POINTS + 1)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
        val ts = FloatArray(n) { it * SAMPLE_INTERVAL_MS }
        val pressures = FloatArray(n)
        val margin = sizePt + EDGE_PT
        var x = random.range(margin, widthPt - margin)
        var y = random.range(margin, heightPt - margin)
        var heading = random.range(-HEADING_SPREAD, HEADING_SPREAD) // mostly left to right
        val stepPt = random.range(STEP_MIN_PT, STEP_MAX_PT)
        for (i in 0 until n) {
            xs[i] = x
            ys[i] = y
            pressures[i] = random.range(PRESSURE_MIN, 1f)
            heading += random.range(-TURN, TURN)
            x = (x + stepPt * cos(heading)).coerceIn(margin, widthPt - margin)
            y = (y + stepPt * sin(heading)).coerceIn(margin, heightPt - margin)
        }
        val inputs = StrokeInputs(xs, ys, ts, pressures, null, null, InputTool.SYNTHETIC)
        return InkStroke.of(id, BrushSpec(kind, argb, sizePt, BRUSH_VERSION), inputs)
    }

    private fun Random.range(
        from: Float,
        until: Float,
    ): Float = from + nextFloat() * (until - from)

    private const val BRUSH_VERSION = 1
    private const val SAMPLE_INTERVAL_MS = 5f
    private const val PEN_SHARE = 0.60f
    private const val MARKER_SHARE = 0.25f
    private const val PEN_MIN_PT = 0.5f
    private const val PEN_MAX_PT = 2f
    private const val MARKER_MIN_PT = 1.5f
    private const val MARKER_MAX_PT = 4f
    private const val HIGHLIGHTER_MIN_PT = 8f
    private const val HIGHLIGHTER_MAX_PT = 14f
    private const val MIN_POINTS = 20
    private const val MAX_POINTS = 80
    private const val STEP_MIN_PT = 1f
    private const val STEP_MAX_PT = 3f
    private const val EDGE_PT = 4f
    private const val PRESSURE_MIN = 0.3f
    private const val HEADING_SPREAD = 0.3f
    private const val TURN = (PI / 5).toFloat()
    private val INK_COLORS = intArrayOf(0xFF1A1A1A.toInt(), 0xFF1F4FB8.toInt(), 0xFFC62828.toInt(), 0xFF2E7D32.toInt())
    private val HIGHLIGHTER_COLORS = intArrayOf(0x66FFEB3B, 0x6681D4FA, 0x66F48FB1)
}
