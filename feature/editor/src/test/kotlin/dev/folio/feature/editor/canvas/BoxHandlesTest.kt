package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_FINGER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.feature.editor.canvas.TouchEvents.event
import dev.folio.feature.editor.text.BoxPiece
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.SelectedBox
import dev.folio.feature.editor.text.TextFocus
import dev.folio.feature.editor.text.fieldOf
import dev.folio.feature.editor.text.shownIn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import kotlin.math.abs
import kotlin.math.roundToInt

/** The chrome of the selected text box: outline, grip, knobs, toolbar, and the move and resize drags (P07-T13). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class BoxHandlesTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 3)
    private var host: CanvasHostView? = null
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val boxFlow = FlowId("box")
    private val boxId = ObjectId("box-frame")

    private fun show() {
        compose.setContent {
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }) { context ->
                CanvasHostView(context, controller) { FakeWetSurface(it) }
            }
        }
        settle()
    }

    private fun settle() {
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CanvasHostView.IDLE_MS * 2))
        compose.waitForIdle()
    }

    private fun hostView(): CanvasHostView = requireNotNull(host)

    private fun firstPage(): PageId =
        controller.document.value.pages[0]
            .id

    // A two-line box on the first page, selected in Preview or (with the caret at its end) in Edit.
    private fun showBox(
        editing: Boolean = false,
        topPt: Float = 180f,
    ): RectPt {
        val pages = controller.document.value.pages
        val page = pages[0].id
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>())
        val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
        val start = TextZones.newBoxRect(ModelFixtures.A4, template, 120f, topPt)
        val rect = TextZones.grownBox(start, 2, template.spacingPt)
        val frame = FlowFrame(boxId, boxFlow, rect, 0, FrameRole.BOX, autoGrow = true)
        val doc =
            ModelFixtures.document(
                List(pages.size) { if (it == 0) ModelFixtures.page(page.value, listOf(frame)) else ModelFixtures.page(pages[it].id.value) },
                flows = listOf(ModelFixtures.flow(boxFlow.value, "A free text box that holds two lines of text.")),
            )
        val block = checkNotNull(PageTexts.flowLayout(doc, boxFlow, layouter)).markdown.blocks.first()
        val layout = checkNotNull(PageTexts.flowLayout(doc, boxFlow, layouter, editBlockId = if (editing) block.id else null))
        val layouts = mapOf(boxFlow to layout)
        val focus = if (editing) TextFocus(boxFlow, block.id, 0, block.source.length, 1) else null
        val selected = SelectedBox(boxFlow, editing, listOf(BoxPiece(page, boxId, rect, layout.outlineOf(0))), movable = true)
        compose.runOnUiThread {
            controller.document.value = doc
            controller.canvasText.value =
                CanvasText(PageTexts.of(doc, layouts, focus?.shownIn(layouts)), layouts.fieldOf(focus), box = selected)
        }
        settle()
        return rect
    }

    private fun onRule(yPt: Float): Boolean {
        val n = (yPt - template.marginTopPt) / template.spacingPt
        return abs(n - n.roundToInt()) < 1e-3f
    }

    private fun drag(
        from: Pair<Float, Float>,
        to: Pair<Float, Float>,
        stylus: Boolean = false,
    ) {
        val tool = if (stylus) TOOL_TYPE_STYLUS else TOOL_TYPE_FINGER
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, from, toolType = tool))
            view.dispatchTouchEvent(event(ACTION_MOVE, 50, to, toolType = tool))
            view.dispatchTouchEvent(event(ACTION_UP, 100, to, toolType = tool))
        }
    }

    private fun tap(at: Pair<Float, Float>) {
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, at))
            view.dispatchTouchEvent(event(ACTION_UP, 60, at))
        }
    }

    private fun center(rect: RectPt): Pair<Float, Float> {
        val p = controller.viewport.toViewPx(firstPage(), PointPt((rect.left + rect.right) / 2, (rect.top + rect.bottom) / 2))
        return p.x to p.y
    }

    private fun grip(rect: RectPt): Pair<Float, Float> {
        val p = controller.viewport.toViewPx(firstPage(), PointPt((rect.left + rect.right) / 2, rect.top))
        return p.x to p.y
    }

    @Test
    fun preview_showsOutlineGripKnobsAndToolbarAboveTheBox() {
        val texts = FakeTextController().also { controller.textController = it }
        show()

        val rect = showBox()

        val handles = hostView().boxHandles
        assertThat(handles.shown).isEqualTo(firstPage() to rect)
        assertThat(handles.editing).isFalse()
        assertThat(handles.toolbarCovers(-1f, -1f)).isFalse()
        assertThat(handles.toolbar.below).isFalse()
        assertThat(texts.field.value).isNull()
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_boxPreview.png")
    }

    @Test
    fun edit_showsTheEditButtonActiveAndTheCaret() {
        controller.textController = FakeTextController()
        show()

        showBox(editing = true)

        assertThat(hostView().boxHandles.editing).isTrue()
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_boxEdit.png")
    }

    @Test
    fun boxAtThePageTop_toolbarFlipsBelowIt() {
        controller.textController = FakeTextController()
        show()
        val rect = showBox(topPt = template.marginTopPt)
        // The box top near the view top: no room above it for the pill.
        val topPx = controller.viewport.toViewPx(firstPage(), PointPt(rect.left, rect.top)).y
        compose.runOnUiThread {
            controller.viewport.panBy(0f, TOP_PX - topPx)
            hostView().invalidate()
            hostView().boxHandles.invalidate()
        }
        settle()

        val handles = hostView().boxHandles
        assertThat(handles.toolbarCovers(-1f, -1f)).isFalse()
        assertThat(handles.toolbar.below).isTrue()
        // Below the outline of the first line (the text fits in one).
        val lineBottomPx = controller.viewport.toViewPx(firstPage(), PointPt(rect.left, rect.top + template.spacingPt)).y
        assertThat(handles.toolbar.pill.top).isGreaterThan(lineBottomPx)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_boxToolbarBelow.png")
    }

    @Test
    fun fingerDragInsideAPreviewBox_movesIt_xUnsnapped_yInWholeLines_noPan() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()
        val vp = controller.viewport
        val offsetBefore = vp.offsetYPx
        val (x, y) = center(rect)
        val dy = 3.4f * template.spacingPt * vp.scale

        drag(x to y, x + 60f to y + dy)

        assertThat(vp.offsetYPx).isEqualTo(offsetBefore)
        assertThat(texts.focusCalls).isEmpty()
        val (flow, page, moved) = texts.movedBoxes.single()
        assertThat(flow).isEqualTo(boxFlow)
        assertThat(page).isEqualTo(firstPage())
        assertThat(onRule(moved.top)).isTrue()
        assertThat(moved.top).isWithin(1e-2f).of(rect.top + 3f * template.spacingPt)
        assertThat(moved.widthPt).isWithin(1e-3f).of(rect.widthPt)
        assertThat(moved.left).isWithin(0.05f).of(rect.left + 60f / vp.scale)
    }

    @Test
    fun fingerHoldsStillInsideAPreviewBox_thenDrags_movesItAndSelectsNothing() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()
        val (x, y) = center(rect)
        val dy = 2f * template.spacingPt * controller.viewport.scale

        compose.runOnUiThread { hostView().dispatchTouchEvent(event(ACTION_DOWN, 0, x to y)) }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(HOLD_MS))
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_MOVE, HOLD_MS, x + 40f to y + dy))
            view.dispatchTouchEvent(event(ACTION_UP, HOLD_MS + 50, x + 40f to y + dy))
        }

        assertThat(texts.movedBoxes).hasSize(1)
        assertThat(texts.selections).isEmpty()
        assertThat(texts.focusCalls).isEmpty()
    }

    private fun key(
        key: BoxKey,
        down: Boolean,
        smooth: Boolean = false,
    ) {
        compose.runOnUiThread { hostView().boxKeys.onKey(BoxKeyEvent(key, down, smooth)) }
    }

    @Test
    fun arrowKeyPresses_moveTheBoxLive_oneMovePerHold_shiftLeavesItBetweenTheLines_upSnapsBack() {
        // The press steps apply at once; the held speed curve is BoxKeyMotionTest's (no display frames here).
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()
        val handles = hostView().boxHandles

        key(BoxKey.RIGHT, down = true)
        val held = handles.preview!!.second
        assertThat(held.left).isWithin(1e-3f).of(rect.left + BoxKeyCurve.PRESS_PT)
        assertThat(held.top).isEqualTo(rect.top)
        assertThat(texts.movedBoxes).isEmpty()
        key(BoxKey.RIGHT, down = false)
        val (_, page, right) = texts.movedBoxes.single()
        assertThat(page).isEqualTo(firstPage())
        assertThat(right.left).isWithin(1e-3f).of(held.left)
        assertThat(right.top).isEqualTo(rect.top)

        // The next hold starts where the last one left the box (its move still landing), Shift+Down leaves the lines.
        key(BoxKey.DOWN, down = true, smooth = true)
        key(BoxKey.DOWN, down = false)
        val shifted = texts.movedBoxes[1].third
        assertThat(shifted.left).isEqualTo(right.left)
        assertThat(onRule(shifted.top)).isFalse()
        assertThat(shifted.top).isWithin(1e-3f).of(rect.top + BoxKeyCurve.PRESS_PT)

        key(BoxKey.UP, down = true)
        key(BoxKey.UP, down = false)
        assertThat(texts.movedBoxes).hasSize(3)
        assertThat(texts.movedBoxes[2].third.top).isWithin(1e-3f).of(rect.top)
    }

    @Test
    fun arrowKeys_onABoxInEdit_moveNothing() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        showBox(editing = true)

        key(BoxKey.RIGHT, down = true)
        key(BoxKey.RIGHT, down = false)

        assertThat(hostView().boxHandles.preview).isNull()
        assertThat(texts.movedBoxes).isEmpty()
    }

    @Test
    fun tapInsideAPreviewBox_goesToTheBoxTap_movesNothing() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()

        tap(center(rect))

        assertThat(texts.boxTaps).hasSize(1)
        assertThat(
            texts.boxTaps
                .single()
                .first.first,
        ).isEqualTo(firstPage())
        assertThat(texts.movedBoxes).isEmpty()
    }

    @Test
    fun rejectedDrop_clearsThePreview() {
        val texts = FakeTextController().also { controller.textController = it }
        texts.placeResult = false
        show()
        val rect = showBox()
        val (x, y) = grip(rect)

        drag(x to y, x + 80f to y + 2f * template.spacingPt * controller.viewport.scale)
        settle()

        assertThat(texts.movedBoxes).hasSize(1)
        assertThat(hostView().boxHandles.preview).isNull()
        assertThat(hostView().boxHandles.isDragging).isFalse()
    }

    @Test
    fun toolbarEditAndDelete_actOnRelease() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        showBox()
        val handles = hostView().boxHandles
        handles.toolbarCovers(-1f, -1f)
        val pill = handles.toolbar.pill
        val half = pill.height() / 2

        tap(pill.left + half to pill.centerY())
        tap(pill.right - half to pill.centerY())

        assertThat(texts.editBoxCount).isEqualTo(1)
        assertThat(texts.deleteBoxCount).isEqualTo(1)
        assertThat(texts.boxTaps).isEmpty()
    }

    @Test
    fun penOnTheGrip_dragsTheBoxOntoTheNextPage() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()
        val pages = controller.document.value.pages
        val target = controller.viewport.toViewPx(pages[1].id, PointPt((rect.left + rect.right) / 2, 100f))

        drag(grip(rect), target.x to target.y, stylus = true)

        val (_, page, moved) = texts.movedBoxes.single()
        assertThat(page).isEqualTo(pages[1].id)
        assertThat(onRule(moved.top)).isTrue()
        assertThat(texts.blurCount).isEqualTo(0)
    }

    @Test
    fun penOnTheGripOfAnEditedBox_dropsItToPreviewAndMovesIt() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox(editing = true)
        val (x, y) = grip(rect)

        drag(x to y, x to y + 2f * template.spacingPt * controller.viewport.scale, stylus = true)

        assertThat(texts.blurCount).isAtLeast(1)
        assertThat(texts.movedBoxes).hasSize(1)
    }

    @Test
    fun fingerOnTheGrip_readMode_pansInsteadOfMovingTheBox() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()
        controller.activeTool = CanvasTool.NAVIGATE
        val vp = controller.viewport
        val offsetBefore = vp.offsetYPx
        val (x, y) = grip(rect)

        drag(x to y, x to y - 200f)

        assertThat(texts.movedBoxes).isEmpty()
        assertThat(vp.offsetYPx).isNotEqualTo(offsetBefore)
    }

    @Test
    fun rightKnob_resizesTheWidthInMillimeters_leftEdgeStays() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val rect = showBox()
        val knob = controller.viewport.toViewPx(firstPage(), PointPt(rect.right, (rect.top + rect.bottom) / 2))

        drag(knob.x to knob.y, knob.x - 150f to knob.y + 5f)

        val placed = texts.placedBoxes.single()
        assertThat(placed.rect.left).isEqualTo(rect.left)
        assertThat(placed.rect.top).isEqualTo(rect.top)
        assertThat(placed.rect.right).isLessThan(rect.right)
        val mm = placed.rect.right / (72f / 25.4f)
        assertThat(abs(mm - mm.roundToInt())).isLessThan(1e-3f)
        assertThat(texts.movedBoxes).isEmpty()
    }

    private companion object {
        const val TOP_PX = 40f
        const val HOLD_MS = 700L
    }
}
