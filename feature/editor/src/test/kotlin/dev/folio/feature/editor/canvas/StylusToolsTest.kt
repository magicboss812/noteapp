package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.BUTTON_STYLUS_PRIMARY
import android.view.MotionEvent.TOOL_TYPE_ERASER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.input.PenButtonAction
import dev.folio.core.ink.input.StylusCapabilities
import dev.folio.core.ink.input.StylusFeatures
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.ink.input.StylusTarget
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith

private class NamedTarget(
    private val name: String,
    private val calls: MutableList<String>,
) : StylusTarget {
    override fun onStylusDown(
        event: MotionEvent,
        pointerId: Int,
        eraser: Boolean,
    ) {
        calls += "$name down"
    }

    override fun onStylusMove(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "$name move"
    }

    override fun onStylusUp(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "$name up"
    }

    override fun onStylusCancel(
        event: MotionEvent,
        pointerId: Int,
    ) {
        calls += "$name cancel"
    }
}

@RunWith(AndroidJUnit4::class)
class StylusToolsTest {
    private val calls = ArrayList<String>()
    private var capabilities = StylusCapabilities.NONE.copy(pressure = true, hover = true)
    private var preferences = StylusPreferences.DEFAULT
    private var tool = CanvasTool.PEN
    private val tools =
        StylusTools(
            NamedTarget("pen", calls),
            NamedTarget("eraser", calls),
            buttonErases = { StylusFeatures.buttonErases(it, capabilities, preferences) },
            navigate = NamedTarget("navigate", calls),
        ) { tool }

    /** A stylus gesture; [buttonAtDown] holds the stylus button at touch down only; [eraserEnd] for TOOL_TYPE_ERASER. */
    private fun gesture(
        buttonAtDown: Int,
        eraserEnd: Boolean = false,
    ) {
        val type = if (eraserEnd) TOOL_TYPE_ERASER else TOOL_TYPE_STYLUS
        tools.onStylusDown(event(ACTION_DOWN, 0, 100f to 100f, toolType = type, buttonState = buttonAtDown), 0, eraser = eraserEnd)
        tools.onStylusMove(event(ACTION_MOVE, 10, 100f to 200f, toolType = type), 0)
        tools.onStylusUp(event(ACTION_UP, 20, 100f to 200f, toolType = type), 0)
    }

    @Test
    fun readMode_tipEraserEndAndButton_allNavigate() {
        capabilities = capabilities.copy(primaryButton = true)
        tool = CanvasTool.NAVIGATE

        gesture(buttonAtDown = 0)
        gesture(buttonAtDown = 0, eraserEnd = true)
        gesture(buttonAtDown = BUTTON_STYLUS_PRIMARY)

        assertThat(calls.distinct()).containsExactly("navigate down", "navigate move", "navigate up").inOrder()
        assertThat(calls).hasSize(9)
        assertThat(tools.erases(event(ACTION_DOWN, 0, 1f to 1f, toolType = TOOL_TYPE_ERASER), eraserEnd = true)).isFalse()
    }

    @Test
    fun readModeLeft_eraserEndErasesAgain() {
        tool = CanvasTool.NAVIGATE
        gesture(buttonAtDown = 0)
        tool = CanvasTool.PEN

        gesture(buttonAtDown = 0, eraserEnd = true)

        assertThat(calls.takeLast(3)).containsExactly("eraser down", "eraser move", "eraser up").inOrder()
    }

    @Test
    fun heldButton_buttonsReported_wholeGestureErases() {
        capabilities = capabilities.copy(primaryButton = true)

        gesture(buttonAtDown = BUTTON_STYLUS_PRIMARY)

        assertThat(calls).containsExactly("eraser down", "eraser move", "eraser up").inOrder()
    }

    @Test
    fun heldButton_noButtonCapability_draws() {
        gesture(buttonAtDown = BUTTON_STYLUS_PRIMARY)

        assertThat(calls).containsExactly("pen down", "pen move", "pen up").inOrder()
    }

    @Test
    fun heldButton_actionNone_draws() {
        capabilities = capabilities.copy(primaryButton = true)
        preferences = preferences.copy(penButton = PenButtonAction.NONE)

        gesture(buttonAtDown = BUTTON_STYLUS_PRIMARY)

        assertThat(calls).containsExactly("pen down", "pen move", "pen up").inOrder()
    }

    @Test
    fun noButton_buttonsReported_draws() {
        capabilities = capabilities.copy(primaryButton = true)

        gesture(buttonAtDown = 0)

        assertThat(calls).containsExactly("pen down", "pen move", "pen up").inOrder()
    }
}
