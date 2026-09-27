package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Test

class StylusStatsTest {
    private val stats = StylusStats()

    @Test
    fun onSample_contactAndHover_countsAndRanges() {
        stats.onSample(TOOL_STYLUS, hovering = true, pressure = 0f, tiltRad = 0.2f, orientationRad = 1f, distance = 0.5f, buttonState = 0)
        stats.onSample(
            TOOL_STYLUS,
            hovering = false,
            pressure = 0.3f,
            tiltRad = 0.6f,
            orientationRad = -1f,
            distance = 0f,
            buttonState = 32,
        )
        stats.onSample(TOOL_STYLUS, hovering = false, pressure = 0.9f, tiltRad = 0.4f, orientationRad = 0f, distance = 0f, buttonState = 0)

        assertThat(stats.hoverEvents).isEqualTo(1)
        assertThat(stats.contactEvents).isEqualTo(2)
        assertThat(stats.toolTypes[TOOL_STYLUS]).isEqualTo(3)
        assertThat(stats.pressure.min).isEqualTo(0.3f) // hover pressure is not a contact reading
        assertThat(stats.pressure.max).isEqualTo(0.9f)
        assertThat(stats.tiltRad.max).isEqualTo(0.6f)
        assertThat(stats.buttonStatesSeen).isEqualTo(32)
    }

    @Test
    fun medianIntervalMs_240HzSamplesAcrossGestures_reports240Hz() {
        var t = 0L
        repeat(2) {
            repeat(50) {
                stats.onSampleTime(t)
                t += 4_166_667L
            }
            stats.onGestureEnd()
            t += 500_000_000L // pause between strokes is not an interval
        }

        assertThat(stats.medianIntervalMs()).isWithin(0.01).of(4.166)
        val hz =
            stats
                .toJson()["sampleRateHz"]!!
                .jsonPrimitive.content
                .toDouble()
        assertThat(hz).isWithin(1.0).of(240.0)
    }

    @Test
    fun toJson_buttonsAndKeys_listed() {
        stats.onButtonPress(32)
        stats.onButtonPress(32)
        stats.onKey(KEYCODE_STYLUS_BUTTON_PRIMARY)

        val json = stats.toJson()

        assertThat(json["buttonPresses"]!!.jsonObject["32"]!!.jsonPrimitive.content).isEqualTo("2")
        assertThat(json["keyCodes"]!!.jsonObject.keys).containsExactly(KEYCODE_STYLUS_BUTTON_PRIMARY.toString())
        assertThat(json.containsKey("pressure")).isFalse()
    }

    private companion object {
        const val TOOL_STYLUS = 2
        const val KEYCODE_STYLUS_BUTTON_PRIMARY = 308
    }
}
