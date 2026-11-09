package dev.folio.feature.editor.canvas

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.view.InputDevice
import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.view.View
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.ink.ruler.RulerAngles
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.math.cos
import kotlin.math.sin

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1000dp-h800dp-mdpi")
class RulerInputTest {
    private val viewport =
        Viewport(
            1f,
        ).apply { setPages(List(3) { ModelFixtures.page("p$it").toRef() }, VIEW_W.toFloat(), VIEW_H.toFloat()) }
    private val surface = FakeWetSurface(ApplicationProvider.getApplicationContext())
    private val overlay = RulerOverlay(ApplicationProvider.getApplicationContext()).also { layout(it) }
    private val forwarded = ArrayList<Int>()
    private var palm = false
    private val gestures =
        RulerGestures(overlay, slopPx = 8f, tapTimeoutMs = 400L, readoutMinPx = 48f, acceptsFinger = { _, _ -> !palm }) {
            forwarded += it.actionMasked
        }
    private val pen =
        PenInput(
            viewport,
            surface,
            brush = { BRUSH },
            capabilities = { StylusCapabilities.NONE },
            onStrokeStart = {},
            ruler = { overlay.active },
            rulerSnapPx = SNAP_PX,
        )
    private val ruler get() = overlay.ruler
    private val halfW get() = ruler.widthPx / 2f

    @Test
    fun penStroke_startingNearAnEdge_followsTheEdgeLine() {
        overlay.show(true)
        val top = CY - halfW
        pen.onStylusDown(stylus(ACTION_DOWN, 0, 300f, top - 6f), 0, eraser = false)
        pen.onStylusMove(stylus(ACTION_MOVE, 10, 360f, top - 15f), 0)
        pen.onStylusMove(stylus(ACTION_MOVE, 20, 420f, top + 9f), 0)
        pen.onStylusUp(stylus(ACTION_UP, 30, 480f, top - 30f), 0)

        assertThat(surface.calls).containsExactly("startInput", "flushInputs 1", "flushInputs 1", "finishInput").inOrder()
        val view = surface.inputs.map { (x, y) -> toView(x, y) }
        assertThat(view.map { it.first }).containsExactly(300f, 360f, 420f, 480f).inOrder()
        view.forEach { assertThat(it.second).isWithin(EPS).of(top) }
        assertThat(pen.stats.finished).isEqualTo(1)
    }

    @Test
    fun penStroke_onATurnedRuler_landsOnTheBottomEdge() {
        overlay.show(true)
        overlay.place(CX, CY, 30f)
        val points = listOf(-200f to 8f, -100f to -10f, 0f to 4f, 150f to 11f).map { (along, off) -> onRuler(along, halfW + off) }
        pen.onStylusDown(stylus(ACTION_DOWN, 0, points[0].first, points[0].second), 0, eraser = false)
        for (i in 1 until points.size - 1) pen.onStylusMove(stylus(ACTION_MOVE, i * 10L, points[i].first, points[i].second), 0)
        pen.onStylusUp(stylus(ACTION_UP, 40, points.last().first, points.last().second), 0)

        assertThat(surface.inputs).hasSize(points.size)
        surface.inputs.forEachIndexed { i, (x, y) ->
            val (vx, vy) = toView(x, y)
            assertThat(ruler.acrossPx(vx, vy)).isWithin(EPS).of(halfW)
            assertThat(ruler.alongPx(vx, vy)).isWithin(EPS).of(ruler.alongPx(points[i].first, points[i].second))
        }
    }

    @Test
    fun penStroke_awayFromTheEdgesOrWithTheRulerHidden_drawsFreely() {
        overlay.show(true)
        pen.onStylusDown(stylus(ACTION_DOWN, 0, 300f, CY), 0, eraser = false) // the ruler's middle
        pen.onStylusUp(stylus(ACTION_UP, 10, 320f, CY), 0)
        overlay.show(false)
        pen.onStylusDown(stylus(ACTION_DOWN, 20, 300f, CY - halfW), 0, eraser = false) // on the edge, ruler hidden
        pen.onStylusUp(stylus(ACTION_UP, 30, 320f, CY - halfW), 0)

        assertThat(surface.calls).containsExactly("start 0", "finish 0", "start 0", "finish 0").inOrder()
    }

    @Test
    fun oneFinger_onTheRuler_movesItInsteadOfPanning() {
        overlay.show(true)

        assertThat(gestures.onTouch(event(ACTION_DOWN, 0, 300f to CY))).isTrue()
        assertThat(gestures.onTouch(event(ACTION_MOVE, 10, 340f to CY + 60f))).isTrue()
        assertThat(gestures.onTouch(event(ACTION_UP, 20, 340f to CY + 60f))).isTrue()

        assertThat(ruler.centerXPx).isEqualTo(CX + 40f)
        assertThat(ruler.centerYPx).isEqualTo(CY + 60f)
        assertThat(ruler.angleDeg).isEqualTo(0f)
        assertThat(forwarded).isEmpty()
    }

    @Test
    fun finger_offTheRulerHiddenOrAPalm_isNotTaken() {
        assertThat(gestures.onTouch(event(ACTION_DOWN, 0, 300f to CY))).isFalse() // hidden
        overlay.show(true)
        assertThat(gestures.onTouch(event(ACTION_DOWN, 0, 300f to CY - 200f))).isFalse()
        assertThat(gestures.onTouch(event(ACTION_MOVE, 10, 300f to CY - 100f))).isFalse()
        palm = true
        assertThat(gestures.onTouch(event(ACTION_DOWN, 20, 300f to CY))).isFalse()
    }

    @Test
    fun twoFingers_turnTheRulerAroundTheirMidpoint_restingInDetents() {
        overlay.show(true)
        gestures.onTouch(event(ACTION_DOWN, 0, 400f to CY))
        gestures.onTouch(event(ACTION_POINTER_DOWN, 10, 400f to CY, 600f to CY, actionIndex = 1))
        // 43 deg clockwise around (500, CY): the turn rests at 45.
        gestures.onTouch(event(ACTION_MOVE, 20, turned(-100f, 43f), turned(100f, 43f)))

        assertThat(ruler.angleDeg).isEqualTo(45f)
        assertThat(ruler.centerXPx).isWithin(EPS).of(CX)
        assertThat(ruler.centerYPx).isWithin(EPS).of(CY)
        // 50 deg: between detents, the ruler follows the fingers.
        gestures.onTouch(event(ACTION_MOVE, 30, turned(-100f, 50f), turned(100f, 50f)))
        assertThat(ruler.angleDeg).isWithin(EPS).of(50f)
        assertThat(RulerAngles.readoutDeg(ruler.angleDeg)).isEqualTo(130)
    }

    @Test
    fun tapOnTheReadout_turnsTheRulerLevelAgain() {
        overlay.show(true)
        overlay.place(CX, CY, -37f)

        gestures.onTouch(event(ACTION_DOWN, 0, CX + 2f to CY + 1f))
        gestures.onTouch(event(ACTION_UP, 80, CX + 2f to CY + 1f))

        assertThat(ruler.angleDeg).isEqualTo(0f)
        assertThat(ruler.centerXPx).isEqualTo(CX)
    }

    @Test
    fun penJoiningAFingerOnTheRuler_isForwardedToTheRouter() {
        overlay.show(true)
        gestures.onTouch(event(ACTION_DOWN, 0, 300f to CY))
        val both = mixed(ACTION_POINTER_DOWN, 10, 300f to CY, 700f to CY - 300f)
        assertThat(gestures.onTouch(both)).isTrue()
        gestures.onTouch(mixed(ACTION_MOVE, 20, 300f to CY, 720f to CY - 300f))

        assertThat(forwarded).containsExactly(ACTION_POINTER_DOWN, ACTION_MOVE).inOrder()
        assertThat(ruler.centerXPx).isEqualTo(CX) // the finger held still
    }

    @Test
    fun screenshot_turnedRulerOverAPage() {
        overlay.show(true)
        overlay.place(CX, CY, -30f)
        val bitmap = Bitmap.createBitmap(VIEW_W, VIEW_H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(Color.WHITE)
        overlay.draw(canvas)
        bitmap.captureRoboImage("src/test/screenshots/Ruler_turned30.png")
    }

    // Page pt of the first page to view px (the wet surface's strokeToView).
    private fun toView(
        x: Float,
        y: Float,
    ): Pair<Float, Float> {
        val p = floatArrayOf(x, y)
        surface.lastStrokeToView.mapPoints(p)
        return p[0] to p[1]
    }

    // The view point [along] the ruler and [across] it from its center.
    private fun onRuler(
        along: Float,
        across: Float,
    ): Pair<Float, Float> =
        (ruler.centerXPx + along * ruler.dirX - across * ruler.dirY) to (ruler.centerYPx + along * ruler.dirY + across * ruler.dirX)

    // A finger [offset] px from (CX, CY) along a line turned clockwise by [deg].
    private fun turned(
        offset: Float,
        deg: Float,
    ): Pair<Float, Float> {
        val rad = Math.toRadians(deg.toDouble())
        return (CX + offset * cos(rad).toFloat()) to (CY + offset * sin(rad).toFloat())
    }

    private fun stylus(
        action: Int,
        timeMs: Long,
        x: Float,
        y: Float,
    ) = event(action, timeMs, x to y, toolType = TOOL_TYPE_STYLUS)

    // A finger at pointer 0 and the pen at pointer 1 ([action] on the pen).
    private fun mixed(
        action: Int,
        timeMs: Long,
        finger: Pair<Float, Float>,
        pen: Pair<Float, Float>,
    ): MotionEvent {
        val points = listOf(finger, pen)
        val properties =
            Array(2) { i ->
                MotionEvent.PointerProperties().apply {
                    id = i
                    toolType = if (i == 0) TOOL_TYPE_FINGER else TOOL_TYPE_STYLUS
                }
            }
        val coords =
            Array(2) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i].first
                    y = points[i].second
                    pressure = 1f
                }
            }
        val full = if (action == ACTION_POINTER_DOWN) action or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT) else action
        return MotionEvent.obtain(0L, timeMs, full, 2, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
    }

    private fun layout(view: View) {
        view.measure(
            View.MeasureSpec.makeMeasureSpec(VIEW_W, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(VIEW_H, View.MeasureSpec.EXACTLY),
        )
        view.layout(0, 0, VIEW_W, VIEW_H)
    }

    private companion object {
        const val VIEW_W = 1000
        const val VIEW_H = 800
        const val CX = VIEW_W / 2f
        const val CY = VIEW_H / 2f
        const val SNAP_PX = 12f
        const val EPS = 0.05f
        val BRUSH = BrushSpec(BrushKind.BALLPOINT, 0xFF1A1A1A.toInt(), 1.5f, 1)
    }
}
