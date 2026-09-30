package dev.folio.core.ink.input

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_HOVER_ENTER
import android.view.MotionEvent.ACTION_HOVER_EXIT
import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_SCROLL
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.FLAG_CANCELED
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

private class Recorder :
    StylusTarget,
    NavigationTarget {
    val calls = ArrayList<String>()

    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        calls += if (eraser) "eraserDown $pointerId" else "penDown $pointerId"
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "penMove $pointerId"
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "penUp $pointerId"
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "penCancel $pointerId"
    }

    override fun onNavigationEvent(event: MotionEvent) {
        calls += "nav ${MotionEvent.actionToString(event.action)}"
    }

    override fun cancelNavigation() {
        calls += "navCancel"
    }

    override fun onScroll(event: MotionEvent) {
        calls += "scroll"
    }
}

@RunWith(AndroidJUnit4::class)
class InputRouterTest {
    private val recorder = Recorder()
    private val router = InputRouter(recorder, recorder, largeTouchPx = LARGE_PX)

    private fun send(event: MotionEvent) {
        router.onTouchEvent(event)
        event.recycle()
    }

    /** A finger pan gesture starting at [startMs]. */
    private fun fingerPan(startMs: Long) {
        send(motion(ACTION_DOWN, startMs, finger(0)))
        send(motion(ACTION_MOVE, startMs + 10, finger(0, y = 200f)))
        send(motion(ACTION_UP, startMs + 20, finger(0, y = 200f)))
    }

    private fun penStroke(
        startMs: Long,
        eraser: Boolean = false,
    ) {
        val p = if (eraser) pen(0).copy(toolType = MotionEvent.TOOL_TYPE_ERASER) else pen(0)
        send(motion(ACTION_DOWN, startMs, p))
        send(motion(ACTION_MOVE, startMs + 10, p.copy(y = 200f)))
        send(motion(ACTION_UP, startMs + 20, p.copy(y = 200f)))
    }

    @Test
    fun stylus_downMoveUp_goesToStylusTargetOnly() {
        penStroke(1000)

        assertThat(recorder.calls).containsExactly("penDown 0", "penMove 0", "penUp 0").inOrder()
    }

    @Test
    fun eraserTool_goesToStylusTargetAsEraser() {
        penStroke(1000, eraser = true)

        assertThat(recorder.calls).containsExactly("eraserDown 0", "penMove 0", "penUp 0").inOrder()
    }

    @Test
    fun oneFinger_goesToNavigationOnly() {
        fingerPan(1000)

        assertThat(recorder.calls)
            .containsExactly("nav ACTION_DOWN", "nav ACTION_MOVE", "nav ACTION_UP")
            .inOrder()
    }

    @Test
    fun twoFingers_bothGoToNavigation() {
        send(motion(ACTION_DOWN, 1000, finger(0)))
        send(motion(ACTION_POINTER_DOWN, 1010, finger(0), finger(1, x = 300f), actionIndex = 1))
        send(motion(ACTION_MOVE, 1020, finger(0), finger(1, x = 400f)))
        send(motion(ACTION_POINTER_UP, 1030, finger(0), finger(1, x = 400f), actionIndex = 1))
        send(motion(ACTION_UP, 1040, finger(0)))

        assertThat(recorder.calls)
            .containsExactly(
                "nav ACTION_DOWN",
                "nav ACTION_POINTER_DOWN(1)",
                "nav ACTION_MOVE",
                "nav ACTION_POINTER_UP(1)",
                "nav ACTION_UP",
            ).inOrder()
    }

    @Test
    fun mouse_goesToNavigation_scrollToo() {
        val mouse = Pointer(0, MotionEvent.TOOL_TYPE_MOUSE)
        send(motion(ACTION_DOWN, 1000, mouse))
        send(motion(ACTION_UP, 1010, mouse))
        val scroll = motion(ACTION_SCROLL, 1020, mouse)

        assertThat(router.onGenericMotionEvent(scroll)).isTrue()
        assertThat(recorder.calls).containsExactly("nav ACTION_DOWN", "nav ACTION_UP", "scroll").inOrder()
    }

    @Test
    fun fingerWithin300msAfterStylusUp_isIgnored_after300msPans() {
        penStroke(1000) // up at 1020
        recorder.calls.clear()

        fingerPan(1020 + InputRouter.PALM_QUIET_MS - 1)
        assertThat(recorder.calls).isEmpty()

        fingerPan(1020 + InputRouter.PALM_QUIET_MS)
        assertThat(recorder.calls).contains("nav ACTION_DOWN")
    }

    @Test
    fun fingerWhileHovering_isIgnored() {
        router.onGenericMotionEvent(motion(ACTION_HOVER_ENTER, 1000, pen(0)))
        router.onGenericMotionEvent(motion(ACTION_HOVER_MOVE, 1200, pen(0)))

        fingerPan(1400)
        assertThat(recorder.calls).isEmpty()

        router.onGenericMotionEvent(motion(ACTION_HOVER_EXIT, 1500, pen(0)))
        fingerPan(1500 + InputRouter.PALM_QUIET_MS)
        assertThat(recorder.calls).contains("nav ACTION_DOWN")
    }

    @Test
    fun hoverDuringFingerPan_cancelsIt() {
        send(motion(ACTION_DOWN, 1000, finger(0)))
        router.onGenericMotionEvent(motion(ACTION_HOVER_ENTER, 1010, pen(0)))
        send(motion(ACTION_MOVE, 1020, finger(0, y = 300f)))
        send(motion(ACTION_UP, 1030, finger(0, y = 300f)))

        assertThat(recorder.calls).containsExactly("nav ACTION_DOWN", "navCancel").inOrder()
    }

    @Test
    fun palmDownThenPen_cancelsPanAndDraws_palmIgnored() {
        send(motion(ACTION_DOWN, 1000, finger(0)))
        send(motion(ACTION_POINTER_DOWN, 1010, finger(0), pen(1), actionIndex = 1))
        send(motion(ACTION_MOVE, 1020, finger(0, y = 150f), pen(1, y = 200f)))
        send(motion(ACTION_POINTER_UP, 1030, finger(0), pen(1, y = 200f), actionIndex = 1))
        send(motion(ACTION_UP, 1040, finger(0)))

        assertThat(recorder.calls)
            .containsExactly("nav ACTION_DOWN", "navCancel", "penDown 1", "penMove 1", "penUp 1")
            .inOrder()
    }

    @Test
    fun fingerJoiningStylusGesture_isIgnored() {
        send(motion(ACTION_DOWN, 1000, pen(0)))
        send(motion(ACTION_POINTER_DOWN, 1010, pen(0), finger(1), actionIndex = 1))
        send(motion(ACTION_MOVE, 1020, pen(0, y = 200f), finger(1, y = 300f)))
        send(motion(ACTION_POINTER_UP, 1030, pen(0, y = 200f), finger(1), actionIndex = 1))
        send(motion(ACTION_UP, 1040, pen(0, y = 200f)))

        assertThat(recorder.calls).containsExactly("penDown 0", "penMove 0", "penUp 0").inOrder()
    }

    @Test
    fun largeTouch_isIgnored_largeSecondFingerCancelsPan() {
        send(motion(ACTION_DOWN, 1000, finger(0, touchMajor = LARGE_PX + 1f)))
        send(motion(ACTION_UP, 1010, finger(0)))
        assertThat(recorder.calls).isEmpty()

        send(motion(ACTION_DOWN, 2000, finger(0)))
        send(motion(ACTION_POINTER_DOWN, 2010, finger(0), finger(1, touchMajor = LARGE_PX + 1f), actionIndex = 1))
        send(motion(ACTION_MOVE, 2020, finger(0, y = 300f), finger(1)))
        assertThat(recorder.calls).containsExactly("nav ACTION_DOWN", "navCancel").inOrder()
    }

    @Test
    fun canceledEvents_endGesturesWithoutEffect() {
        send(motion(ACTION_DOWN, 1000, pen(0)))
        send(motion(ACTION_UP, 1010, pen(0), flags = FLAG_CANCELED))
        send(motion(ACTION_DOWN, 2000, pen(0)))
        send(motion(ACTION_CANCEL, 2010, pen(0)))
        send(motion(ACTION_DOWN, 3000, finger(0)))
        send(motion(ACTION_UP, 3010, finger(0), flags = FLAG_CANCELED))
        send(motion(ACTION_DOWN, 4000, finger(0), flags = FLAG_CANCELED))

        assertThat(recorder.calls)
            .containsExactly("penDown 0", "penCancel 0", "penDown 0", "penCancel 0", "nav ACTION_DOWN", "navCancel")
            .inOrder()
    }

    @Test
    fun secondStylusPointer_isIgnored() {
        send(motion(ACTION_DOWN, 1000, pen(0)))
        send(motion(ACTION_POINTER_DOWN, 1010, pen(0), pen(1), actionIndex = 1))
        send(motion(ACTION_POINTER_UP, 1020, pen(0), pen(1), actionIndex = 1))
        send(motion(ACTION_UP, 1030, pen(0)))

        assertThat(recorder.calls).containsExactly("penDown 0", "penUp 0").inOrder()
    }

    @Test
    fun capabilities_refinedByObservedTiltAndHover() {
        assertThat(router.capabilities).isEqualTo(StylusCapabilities.NONE)

        send(motion(ACTION_DOWN, 1000, pen(0, tiltRad = 0.5f)))
        assertThat(router.capabilities.tilt).isTrue()
        assertThat(router.capabilities.hover).isFalse()

        router.onGenericMotionEvent(motion(ACTION_HOVER_MOVE, 2000, pen(0)))
        assertThat(router.capabilities.hover).isTrue()
    }

    @Test
    fun fingerHover_isNotConsumed() {
        assertThat(router.onGenericMotionEvent(motion(ACTION_HOVER_MOVE, 1000, finger(0)))).isFalse()
    }

    private companion object {
        const val LARGE_PX = 200f
    }
}
