package dev.folio.feature.editor.canvas

import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_POINTER_DOWN
import android.view.MotionEvent.ACTION_POINTER_UP
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.widget.OverScroller
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class FingerGesturesTest {
    private val viewport = Viewport(DENSITY).apply { setPages(List(20) { ModelFixtures.page("p$it").toRef() }, VIEW_W, VIEW_H) }
    private val frames = ArrayList<Runnable>()
    private var changes = 0
    private val gestures =
        FingerGestures(
            viewport,
            OverScroller(ApplicationProvider.getApplicationContext()),
            GestureConfig(touchSlopPx = 20f, minFlingPxPerS = 100f, maxFlingPxPerS = 20_000f),
            onChanged = { changes++ },
            postFrame = { frames += it },
        )

    @Test
    fun drag_oneFinger_pansByTheWholeDelta() {
        gestures.onTouchEvent(event(ACTION_DOWN, 0, 1000f to 1500f))
        gestures.onTouchEvent(event(ACTION_MOVE, 100, 1000f to 1400f))
        gestures.onTouchEvent(event(ACTION_MOVE, 200, 1000f to 1200f))

        assertThat(viewport.offsetYPx).isWithin(1e-3).of(-300.0)
        assertThat(changes).isEqualTo(2)
    }

    @Test
    fun drag_withinTouchSlop_doesNotPan() {
        gestures.onTouchEvent(event(ACTION_DOWN, 0, 1000f to 1500f))
        gestures.onTouchEvent(event(ACTION_MOVE, 100, 1000f to 1490f))

        assertThat(viewport.offsetYPx).isEqualTo(0.0)
    }

    @Test
    fun drag_stylus_isIgnored() {
        val tracked = gestures.onTouchEvent(event(ACTION_DOWN, 0, 1000f to 1500f, toolType = TOOL_TYPE_STYLUS))
        gestures.onTouchEvent(event(ACTION_MOVE, 100, 1000f to 1000f, toolType = TOOL_TYPE_STYLUS))

        assertThat(tracked).isFalse()
        assertThat(viewport.offsetYPx).isEqualTo(0.0)
    }

    @Test
    fun pinch_spreadToDoubleSpan_doublesZoomKeepingTheMidpointFixed() {
        val docX = (1000 - viewport.offsetXPx) / viewport.scale
        val docY = (1600 - viewport.offsetYPx) / viewport.scale

        pinchOpen()

        assertThat(viewport.zoom).isWithin(1e-4f).of(2f)
        assertThat(docX * viewport.scale + viewport.offsetXPx).isWithin(0.5).of(1000.0)
        assertThat(docY * viewport.scale + viewport.offsetYPx).isWithin(0.5).of(1600.0)
    }

    @Test
    fun pinch_liftOneFinger_continuesAsPanWithoutJump() {
        pinchOpen()
        val before = viewport.offsetYPx

        gestures.onTouchEvent(event(ACTION_POINTER_UP, 300, 600f to 1600f, 1400f to 1600f, actionIndex = 0))
        gestures.onTouchEvent(event(ACTION_MOVE, 400, 1400f to 1500f, firstPointerId = 1))

        assertThat(viewport.offsetYPx - before).isWithin(1e-3).of(-100.0)
    }

    @Test
    fun swipe_fast_flingsAfterRelease() {
        gestures.onTouchEvent(event(ACTION_DOWN, 0, 1000f to 2500f))
        for (i in 1..5) gestures.onTouchEvent(event(ACTION_MOVE, i * 10L, 1000f to 2500f - i * 100f))
        gestures.onTouchEvent(event(ACTION_UP, 60, 1000f to 2000f))
        val released = viewport.offsetYPx

        assertThat(gestures.isFlinging).isTrue()
        repeat(3) {
            ShadowLooper.idleMainLooper(16, TimeUnit.MILLISECONDS)
            frames.removeAt(0).run()
        }
        assertThat(viewport.offsetYPx).isLessThan(released)
    }

    @Test
    fun swipe_newTouch_stopsFling() {
        gestures.onTouchEvent(event(ACTION_DOWN, 0, 1000f to 2500f))
        for (i in 1..5) gestures.onTouchEvent(event(ACTION_MOVE, i * 10L, 1000f to 2500f - i * 100f))
        gestures.onTouchEvent(event(ACTION_UP, 60, 1000f to 2000f))

        gestures.onTouchEvent(event(ACTION_DOWN, 100, 1000f to 1500f))

        assertThat(gestures.isFlinging).isFalse()
    }

    /** Two fingers at 800/1200 spread to 600/1400 around (1000, 1600): span 400 -> 800. */
    private fun pinchOpen() {
        gestures.onTouchEvent(event(ACTION_DOWN, 0, 800f to 1600f))
        gestures.onTouchEvent(event(ACTION_POINTER_DOWN, 50, 800f to 1600f, 1200f to 1600f, actionIndex = 1))
        gestures.onTouchEvent(event(ACTION_MOVE, 100, 700f to 1600f, 1300f to 1600f))
        gestures.onTouchEvent(event(ACTION_MOVE, 200, 600f to 1600f, 1400f to 1600f))
    }

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
    }
}
