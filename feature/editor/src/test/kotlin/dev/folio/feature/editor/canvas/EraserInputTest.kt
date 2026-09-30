package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import android.view.MotionEvent.ACTION_CANCEL
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.erase.EraseResult
import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusTarget
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.Test
import org.junit.runner.RunWith

// Robolectric for MotionEvent; the host libink.so (ink-nativeloader-jvm) meshes strokes for the stroke eraser.
@RunWith(AndroidJUnit4::class)
class EraserInputTest {
    private val stroke =
        InkStroke.of(
            ObjectId("s"),
            BrushSpec(BrushKind.BALLPOINT, 0xFF000000.toInt(), 1f, 1),
            StrokeInputs(
                FloatArray(201) { 100f + it },
                FloatArray(201) { 100f },
                FloatArray(201) { it * 2f },
                null,
                null,
                null,
                InputTool.SYNTHETIC,
            ),
        )
    private val pages = List(3) { ModelFixtures.page("p$it", if (it == 1) listOf(stroke) else emptyList()) }
    private val viewport = Viewport(DENSITY).apply { setPages(pages.map { it.toRef() }, VIEW_W, VIEW_H) }
    private var options = EraserOptions(EraserMode.STROKE, 4f)
    private val previews = ArrayList<EraseResult?>()
    private val finished = ArrayList<EraseResult>()

    // Unconfined: the worker erases inline, so every preview arrives before the event call returns.
    private val eraser =
        EraserInput(
            viewport,
            body = { id -> pages.firstOrNull { it.id == id } },
            options = { options },
            scope = CoroutineScope(Dispatchers.Unconfined),
            worker = Dispatchers.Unconfined,
            onPreview = { previews += it },
            onFinished = { finished += it },
        )

    private fun at(
        action: Int,
        timeMs: Long,
        xPt: Float,
        yPt: Float,
    ): MotionEvent {
        val p = viewport.toViewPx(PageId("p1"), PointPt(xPt, yPt))
        return event(action, timeMs, p.x to p.y, toolType = TOOL_TYPE_STYLUS)
    }

    @Test
    fun gestureOnSecondPage_previewsTheHit_finishesWithOneResult() {
        viewport.zoomBy(1.7f, 300f, 900f)
        viewport.panBy(0f, -2500f)
        eraser.onStylusDown(at(ACTION_DOWN, 0, 150f, 50f), 0, eraser = false)
        assertThat(previews).isEmpty()

        eraser.onStylusMove(at(ACTION_MOVE, 10, 150f, 150f), 0)
        eraser.onStylusUp(at(ACTION_UP, 20, 150f, 160f), 0)

        assertThat(previews.map { it?.replacements?.keys }).containsExactly(setOf(stroke.id))
        val result = finished.single()
        assertThat(result.pageId).isEqualTo(PageId("p1"))
        assertThat(result.replacements).containsExactly(stroke.id, emptyList<InkStroke>())
        assertThat(eraser.startedCount).isEqualTo(1)
    }

    @Test
    fun partialMode_splitsAtTheEraserInPagePoints() {
        options = EraserOptions(EraserMode.PARTIAL, 4f)
        eraser.onStylusDown(at(ACTION_DOWN, 0, 150f, 50f), 0, eraser = false)
        eraser.onStylusUp(at(ACTION_UP, 20, 150f, 150f), 0)

        val pieces = finished.single().replacements.getValue(stroke.id)
        assertThat(pieces.map { it.inputs.x.last() }.first()).isWithin(1f).of(145f)
        assertThat(pieces.map { it.inputs.x.first() }.last()).isWithin(1f).of(155f)
    }

    @Test
    fun miss_finishesEmpty() {
        eraser.onStylusDown(at(ACTION_DOWN, 0, 150f, 20f), 0, eraser = false)
        eraser.onStylusUp(at(ACTION_UP, 20, 250f, 20f), 0)

        assertThat(previews).isEmpty()
        assertThat(finished.single().isEmpty).isTrue()
    }

    @Test
    fun cancel_clearsThePreview_finishesNothing() {
        eraser.onStylusDown(at(ACTION_DOWN, 0, 150f, 50f), 0, eraser = false)
        eraser.onStylusMove(at(ACTION_MOVE, 10, 150f, 150f), 0)
        eraser.onStylusCancel(at(ACTION_CANCEL, 20, 150f, 150f), 0)

        assertThat(previews.last()).isNull()
        assertThat(finished).isEmpty()
        assertThat(eraser.canceledCount).isEqualTo(1)
    }

    @Test
    fun stylusTools_eraserEndOrEraserTool_goToTheEraser() {
        val calls = ArrayList<String>()

        fun target(name: String) =
            object : StylusTarget {
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
        var tool = CanvasTool.PEN
        val tools = StylusTools(target("pen"), target("eraser")) { tool }
        val e = at(ACTION_MOVE, 0, 0f, 0f)

        tools.onStylusDown(e, 0, eraser = false)
        tool = CanvasTool.ERASER // a tool change mid-gesture does not move the gesture
        tools.onStylusMove(e, 0)
        tools.onStylusUp(e, 0)
        tools.onStylusDown(e, 0, eraser = false)
        tools.onStylusCancel(e, 0)
        tool = CanvasTool.PEN
        tools.onStylusDown(e, 0, eraser = true)
        tools.onStylusUp(e, 0)

        assertThat(calls)
            .containsExactly("pen down", "pen move", "pen up", "eraser down", "eraser cancel", "eraser down", "eraser up")
            .inOrder()
    }

    private companion object {
        const val DENSITY = 2.5f
        const val VIEW_W = 2136f
        const val VIEW_H = 3200f
    }
}
