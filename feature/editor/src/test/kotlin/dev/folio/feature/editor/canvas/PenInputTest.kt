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
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.PageId
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PenInputTest {
    private val viewport = Viewport(DENSITY).apply { setPages(List(20) { ModelFixtures.page("p$it").toRef() }, VIEW_W, VIEW_H) }
    private val surface = FakeWetSurface(ApplicationProvider.getApplicationContext())
    private var starts = 0
    private val pen =
        PenInput(
            viewport,
            surface,
            brush = { BRUSH },
            capabilities = { StylusCapabilities.NONE.copy(tilt = true) },
            onStrokeStart = { starts++ },
        )

    private fun stylus(
        action: Int,
        timeMs: Long,
        y: Float,
    ) = event(action, timeMs, 500f to y, toolType = TOOL_TYPE_STYLUS)

    @Test
    fun stroke_downMoveUp_drawsWetStrokeWithActiveBrushAndTilt() {
        pen.onStylusDown(stylus(ACTION_DOWN, 0, 500f), 0, eraser = false)
        pen.onStylusMove(stylus(ACTION_MOVE, 10, 600f), 0)
        pen.onStylusUp(stylus(ACTION_UP, 20, 600f), 0)

        assertThat(surface.calls).containsExactly("start 0", "add 0", "finish 0").inOrder()
        assertThat(surface.lastSpec).isEqualTo(BRUSH)
        assertThat(surface.lastTilt).isTrue()
        assertThat(starts).isEqualTo(1)
        assertThat(pen.stats).isEqualTo(InkStats(started = 1, finished = 1, canceled = 0))
    }

    @Test
    fun stroke_onSecondPage_mapsThatPagesPointsToViewPx() {
        viewport.zoomBy(1.7f, 300f, 900f)
        val page = PageId("p1")
        val target = viewport.toViewPx(page, PointPt(100f, 200f))

        pen.onStylusDown(stylus(ACTION_DOWN, 0, target.y), 0, eraser = false)

        assertThat(pen.pageIndex).isEqualTo(1)
        val mapped = floatArrayOf(100f, 200f)
        surface.lastStrokeToView.mapPoints(mapped)
        assertThat(mapped[0]).isWithin(0.01f).of(target.x)
        assertThat(mapped[1]).isWithin(0.01f).of(target.y)
    }

    @Test
    fun eraserPointer_drawsNothing() {
        pen.onStylusDown(stylus(ACTION_DOWN, 0, 500f), 0, eraser = true)
        pen.onStylusMove(stylus(ACTION_MOVE, 10, 600f), 0)
        pen.onStylusUp(stylus(ACTION_UP, 20, 600f), 0)

        assertThat(surface.calls).isEmpty()
    }

    @Test
    fun cancel_discardsTheStroke() {
        pen.onStylusDown(stylus(ACTION_DOWN, 0, 500f), 0, eraser = false)
        pen.onStylusCancel(stylus(ACTION_CANCEL, 10, 500f), 0)

        assertThat(surface.calls).containsExactly("start 0", "cancel").inOrder()
        assertThat(pen.stats).isEqualTo(InkStats(started = 1, finished = 0, canceled = 1))
    }

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
        val BRUSH = BrushSpec(BrushKind.PENCIL, 0xFF2563EB.toInt(), 1.2f, 1)
    }
}
