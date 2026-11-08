package dev.folio.core.ink.shape

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import org.junit.Test
import kotlin.math.abs

class ShapeRecognizerTest {
    private fun recognize(stroke: SyntheticStroke) = ShapeRecognizer.recognize(stroke.xs, stroke.ys, stroke.xs.size)

    private fun Expected.matches(shape: RecognizedShape?): Boolean =
        when (this) {
            Expected.LINE -> shape?.kind == ShapeKind.LINE
            Expected.ARROW -> shape?.kind == ShapeKind.ARROW
            Expected.TRIANGLE -> shape?.kind == ShapeKind.TRIANGLE
            Expected.RECTANGLE -> shape?.kind == ShapeKind.RECTANGLE
            Expected.ELLIPSE -> shape?.kind == ShapeKind.ELLIPSE
            Expected.CIRCLE -> shape?.kind == ShapeKind.ELLIPSE && isCircle(shape)
            Expected.POLYGON -> shape?.kind == ShapeKind.POLYGON && shape.points.size in 5..6
        }

    private fun isCircle(shape: RecognizedShape): Boolean {
        val (a, b) = shape.points
        return abs((b.x - a.x) - (b.y - a.y)) < 1e-3f
    }

    @Test
    fun recognize_seededNoisyShapes_classifiesAtLeast95Percent() {
        val strokes = SyntheticStrokes(SEED)
        val kinds = Expected.entries
        val misses = ArrayList<String>()
        for (i in 0 until SAMPLES) {
            val stroke = strokes.shape(kinds[i % kinds.size])
            val shape = recognize(stroke)
            if (!requireNotNull(stroke.expected).matches(shape)) misses += "#$i ${stroke.label} -> ${shape?.kind} ${shape?.points?.size}"
        }
        val rate = 1f - misses.size.toFloat() / SAMPLES
        assertWithMessage("seed $SEED misses: $misses").that(rate).isAtLeast(MIN_RATE)
    }

    @Test
    fun recognize_seededScribbles_stayInk() {
        val strokes = SyntheticStrokes(SEED + 1)
        val shapes = ArrayList<String>()
        for (i in 0 until SCRIBBLES) {
            val stroke = strokes.scribble()
            val shape = recognize(stroke)
            if (shape != null) shapes += "#$i ${stroke.label} -> ${shape.kind}"
        }
        assertWithMessage("seed ${SEED + 1} scribbles taken for shapes: $shapes").that(shapes).isEmpty()
    }

    @Test
    fun recognize_straightLine_endsAtTheStrokeEnds() {
        val xs = FloatArray(101) { it * 2f + 10f }
        val ys = FloatArray(101) { 50f + if (it % 2 == 0) 0.2f else -0.2f }

        val shape = ShapeRecognizer.recognize(xs, ys, xs.size)

        assertThat(shape?.kind).isEqualTo(ShapeKind.LINE)
        assertThat(shape?.points).containsExactly(PointPt(10f, 50.2f), PointPt(210f, 50.2f)).inOrder()
    }

    @Test
    fun recognize_nearlyAxisAlignedRectangle_snapsToTheAxes() {
        val corners = listOf(PointPt(100f, 100f), PointPt(300f, 110f), PointPt(295f, 230f), PointPt(95f, 220f), PointPt(100f, 100f))
        val path = densify(corners, 1.5f)

        val shape = ShapeRecognizer.recognize(FloatArray(path.size) { path[it].x }, FloatArray(path.size) { path[it].y }, path.size)

        assertThat(shape?.kind).isEqualTo(ShapeKind.RECTANGLE)
        assertThat(shape?.rotationDeg).isEqualTo(0f)
    }

    @Test
    fun recognize_tinyStroke_isNoShape() {
        val xs = floatArrayOf(10f, 11f, 12f, 13f)
        val ys = floatArrayOf(10f, 10f, 10f, 10f)

        assertThat(ShapeRecognizer.recognize(xs, ys, xs.size, minSizePt = 8f)).isNull()
    }

    @Test
    fun followed_line_movesItsEndToThePen() {
        val line = RecognizedShape(ShapeKind.LINE, listOf(PointPt(0f, 0f), PointPt(100f, 0f)))

        val moved = line.followed(PointPt(100f, 0f), PointPt(80f, 40f))

        assertThat(moved.points).containsExactly(PointPt(0f, 0f), PointPt(80f, 40f)).inOrder()
    }

    @Test
    fun followed_rectangle_scalesAndTurnsAroundItsCenter() {
        val box = RecognizedShape(ShapeKind.RECTANGLE, listOf(PointPt(-10f, -10f), PointPt(10f, 10f)))

        // The pen held at the right edge's middle, then moves out to twice the distance and a quarter turn on.
        val moved = box.followed(PointPt(10f, 0f), PointPt(0f, 20f))

        assertThat(moved.points[0].x).isWithin(1e-4f).of(-20f)
        assertThat(moved.points[1].y).isWithin(1e-4f).of(20f)
        assertThat(moved.rotationDeg).isWithin(1e-3f).of(90f)
    }

    private companion object {
        const val SEED = 20261109
        const val SAMPLES = 200
        const val SCRIBBLES = 50
        const val MIN_RATE = 0.95f
    }
}
