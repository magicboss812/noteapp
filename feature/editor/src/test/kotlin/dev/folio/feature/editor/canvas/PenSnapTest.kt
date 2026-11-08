package dev.folio.feature.editor.canvas

import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.ink.shape.DwellDetector
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
class PenSnapTest {
    private val viewport = Viewport(DENSITY).apply { setPages(List(3) { ModelFixtures.page("p$it").toRef() }, VIEW_W, VIEW_H) }
    private val surface = FakeWetSurface(ApplicationProvider.getApplicationContext())
    private val timer = FakeHoldTimer()
    private var ids = 0
    private val snap = PenSnap(dwellRadiusPx = 10f, minShapePx = 30f, timer = timer) { ObjectId("snap-${ids++}") }
    private val finished = ArrayList<PendingSnap>()
    private var brush = PEN
    private var straightOnly = false
    private val pen =
        PenInput(
            viewport,
            surface,
            brush = { brush },
            capabilities = { StylusCapabilities.NONE },
            onStrokeStart = {},
            snap = snap,
            straightOnly = { straightOnly },
        )

    init {
        snap.onFinished = { finished += it }
    }

    private fun stylus(
        action: Int,
        timeMs: Long,
        pt: PointPt,
    ) = viewport.toViewPx(PAGE, pt).let { event(action, timeMs, it.x to it.y, toolType = TOOL_TYPE_STYLUS) }

    /** Draws through [points] (page pt), one sample per 4 ms from t = 0; returns the time of the last sample. */
    private fun draw(points: List<PointPt>): Long {
        pen.onStylusDown(stylus(ACTION_DOWN, 0, points[0]), 0, eraser = false)
        for (i in 1 until points.size) pen.onStylusMove(stylus(ACTION_MOVE, i * 4L, points[i]), 0)
        return (points.size - 1) * 4L
    }

    private fun rectangle(): List<PointPt> =
        densify(listOf(PointPt(100f, 100f), PointPt(300f, 100f), PointPt(300f, 250f), PointPt(100f, 250f), PointPt(100f, 100f)))

    @Test
    fun alwaysStraight_wavyHighlighterStroke_commitsStraightSegmentFirstToLast() {
        brush = HIGHLIGHTER
        straightOnly = true
        val wavy = List(80) { i -> PointPt(100f + i * 3f, 300f + 12f * sin(i / 5f)) }

        val end = draw(wavy)
        pen.onStylusUp(stylus(ACTION_UP, end + 4, wavy.last()), 0)

        assertThat(surface.calls).isEmpty() // no wet stroke: the overlay drew the straight line
        val stroke = finished.single().obj as InkStroke
        val inputs = stroke.inputs
        assertThat(inputs.x.first()).isWithin(TOL).of(wavy.first().x)
        assertThat(inputs.y.first()).isWithin(TOL).of(wavy.first().y)
        assertThat(inputs.x.last()).isWithin(TOL).of(wavy.last().x)
        assertThat(inputs.y.last()).isWithin(TOL).of(wavy.last().y)
        val slope = (inputs.y.last() - inputs.y.first()) / (inputs.x.last() - inputs.x.first())
        for (i in 0 until inputs.size) {
            assertThat(abs(inputs.y[i] - (inputs.y.first() + slope * (inputs.x[i] - inputs.x.first())))).isLessThan(TOL)
        }
        assertThat(stroke.brush).isEqualTo(HIGHLIGHTER)
    }

    @Test
    fun alwaysStraight_penBrush_drawsInk() {
        straightOnly = true

        draw(rectangle())

        assertThat(surface.calls.first()).isEqualTo("start 0")
        assertThat(snap.snapped).isFalse()
    }

    @Test
    fun hold_onDrawnRectangle_replacesTheWetStrokeWithAShapeThatFollowsThePen() {
        val end = draw(rectangle())
        timer.advanceTo(end + DwellDetector.DWELL_MS)

        assertThat(surface.calls.last()).isEqualTo("cancel")
        assertThat(pen.stats.snapped).isEqualTo(1)
        val held = snap.preview() as Shape
        assertThat(held.kind).isEqualTo(ShapeKind.RECTANGLE)

        // Dragging on to twice the distance from the center doubles the rectangle.
        pen.onStylusMove(stylus(ACTION_MOVE, end + 500, PointPt(0f, 25f)), 0)
        pen.onStylusUp(stylus(ACTION_UP, end + 504, PointPt(0f, 25f)), 0)

        val shape = finished.single().obj as Shape
        assertThat(shape.kind).isEqualTo(ShapeKind.RECTANGLE)
        assertThat(shape.id).isEqualTo(ObjectId("snap-0"))
        assertThat(shape.stroke.argb).isEqualTo(PEN.argb)
        assertThat(shape.stroke.widthPt).isEqualTo(PEN.sizePt)
        assertThat(shape.bounds.widthPt).isWithin(6f).of(400f + PEN.sizePt)
        assertThat(surface.calls).doesNotContain("finish 0")
        assertThat(snap.pending?.obj).isEqualTo(shape)

        snap.committed()

        assertThat(snap.pending).isNull()
    }

    @Test
    fun hold_onScribble_keepsInking() {
        val scribble = densify(List(12) { i -> PointPt(100f + i * 20f, if (i % 2 == 0) 100f else 160f) })

        val end = draw(scribble)
        timer.advanceTo(end + DwellDetector.DWELL_MS)
        pen.onStylusUp(stylus(ACTION_UP, end + 600, scribble.last()), 0)

        assertThat(surface.calls).contains("finish 0")
        assertThat(surface.calls).doesNotContain("cancel")
        assertThat(finished).isEmpty()
        assertThat(timer.posted).isFalse()
    }

    @Test
    fun hold_beforeTheDwellTime_waits() {
        val end = draw(rectangle())
        timer.advanceTo(end + DwellDetector.DWELL_MS - 50)

        assertThat(snap.snapped).isFalse()

        timer.advanceTo(end + DwellDetector.DWELL_MS)

        assertThat(snap.snapped).isTrue()
    }

    @Test
    fun hold_highlighter_becomesStraightLineFromItsStartToThePen() {
        brush = HIGHLIGHTER
        val wavy = List(60) { i -> PointPt(100f + i * 3f, 300f + 6f * sin(i / 4f)) }

        val end = draw(wavy)
        timer.advanceTo(end + DwellDetector.DWELL_MS)
        pen.onStylusMove(stylus(ACTION_MOVE, end + 500, PointPt(400f, 400f)), 0)
        pen.onStylusUp(stylus(ACTION_UP, end + 504, PointPt(400f, 400f)), 0)

        val inputs = (finished.single().obj as InkStroke).inputs
        assertThat(inputs.x.first()).isWithin(TOL).of(100f)
        assertThat(inputs.x.last()).isWithin(TOL).of(400f)
        assertThat(inputs.y.last()).isWithin(TOL).of(400f)
    }

    @Test
    fun cancel_afterSnap_commitsNothing() {
        val end = draw(rectangle())
        timer.advanceTo(end + DwellDetector.DWELL_MS)

        pen.onStylusCancel(stylus(ACTION_CANCEL, end + 500, PointPt(0f, 0f)), 0)

        assertThat(finished).isEmpty()
        assertThat(snap.previewPage).isEqualTo(-1)
    }

    /** Runs posted tasks when [advanceTo] passes their time. */
    private class FakeHoldTimer : HoldTimer {
        private var now = 0L
        private var task: Runnable? = null
        private var dueMs = 0L

        val posted: Boolean get() = task != null

        override fun nowMs(): Long = now

        override fun post(
            task: Runnable,
            delayMs: Long,
        ) {
            this.task = task
            dueMs = now + delayMs
        }

        override fun cancel(task: Runnable) {
            if (this.task === task) this.task = null
        }

        fun advanceTo(timeMs: Long) {
            var t = task
            while (t != null && dueMs <= timeMs) {
                now = dueMs
                task = null
                t.run()
                t = task
            }
            now = timeMs
        }
    }

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
        const val TOL = 0.05f
        val PAGE = PageId("p0")
        val PEN = BrushSpec(BrushKind.BALLPOINT, 0xFF2563EB.toInt(), 1.2f, 1)
        val HIGHLIGHTER = BrushSpec(BrushKind.HIGHLIGHTER, 0x59FDE047, 12f, 1)
    }
}

/** Points along [path] about 2 pt apart, its corners kept. */
private fun densify(path: List<PointPt>): List<PointPt> {
    val out = ArrayList<PointPt>()
    out += path[0]
    for (k in 1 until path.size) {
        val a = path[k - 1]
        val b = path[k]
        val n = (kotlin.math.hypot(b.x - a.x, b.y - a.y) / 2f).toInt().coerceAtLeast(1)
        for (i in 1..n) out += PointPt(a.x + (b.x - a.x) * i / n, a.y + (b.y - a.y) * i / n)
    }
    return out
}
