package dev.folio.core.ink.shape

import dev.folio.core.model.geometry.PointPt
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/** A generated pen stroke ([xs], [ys], page pt) meant to draw [expected] (null: a scribble that stays ink). */
internal class SyntheticStroke(
    val label: String,
    val expected: Expected?,
    val xs: FloatArray,
    val ys: FloatArray,
)

/** What the recognizer should answer. */
internal enum class Expected { LINE, ARROW, TRIANGLE, RECTANGLE, ELLIPSE, CIRCLE, POLYGON }

/**
 * Hand-like strokes: an ideal path sampled about every 1.5 pt (a 240 Hz pen), bent by a slow wobble across the path
 * (two sinusoids, about 1.2 % of the size each) plus 0.3 pt jitter; closed outlines start anywhere and stop short of or
 * run past their start.
 */
internal class SyntheticStrokes(
    seed: Int,
) {
    private val random = Random(seed)

    fun shape(kind: Expected): SyntheticStroke {
        val size = random.range(60f, 300f)
        val cx = random.range(200f, 400f)
        val cy = random.range(200f, 600f)
        val turn = random.range(-PI.toFloat(), PI.toFloat())
        val path =
            when (kind) {
                Expected.LINE -> {
                    listOf(rotate(PointPt(cx - size / 2, cy), cx, cy, turn), rotate(PointPt(cx + size / 2, cy), cx, cy, turn))
                }

                Expected.ARROW -> {
                    arrow(cx, cy, size, turn)
                }

                Expected.TRIANGLE -> {
                    closed(regular(3, cx, cy, size / 2, turn, random.range(0.8f, 1.2f)))
                }

                Expected.RECTANGLE -> {
                    val w = size
                    val h = size * random.range(0.4f, 1f)
                    val angle = if (random.nextBoolean()) 0f else turn
                    val corners =
                        listOf(PointPt(-w / 2, -h / 2), PointPt(w / 2, -h / 2), PointPt(w / 2, h / 2), PointPt(-w / 2, h / 2))
                            .map { rotate(PointPt(cx + it.x, cy + it.y), cx, cy, angle) }
                    closed(corners)
                }

                Expected.ELLIPSE -> {
                    ellipse(cx, cy, size / 2, size / 2 * random.range(0.35f, 0.75f), turn)
                }

                Expected.CIRCLE -> {
                    ellipse(cx, cy, size / 2, size / 2, 0f)
                }

                Expected.POLYGON -> {
                    closed(regular(5 + random.nextInt(2), cx, cy, size / 2, turn, 1f))
                }
            }
        val isClosed = kind != Expected.LINE && kind != Expected.ARROW
        val outline = if (isClosed) startAnywhere(path) else path
        return draw("$kind", kind, outline, size, closedGap = isClosed)
    }

    /** Scribbles: random zigzags, spirals, letters and wavy loops. */
    fun scribble(): SyntheticStroke {
        val size = random.range(60f, 300f)
        val cx = random.range(200f, 400f)
        val cy = random.range(200f, 600f)
        val style = random.nextInt(SCRIBBLE_STYLES)
        val path =
            when (style) {
                0 -> {
                    // zigzag: many sharp corners, open
                    List(6 + random.nextInt(6)) { i -> PointPt(cx + i * size / 8, cy + if (i % 2 == 0) 0f else size / 3) }
                }

                1 -> {
                    // random walk with sharp turns
                    var x = cx
                    var y = cy
                    List(10 + random.nextInt(10)) {
                        x += random.range(-size / 3, size / 3)
                        y += random.range(-size / 3, size / 3)
                        PointPt(x, y)
                    }
                }

                2 -> {
                    // open spiral
                    List(120) { i ->
                        val a = i / 120f * 4f * PI.toFloat()
                        val r = size / 2 * (0.2f + 0.8f * i / 120f)
                        PointPt(cx + r * cos(a), cy + r * sin(a))
                    }
                }

                3 -> {
                    // closed wavy loop (a cloud): too many bumps for a polygon, too bumpy for an ellipse
                    val bumps = 7 + random.nextInt(5)
                    List(181) { i ->
                        val a = i / 180f * 2f * PI.toFloat()
                        val r = size / 2 * (1f + 0.2f * sin(bumps * a))
                        PointPt(cx + r * cos(a), cy + r * sin(a))
                    }
                }

                else -> {
                    // an "S"
                    List(121) { i ->
                        val t = i / 120f
                        val a = if (t < 0.5f) PI.toFloat() * (0.5f - 3f * t) else PI.toFloat() * (-1f + 3f * (t - 0.5f))
                        val sign = if (t < 0.5f) 1f else -1f
                        val oy = if (t < 0.5f) -size / 4 else size / 4
                        PointPt(cx + sign * size / 4 * cos(a), cy + oy - size / 4 * sin(a))
                    }
                }
            }
        return draw("scribble $style", null, path, size, closedGap = false)
    }

    private fun arrow(
        cx: Float,
        cy: Float,
        size: Float,
        turn: Float,
    ): List<PointPt> {
        val start = PointPt(cx - size / 2, cy)
        val tip = PointPt(cx + size / 2, cy)
        val head = size * random.range(0.18f, 0.3f)
        val spread = random.range(0.4f, 0.65f)
        val barb1 = PointPt(tip.x - head * cos(spread), tip.y - head * sin(spread))
        val barb2 = PointPt(tip.x - head * cos(spread), tip.y + head * sin(spread))
        return listOf(start, tip, barb1, tip, barb2).map { rotate(it, cx, cy, turn) }
    }

    private fun regular(
        n: Int,
        cx: Float,
        cy: Float,
        r: Float,
        turn: Float,
        squash: Float,
    ): List<PointPt> = List(n) { i -> 2f * PI.toFloat() * i / n + turn }.map { a -> PointPt(cx + r * cos(a), cy + r * squash * sin(a)) }

    private fun ellipse(
        cx: Float,
        cy: Float,
        a: Float,
        b: Float,
        turn: Float,
    ): List<PointPt> =
        List(ELLIPSE_POINTS + 1) { i ->
            val t = 2f * PI.toFloat() * i / ELLIPSE_POINTS
            rotate(PointPt(cx + a * cos(t), cy + b * sin(t)), cx, cy, turn)
        }

    private fun closed(vertices: List<PointPt>) = vertices + vertices[0]

    // Rotates a closed path (last point = first) so it starts at a random spot along it.
    private fun startAnywhere(path: List<PointPt>): List<PointPt> {
        val dense = densify(path, 1f)
        val shift = random.nextInt(dense.size - 1)
        val open = dense.dropLast(1)
        val rotated = open.drop(shift) + open.take(shift)
        return rotated + rotated[0]
    }

    // Samples the path every ~1.5 pt, adds wobble and jitter, and ends the loop short of or past its start.
    private fun draw(
        label: String,
        expected: Expected?,
        path: List<PointPt>,
        size: Float,
        closedGap: Boolean,
    ): SyntheticStroke {
        var dense = densify(path, SAMPLE_PT)
        if (closedGap) {
            val n = dense.size
            val delta = (n * random.range(-0.03f, 0.08f)).toInt()
            dense = if (delta < 0) dense.take(n + delta) else dense + dense.drop(1).take(delta)
        }
        val amp1 = size * random.range(0.004f, 0.012f)
        val amp2 = size * random.range(0.004f, 0.012f)
        val wave1 = size * random.range(0.4f, 1.2f)
        val wave2 = size * random.range(0.15f, 0.4f)
        val phase1 = random.range(0f, 6.28f)
        val phase2 = random.range(0f, 6.28f)
        val xs = FloatArray(dense.size)
        val ys = FloatArray(dense.size)
        var along = 0f
        for (i in dense.indices) {
            if (i > 0) along += hypot(dense[i].x - dense[i - 1].x, dense[i].y - dense[i - 1].y)
            val j = if (i == dense.size - 1) i - 1 else i
            val tx = dense[j + 1].x - dense[j].x
            val ty = dense[j + 1].y - dense[j].y
            val len = hypot(tx, ty).coerceAtLeast(1e-3f)
            val off = amp1 * sin(2f * PI.toFloat() * along / wave1 + phase1) + amp2 * sin(2f * PI.toFloat() * along / wave2 + phase2)
            xs[i] = dense[i].x - ty / len * off + random.range(-JITTER_PT, JITTER_PT)
            ys[i] = dense[i].y + tx / len * off + random.range(-JITTER_PT, JITTER_PT)
        }
        return SyntheticStroke(label, expected, xs, ys)
    }

    private companion object {
        const val SAMPLE_PT = 1.5f
        const val JITTER_PT = 0.3f
        const val ELLIPSE_POINTS = 96
        const val SCRIBBLE_STYLES = 5
    }
}

private fun Random.range(
    from: Float,
    to: Float,
) = from + nextFloat() * (to - from)

private fun rotate(
    p: PointPt,
    cx: Float,
    cy: Float,
    angle: Float,
): PointPt {
    val c = cos(angle)
    val s = sin(angle)
    return PointPt(cx + c * (p.x - cx) - s * (p.y - cy), cy + s * (p.x - cx) + c * (p.y - cy))
}

/** Points along [path] about [stepPt] apart, its corners kept. */
internal fun densify(
    path: List<PointPt>,
    stepPt: Float,
): List<PointPt> {
    val out = ArrayList<PointPt>()
    out += path[0]
    for (k in 1 until path.size) {
        val a = path[k - 1]
        val b = path[k]
        val n = (hypot(b.x - a.x, b.y - a.y) / stepPt).toInt().coerceAtLeast(1)
        for (i in 1..n) out += PointPt(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
    }
    return out
}
