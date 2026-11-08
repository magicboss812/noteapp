package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith

/** Finger gestures for touch text selection (07-text-engine.md#selection-as-built) from MotionEvent sequences. */
@RunWith(AndroidJUnit4::class)
class TextTouchTest {
    private var takes = true
    private val touch =
        TextTouch(
            slopPx = SLOP,
            longPressMs = LONG_MS,
            doubleTapMs = DOUBLE_MS,
            doubleTapSlopPx = DOUBLE_SLOP,
            handleAt = { x, y -> x in HANDLE_X - 10f..HANDLE_X + 10f && y in HANDLE_Y - 10f..HANDLE_Y + 10f },
            takesLongPress = { _, _ -> takes },
        )

    private fun on(
        action: Int,
        timeMs: Long,
        x: Float = 100f,
        y: Float = 100f,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
    ): TextGesture = touch.onTouch(event(action, timeMs, x to y, toolType = toolType))

    @Test
    fun tap_releasedInPlace_isATap() {
        assertThat(on(MotionEvent.ACTION_DOWN, 0)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_UP, 80)).isEqualTo(TextGesture.TAP)
    }

    @Test
    fun secondTap_soonAndNear_isADoubleTap() {
        on(MotionEvent.ACTION_DOWN, 0)
        on(MotionEvent.ACTION_UP, 80)
        on(MotionEvent.ACTION_DOWN, 200, x = 104f)

        assertThat(on(MotionEvent.ACTION_UP, 260, x = 104f)).isEqualTo(TextGesture.DOUBLE_TAP)
        // A third tap starts over.
        on(MotionEvent.ACTION_DOWN, 400)
        assertThat(on(MotionEvent.ACTION_UP, 450)).isEqualTo(TextGesture.TAP)
    }

    @Test
    fun secondTap_tooLate_isATap() {
        on(MotionEvent.ACTION_DOWN, 0)
        on(MotionEvent.ACTION_UP, 80)
        on(MotionEvent.ACTION_DOWN, 80 + DOUBLE_MS + 1)

        assertThat(on(MotionEvent.ACTION_UP, 80 + DOUBLE_MS + 50)).isEqualTo(TextGesture.TAP)
    }

    @Test
    fun press_heldPastTheTimeout_isALongPressAndItsMovesDrag() {
        on(MotionEvent.ACTION_DOWN, 0)
        assertThat(touch.longPressDue(LONG_MS - 1)).isEqualTo(TextGesture.PASS)

        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.LONG_PRESS)
        assertThat(on(MotionEvent.ACTION_MOVE, LONG_MS + 50, x = 300f)).isEqualTo(TextGesture.LONG_DRAG)
        assertThat(on(MotionEvent.ACTION_UP, LONG_MS + 90, x = 300f)).isEqualTo(TextGesture.LONG_END)
    }

    @Test
    fun longPress_refusedByTheText_passesAndIsNoTap() {
        takes = false
        on(MotionEvent.ACTION_DOWN, 0)

        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_MOVE, LONG_MS + 50, x = 300f)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_UP, LONG_MS + 90, x = 300f)).isEqualTo(TextGesture.PASS)
    }

    @Test
    fun drag_pastTheSlopBeforeTheTimeout_pansAndNeverLongPresses() {
        on(MotionEvent.ACTION_DOWN, 0)

        assertThat(on(MotionEvent.ACTION_MOVE, 50, x = 100f + SLOP + 1f)).isEqualTo(TextGesture.PAN)
        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_MOVE, LONG_MS + 10, x = 300f)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_UP, LONG_MS + 20, x = 300f)).isEqualTo(TextGesture.PASS)
    }

    @Test
    fun down_onAHandle_belongsToTheHandleUntilTheFingerLifts() {
        assertThat(on(MotionEvent.ACTION_DOWN, 0, HANDLE_X, HANDLE_Y)).isEqualTo(TextGesture.HANDLE)
        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_MOVE, LONG_MS + 10, x = 400f)).isEqualTo(TextGesture.HANDLE)
        assertThat(on(MotionEvent.ACTION_UP, LONG_MS + 20, x = 400f)).isEqualTo(TextGesture.HANDLE)
    }

    @Test
    fun stylus_neverSelects() {
        on(MotionEvent.ACTION_DOWN, 0, toolType = MotionEvent.TOOL_TYPE_STYLUS)

        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.PASS)
        assertThat(on(MotionEvent.ACTION_UP, LONG_MS + 10, toolType = MotionEvent.TOOL_TYPE_STYLUS)).isEqualTo(TextGesture.PASS)
    }

    @Test
    fun secondFinger_beforeTheTimeout_isAPinchNotALongPress() {
        on(MotionEvent.ACTION_DOWN, 0)
        touch.onTouch(event(MotionEvent.ACTION_POINTER_DOWN, 30, 100f to 100f, 200f to 200f, actionIndex = 1))

        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.PASS)
    }

    @Test
    fun disabled_readMode_passesALongPress() {
        touch.onTouch(event(MotionEvent.ACTION_DOWN, 0, 100f to 100f), enabled = false)

        assertThat(touch.longPressDue(LONG_MS)).isEqualTo(TextGesture.PASS)
    }

    private companion object {
        const val SLOP = 16f
        const val LONG_MS = 400L
        const val DOUBLE_MS = 300L
        const val DOUBLE_SLOP = 100f
        const val HANDLE_X = 500f
        const val HANDLE_Y = 500f
    }
}
