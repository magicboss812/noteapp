package dev.folio.core.ink.input

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusCapabilitiesTest {
    @Test
    fun fromAxes_focusPenRanges_pressureTiltOrientationHoverNoButton() {
        val axes = setOf(MotionEvent.AXIS_PRESSURE, MotionEvent.AXIS_TILT, MotionEvent.AXIS_ORIENTATION, MotionEvent.AXIS_DISTANCE)

        val caps = StylusCapabilities.fromAxes { it in axes }

        assertThat(
            caps,
        ).isEqualTo(StylusCapabilities(pressure = true, tilt = true, orientation = true, hover = true, primaryButton = false))
    }

    @Test
    fun detect_noDevice_isNone() {
        assertThat(StylusCapabilities.detect(null)).isEqualTo(StylusCapabilities.NONE)
    }

    @Test
    fun observe_nothingNew_returnsSameInstance() {
        val caps = StylusCapabilities.NONE.copy(pressure = true)
        val event = motion(ACTION_DOWN, 0, pen(0))

        assertThat(caps.observe(event, 0)).isSameInstanceAs(caps)
    }

    @Test
    fun observe_stylusButton_enablesPrimaryButton() {
        val event = motion(ACTION_DOWN, 0, pen(0), buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)

        assertThat(StylusCapabilities.NONE.observe(event, 0).primaryButton).isTrue()
    }
}
