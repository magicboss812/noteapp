package dev.folio.core.ink.input

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class StylusFeaturesTest {
    // Focus Pen after P01-S7: pressure, tilt, orientation, hover; no button state reaches the app.
    private val focusPen = StylusCapabilities(pressure = true, tilt = true, orientation = true, hover = true, primaryButton = false)
    private val all = focusPen.copy(primaryButton = true)
    private val defaults = StylusPreferences.DEFAULT

    @Test
    fun focusPen_tiltAndHoverOn_buttonEraserOff() {
        assertThat(StylusFeatures.tiltShading(focusPen)).isTrue()
        assertThat(StylusFeatures.hoverCursor(focusPen, defaults)).isTrue()
        assertThat(StylusFeatures.buttonEraser(focusPen, defaults)).isFalse()
    }

    @Test
    fun noCapabilities_everyFeatureOff() {
        val none = StylusCapabilities.NONE

        assertThat(StylusFeatures.tiltShading(none)).isFalse()
        assertThat(StylusFeatures.hoverCursor(none, defaults)).isFalse()
        assertThat(StylusFeatures.buttonEraser(none, defaults)).isFalse()
    }

    @Test
    fun preferencesOff_supportedFeaturesOff() {
        val off = StylusPreferences(hoverCursor = false, penButton = PenButtonAction.NONE)

        assertThat(StylusFeatures.hoverCursor(all, off)).isFalse()
        assertThat(StylusFeatures.buttonEraser(all, off)).isFalse()
        assertThat(StylusFeatures.buttonEraser(all, defaults)).isTrue()
    }

    @Test
    fun buttonErases_needsHeldButtonAndSupport() {
        val held = motion(ACTION_DOWN, 0, pen(0), buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)
        val secondary = motion(ACTION_DOWN, 0, pen(0), buttonState = MotionEvent.BUTTON_STYLUS_SECONDARY)
        val free = motion(ACTION_DOWN, 0, pen(0))

        assertThat(StylusFeatures.buttonErases(held, all, defaults)).isTrue()
        assertThat(StylusFeatures.buttonErases(secondary, all, defaults)).isTrue()
        assertThat(StylusFeatures.buttonErases(free, all, defaults)).isFalse()
        assertThat(StylusFeatures.buttonErases(held, focusPen, defaults)).isFalse()
        assertThat(StylusFeatures.buttonErases(held, all, defaults.copy(penButton = PenButtonAction.NONE))).isFalse()
    }

    @Test
    fun buttonErases_firstButtonDownObserved_enablesItself() {
        val held = motion(ACTION_DOWN, 0, pen(0), buttonState = MotionEvent.BUTTON_STYLUS_PRIMARY)

        val observed = focusPen.observe(held, 0)

        assertThat(StylusFeatures.buttonErases(held, observed, defaults)).isTrue()
    }

    @Test
    fun visibleSettings_hidesUnsupportedRows() {
        assertThat(StylusFeatures.visibleSettings(focusPen))
            .containsExactly(StylusSetting.TILT_SHADING, StylusSetting.HOVER_CURSOR)
            .inOrder()
        assertThat(StylusFeatures.visibleSettings(all))
            .containsExactly(StylusSetting.TILT_SHADING, StylusSetting.HOVER_CURSOR, StylusSetting.PEN_BUTTON)
            .inOrder()
        assertThat(StylusFeatures.visibleSettings(StylusCapabilities.NONE)).isEmpty()
    }
}
