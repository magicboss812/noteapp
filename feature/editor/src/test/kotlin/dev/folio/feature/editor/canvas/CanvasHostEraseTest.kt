package dev.folio.feature.editor.canvas

import android.os.Looper
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_ERASER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The eraser in the canvas host (06-ink-input.md#erasers): one command per gesture, pending strokes, the ring. */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi") // Xiaomi Pad 7 in landscape (docs/notes/device.md)
class CanvasHostEraseTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 20)
    private var host: CanvasHostView? = null
    private var wet: FakeWetSurface? = null
    private val page = ModelFixtures.page("p0").id

    private fun show() {
        compose.setContent {
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }) { context ->
                CanvasHostView(context, controller) {
                    FakeWetSurface(it).also { surface -> wet = surface }
                }
            }
        }
        settle()
    }

    /** Runs the idle tile request (100 ms after the last viewport change) and the resulting frame. */
    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CanvasHostView.IDLE_MS * 2))
        compose.waitForIdle()
    }

    private fun hostView(): CanvasHostView = requireNotNull(host)

    @Test
    fun eraserTool_gestureAcrossStroke_oneCommandRemovesIt_undoRestores() {
        val stroke = strokeOn(page)
        val before = controller.document.value
        show()
        controller.activeTool = CanvasTool.ERASER

        erase()
        settle()

        assertThat(
            controller.document.value.pageBodies[page]
                ?.objects,
        ).isEmpty()
        assertThat(controller.commands).hasSize(1)
        assertThat(hostView().eraseStats).isEqualTo(EraseStats(gestures = 1, committed = 1, strokes = 1, discarded = 0))
        assertThat(wet?.calls).isEmpty() // the eraser draws no wet ink
        val undone =
            controller.commands
                .single()
                .execute(before)
                .inverse
        assertThat(
            undone
                .execute(controller.document.value)
                .doc.pageBodies[page]
                ?.objects,
        ).containsExactly(stroke)
    }

    @Test
    fun eraserEnd_erasesWhateverTheTool_rejectedCommandKeepsTheStroke() {
        val stroke = strokeOn(page)
        show()
        controller.accept = false

        erase(toolType = TOOL_TYPE_ERASER)
        settle()

        assertThat(
            controller.document.value.pageBodies[page]
                ?.objects,
        ).containsExactly(stroke)
        assertThat(hostView().eraseStats).isEqualTo(EraseStats(gestures = 1, committed = 1, strokes = 1, discarded = 1))
    }

    @Test
    fun eraseRightAfterPenLift_strokeStillPendingInTheHandoff_oneCommandRemovesIt_undoRestores() {
        show()
        val gate = CompletableDeferred<Unit>()
        controller.commitGate = gate // the session has not applied the stroke yet when the eraser starts
        var key: Any? = null
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, 500f to 500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, 600f to 500f, toolType = TOOL_TYPE_STYLUS))
            key = wet?.deliver(page, floatArrayOf(150f, 250f), floatArrayOf(100f, 100f))
        }
        assertThat(hostView().handoffStats.pending).isEqualTo(1)
        controller.activeTool = CanvasTool.ERASER
        val (from, to) = swipe()
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2200, from, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_MOVE, 2250, to, toolType = TOOL_TYPE_STYLUS))
        }
        compose.waitForIdle()
        val ringMidErase = hostView().isHoverRingShown
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_eraseRing.png")
        // The stroke eraser leaves nothing of it: the wet copy goes with the preview.
        assertThat(wet?.removed).containsExactly(key)

        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()
        val committed = controller.document.value
        compose.runOnUiThread {
            hostView().dispatchTouchEvent(event(ACTION_UP, 2300, to, toolType = TOOL_TYPE_STYLUS))
        }
        settle()

        assertThat(ringMidErase).isTrue()
        assertThat(hostView().isHoverRingShown).isFalse()
        assertThat(
            controller.document.value.pageBodies[page]
                ?.objects,
        ).isEmpty()
        assertThat(controller.commands).hasSize(1)
        assertThat(hostView().eraseStats).isEqualTo(EraseStats(gestures = 1, committed = 1, strokes = 1, discarded = 0))
        assertThat(hostView().handoffStats).isEqualTo(HandoffStats(committed = 1, pending = 0, removed = 1))
        val undone =
            controller.commands
                .single()
                .execute(committed)
                .inverse
        assertThat(committed.pageBodies[page]?.objects).hasSize(1)
        assertThat(
            undone
                .execute(controller.document.value)
                .doc.pageBodies[page]
                ?.objects,
        ).containsExactlyElementsIn(committed.pageBodies[page]?.objects.orEmpty())
    }

    /** Adds a 200 pt horizontal ballpoint stroke at y = 100 pt on [page]. */
    private fun strokeOn(page: PageId): InkStroke {
        val inputs =
            StrokeInputs(
                FloatArray(201) { 100f + it },
                FloatArray(201) { 100f },
                FloatArray(201) { it * 2f },
                null,
                null,
                null,
                InputTool.SYNTHETIC,
            )
        val stroke = InkStroke.of(ObjectId("s"), controller.activeBrush, inputs)
        controller.document.value = AddObjects(page, listOf(stroke)).execute(controller.document.value).doc
        return stroke
    }

    /** View px of a swipe from (200, 50) to (200, 150) pt on [page]. */
    private fun swipe(): Pair<Pair<Float, Float>, Pair<Float, Float>> {
        val vp = controller.viewport
        val from = vp.toViewPx(page, PointPt(200f, 50f))
        val to = vp.toViewPx(page, PointPt(200f, 150f))
        return (from.x to from.y) to (to.x to to.y)
    }

    /** A stylus swipe across [strokeOn]'s stroke. */
    private fun erase(toolType: Int = TOOL_TYPE_STYLUS) {
        val (from, to) = swipe()
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, from, toolType = toolType))
            view.dispatchTouchEvent(event(ACTION_MOVE, 2050, to.first to (from.second + to.second) / 2f, toolType = toolType))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, to, toolType = toolType))
        }
    }
}
