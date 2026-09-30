package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_ERASER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
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
    override var activeTool = CanvasTool.PEN
    override var eraserOptions = EraserOptions.DEFAULT
    override var stylusPreferences = StylusPreferences.DEFAULT
    val loadRequests = ArrayList<PageId>()
    var accept = true

    val commands = ArrayList<EditCommand>()

    override suspend fun execute(command: EditCommand): Boolean {
        commands += command
        if (accept) document.value = command.execute(document.value).doc
        return accept
    }

    override fun loadPages(ids: Collection<PageId>) {
        loadRequests += ids
    }

    override suspend fun commitStrokes(
        pageId: PageId,
        strokes: List<InkStroke>,
    ): Boolean {
        if (accept) document.value = AddObjects(pageId, strokes).execute(document.value).doc
        return accept
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
    private val frameCommits = ArrayList<Runnable>()

    private fun show() {
        compose.setContent {
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }) { context ->
                CanvasHostView(context, controller, afterFrameCommit = { _, action -> frameCommits += action }) {
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
    fun finishedStroke_committedToThePageUnderThePen_wetCopyRemovedAfterTheFrameShowingIt() {
        show()
        val page = ModelFixtures.page("p0").id
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, 500f to 500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, 600f to 500f, toolType = TOOL_TYPE_STYLUS))
        }
        assertThat(wet?.lastPage).isEqualTo(page)

        var key: Any? = null
        compose.runOnUiThread { key = wet?.deliver(page, floatArrayOf(100f, 150f), floatArrayOf(100f, 100f)) }
        val removedBeforeFrame = wet?.removed?.toList()
        settle()
        compose.runOnUiThread { frameCommits.forEach(Runnable::run) }

        val objects =
            controller.document.value.pageBodies[page]
                ?.objects
        assertThat(objects?.single()?.bounds?.left).isWithin(1f).of(100f)
        assertThat(removedBeforeFrame).isEmpty()
        assertThat(wet?.removed).containsExactly(key)
        assertThat(hostView().handoffStats).isEqualTo(HandoffStats(committed = 1, pending = 0, removed = 1))
        assertThat(hostView().tileStats.contentBytes).isGreaterThan(0L) // the tile under the stroke was rendered
    }

    @Test
    fun finishedStroke_sessionRejects_wetCopyDropped() {
        show()
        controller.accept = false
        val page = ModelFixtures.page("p0").id
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, 500f to 500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, 600f to 500f, toolType = TOOL_TYPE_STYLUS))
        }

        var key: Any? = null
        compose.runOnUiThread { key = wet?.deliver(page, floatArrayOf(100f, 150f), floatArrayOf(100f, 100f)) }
        settle()

        assertThat(wet?.removed).containsExactly(key)
        assertThat(hostView().handoffStats.pending).isEqualTo(0)
    }

    @Test
    fun eraserTool_gestureAcrossStroke_oneCommandRemovesIt_undoRestores() {
        val page = ModelFixtures.page("p0").id
        val stroke = strokeOn(page)
        val before = controller.document.value
        show()
        controller.activeTool = CanvasTool.ERASER

        erase(page)
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
        val page = ModelFixtures.page("p0").id
        val stroke = strokeOn(page)
        show()
        controller.accept = false

        erase(page, toolType = TOOL_TYPE_ERASER)
        settle()

        assertThat(
            controller.document.value.pageBodies[page]
                ?.objects,
        ).containsExactly(stroke)
        assertThat(hostView().eraseStats).isEqualTo(EraseStats(gestures = 1, committed = 1, strokes = 1, discarded = 1))
    }

    @Test
    fun stylusHover_showsSizeRingUntilThePenTouches() {
        show()
        controller.activeTool = CanvasTool.ERASER
        controller.eraserOptions = EraserOptions(radiusPt = 24f)

        compose.runOnUiThread {
            hostView().dispatchGenericMotionEvent(
                event(ACTION_HOVER_MOVE, 2000, 600f to 500f, toolType = TOOL_TYPE_STYLUS),
            )
        }
        compose.waitForIdle()
        val shown = hostView().isHoverRingShown
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_hoverRingEraser.png")
        compose.runOnUiThread {
            hostView().dispatchTouchEvent(event(ACTION_DOWN, 2100, 600f to 500f, toolType = TOOL_TYPE_STYLUS))
            hostView().dispatchTouchEvent(event(ACTION_UP, 2200, 600f to 500f, toolType = TOOL_TYPE_STYLUS))
        }

        assertThat(shown).isTrue()
        assertThat(hostView().isHoverRingShown).isFalse()
    }

    @Test
    fun stylusHover_preferenceOff_noRing() {
        controller.stylusPreferences = StylusPreferences(hoverCursor = false)
        show()

        compose.runOnUiThread {
            hostView().dispatchGenericMotionEvent(
                event(ACTION_HOVER_MOVE, 2000, 600f to 500f, toolType = TOOL_TYPE_STYLUS),
            )
        }

        assertThat(hostView().isHoverRingShown).isFalse()
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

    /** A stylus swipe from (200, 50) to (200, 150) pt on [page]. */
    private fun erase(
        page: PageId,
        toolType: Int = TOOL_TYPE_STYLUS,
    ) {
        val vp = controller.viewport
        val from = vp.toViewPx(page, PointPt(200f, 50f))
        val to = vp.toViewPx(page, PointPt(200f, 150f))
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, from.x to from.y, toolType = toolType))
            view.dispatchTouchEvent(event(ACTION_MOVE, 2050, to.x to (from.y + to.y) / 2f, toolType = toolType))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, to.x to to.y, toolType = toolType))
        }
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
