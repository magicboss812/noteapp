package dev.folio.app.spikes

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Brush family of a synthetic stroke (P01-S2). */
internal enum class SyntheticBrush { PEN, MARKER, HIGHLIGHTER }

/** One seeded stroke in page points; the view converts it to an androidx.ink Stroke. */
internal class SyntheticStroke(
    val brush: SyntheticBrush,
    val sizePt: Float,
    val argb: Int,
    val xsPt: FloatArray,
    val ysPt: FloatArray,
    val pressures: FloatArray,
)

/**
 * Handwriting-like strokes for the tile spike: short smoothed random walks along the lines of a
 * college-ruled page. Deterministic for a seed. Mix: 60% pen, 25% marker, 15% highlighter.
 */
internal object SyntheticStrokes {
    const val SEED = 20260927L
    const val COUNT = 1500

    fun generate(
        widthPt: Float,
        heightPt: Float,
        count: Int = COUNT,
        seed: Long = SEED,
    ): List<SyntheticStroke> {
        val random = Random(seed)
        return List(count) { stroke(random, widthPt, heightPt) }
    }

    private fun stroke(
        random: Random,
        widthPt: Float,
        heightPt: Float,
    ): SyntheticStroke {
        val roll = random.nextFloat()
        val brush =
            when {
                roll < PEN_SHARE -> SyntheticBrush.PEN
                roll < PEN_SHARE + MARKER_SHARE -> SyntheticBrush.MARKER
                else -> SyntheticBrush.HIGHLIGHTER
            }
        val sizePt =
            when (brush) {
                SyntheticBrush.PEN -> random.range(PEN_MIN_PT, PEN_MAX_PT)
                SyntheticBrush.MARKER -> random.range(MARKER_MIN_PT, MARKER_MAX_PT)
                SyntheticBrush.HIGHLIGHTER -> random.range(HIGHLIGHTER_MIN_PT, HIGHLIGHTER_MAX_PT)
            }
        val argb =
            when (brush) {
                SyntheticBrush.HIGHLIGHTER -> HIGHLIGHTER_COLORS[random.nextInt(HIGHLIGHTER_COLORS.size)]
                else -> INK_COLORS[random.nextInt(INK_COLORS.size)]
            }
        val n = random.nextInt(MIN_POINTS, MAX_POINTS + 1)
        val xs = FloatArray(n)
        val ys = FloatArray(n)
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
        return SyntheticStroke(brush, sizePt, argb, xs, ys, pressures)
    }

    private fun Random.range(
        from: Float,
        until: Float,
    ): Float = from + nextFloat() * (until - from)

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
