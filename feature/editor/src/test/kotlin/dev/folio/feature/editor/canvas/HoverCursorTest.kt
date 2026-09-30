package dev.folio.feature.editor.canvas

import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith

private class RingRecorder : HoverRing {
    val calls = ArrayList<String>()

    override fun show(
        xPx: Float,
        yPx: Float,
        radiusPx: Float,
    ) {
        calls += "show ${xPx.toInt()},${yPx.toInt()} r=$radiusPx"
    }

    override fun hide() {
        calls += "hide"
    }
}

@RunWith(AndroidJUnit4::class)
class HoverCursorTest {
    private val viewport = Viewport(DENSITY).apply { setPages(List(3) { ModelFixtures.page("p$it").toRef() }, VIEW_W, VIEW_H) }
    private val ring = RingRecorder()
    private var enabled = true
    private var radiusPt = 5f
    private val cursor = HoverCursor(ring, viewport, { enabled }, { radiusPt }, minRadiusPx = MIN_PX)

    private fun hover() = event(ACTION_HOVER_MOVE, 0, 300f to 400f, toolType = TOOL_TYPE_STYLUS)

    @Test
    fun hover_enabled_ringOfTheMarkSizeAtThePen() {
        cursor.onHover(hover())

        assertThat(ring.calls).containsExactly("show 300,400 r=${5f * viewport.scale}")
    }

    @Test
    fun hover_thinPen_ringKeepsMinimumRadius() {
        radiusPt = 0.1f

        cursor.onHover(hover())

        assertThat(ring.calls).containsExactly("show 300,400 r=$MIN_PX")
    }

    @Test
    fun hover_disabled_ringHidden() {
        enabled = false

        cursor.onHover(hover())

        assertThat(ring.calls).containsExactly("hide")
    }

    @Test
    fun hoverEnd_hidesRing() {
        cursor.onHover(hover())
        cursor.onHoverEnd()

        assertThat(ring.calls.last()).isEqualTo("hide")
    }

    private companion object {
        const val MIN_PX = 6f
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
    }
}
