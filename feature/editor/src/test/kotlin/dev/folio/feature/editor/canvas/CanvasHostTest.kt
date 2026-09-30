package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.canvas.TouchEvents.event
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

// Xiaomi Pad 7 in landscape: 3200x2136 px at 440 dpi (docs/notes/device.md).
private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"

private class FakeCanvasController(
    pages: Int,
) : CanvasController {
    private val density =
        ApplicationProvider
            .getApplicationContext<Context>()
            .resources.displayMetrics.density
    override val document = MutableStateFlow<Document>(ModelFixtures.document(List(pages) { ModelFixtures.page("p$it") }))
    override val viewport = Viewport(density)
    override val activeBrush = BrushSpec(BrushKind.BALLPOINT, 0xFF1A1A1A.toInt(), 0.9f, 1)

    // Tiles render inline on the main thread, so a frame after the idle request shows them.
    override val renderDispatcher = Dispatchers.Unconfined
    val loadRequests = ArrayList<PageId>()

    override fun loadPages(ids: Collection<PageId>) {
        loadRequests += ids
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_LANDSCAPE)
class CanvasHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 20)
    private var host: CanvasHostView? = null
    private var wet: FakeWetSurface? = null

    private fun show() {
        compose.setContent {
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }) { context ->
                CanvasHostView(context, controller) { FakeWetSurface(it).also { surface -> wet = surface } }
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
    fun canvasHost_twentyPages_showsFitWidthStackAtTop() {
        show()

        val vp = controller.viewport
        assertThat(vp.layout?.size).isEqualTo(20)
        assertThat(vp.zoom).isWithin(1e-4f).of(1f)
        assertThat(vp.offsetYPx).isEqualTo(0.0)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_stackFitWidth.png")
    }

    @Test
    fun canvasHost_zoomedOut_showsSeveralPageCards() {
        show()

        compose.runOnUiThread { hostView().animateZoom(0.3f, 0.3f, durationMs = 0) }
        settle()

        assertThat(controller.viewport.zoom).isWithin(1e-4f).of(0.3f)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_stackZoomedOut.png")
    }

    @Test
    fun canvasHost_fingerDragPans_stylusDragDrawsWetStrokeWithoutPanning() {
        show()
        val vp = controller.viewport

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, 500f to 1500f))
            view.dispatchTouchEvent(event(ACTION_MOVE, 500, 500f to 1000f))
            view.dispatchTouchEvent(event(ACTION_UP, 1500, 500f to 1000f))
        }
        val panned = vp.offsetYPx
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, 500f to 1500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_MOVE, 2100, 500f to 500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 2200, 500f to 500f, toolType = TOOL_TYPE_STYLUS))
        }

        assertThat(panned).isWithin(1e-3).of(-500.0)
        assertThat(vp.offsetYPx).isEqualTo(panned)
        assertThat(wet?.calls).containsExactly("start 0", "add 0", "finish 0").inOrder()
        assertThat(wet?.lastSpec).isEqualTo(controller.activeBrush)
        assertThat(hostView().inkStats).isEqualTo(InkStats(started = 1, finished = 1, canceled = 0))
    }

    @Test
    fun canvasHost_fingerRightAfterStylus_isPalmAndDoesNotPan() {
        show()
        val vp = controller.viewport

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, 500f to 1500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, 500f to 1400f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_DOWN, 2200, 500f to 1500f))
            view.dispatchTouchEvent(event(ACTION_MOVE, 2300, 500f to 1000f))
            view.dispatchTouchEvent(event(ACTION_UP, 2400, 500f to 1000f))
        }

        assertThat(vp.offsetYPx).isEqualTo(0.0)
    }

    @Test
    fun scrollToPage_jump_putsPageTopOneGapBelowViewTop() {
        show()

        compose.runOnUiThread { hostView().scrollToPage(5, durationMs = 0) }

        val top = controller.viewport.toViewPx(ModelFixtures.page("p5").id, PointPt(0f, 0f)).y
        assertThat(top).isWithin(0.05f).of(16f * controller.viewport.density)
    }

    @Test
    fun setPages_documentChanges_relayoutsStack() {
        show()

        controller.document.value =
            ModelFixtures.document(List(3) { ModelFixtures.page("q$it", spec = PageSpec.Custom(400f, 300f)) })
        compose.waitForIdle()

        assertThat(controller.viewport.layout?.size).isEqualTo(3)
    }

    @Test
    fun idleRequest_rendersVisibleBackgroundTiles_contentOfEmptyPagesNeedsNoBitmaps() {
        show()

        val stats = hostView().tileStats
        assertThat(stats.pending).isEqualTo(0)
        assertThat(stats.backgroundBytes).isGreaterThan(0L)
        assertThat(stats.contentTiles).isGreaterThan(0)
        assertThat(stats.contentBytes).isEqualTo(0L)
        assertThat(controller.loadRequests).isEmpty() // every body of the fixture is loaded
    }

    @Test
    fun setDocument_unloadedVisiblePage_asksTheSessionToLoadIt() {
        show()

        val doc = controller.document.value
        controller.document.value = doc.copy(pageBodies = doc.pageBodies.remove(doc.pages[0].id))
        settle()

        assertThat(controller.loadRequests).contains(doc.pages[0].id)
    }
}
