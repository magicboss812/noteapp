package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import android.view.MotionEvent.ACTION_DOWN
import android.view.MotionEvent.ACTION_HOVER_ENTER
import android.view.MotionEvent.ACTION_HOVER_MOVE
import android.view.MotionEvent.ACTION_MOVE
import android.view.MotionEvent.ACTION_UP
import android.view.MotionEvent.TOOL_TYPE_ERASER
import android.view.MotionEvent.TOOL_TYPE_STYLUS
import android.view.PointerIcon
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.PerfMonitor
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.toViewPx
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.flow.BlockPlacement
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.flow.caretSpot
import dev.folio.core.text.flow.selectionBoxes
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.layout.FlowTextStyle
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.feature.editor.canvas.TouchEvents.event
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.SelectionHighlight
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus
import kotlinx.coroutines.CompletableDeferred
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

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_LANDSCAPE)
class CanvasHostTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 20)
    private var host: CanvasHostView? = null
    private var wet: FakeWetSurface? = null
    private val coveredPx = mutableIntStateOf(0)

    private val hostShown = mutableStateOf(true)

    private fun show() {
        compose.setContent {
            if (!hostShown.value) return@setContent
            CanvasHost(controller, Modifier.fillMaxSize(), onHost = { host = it }, coveredBottomPx = coveredPx.intValue) { context ->
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
    fun fingerTap_onAPage_asksTheTextControllerToFocusThePagePoint() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val page =
            controller.document.value.pages[0]
                .id
        val at = controller.viewport.toViewPx(page, PointPt(100f, 200f))

        tapAt(at.x, at.y)

        assertThat(texts.focusCalls).hasSize(1)
        val (pageId, xPt, yPt) = texts.focusCalls.single()
        assertThat(pageId).isEqualTo(page)
        assertThat(xPt).isWithin(1e-2f).of(100f)
        assertThat(yPt).isWithin(1e-2f).of(200f)
    }

    // A one-line paragraph focused on the first page, shown as the live field.
    private fun showFocusedField(
        texts: FakeTextController,
        topPt: Float = 100f,
    ): LiveTextField {
        val page =
            controller.document.value.pages[0]
                .id
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>())
        val block = MarkdownDocument.parse("alpha beta").blocks.first()
        val layout =
            BlockLayouter(resolver, FontMetricsCache(resolver))
                .layout(block, 400f, FlowTextStyle(), LayoutGrid(unitPt = 20f, indentStepPt = 20f))
        val focus = TextFocus(FlowId("body"), block.id, 0, 0, 1)
        val spec = TextFieldSpec(focus, block, layout, BlockPlacement(0, page, 40f, topPt, 400f))
        compose.runOnUiThread {
            texts.field.value = spec
            controller.canvasText.value = CanvasText(emptyMap(), spec)
        }
        settle()
        return checkNotNull(hostView().liveTextField)
    }

    @Test
    fun focusedField_tilesAlreadyFresh_showsTheTextAtOnceAndBlurLeavesNoPicture() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val field = showFocusedField(texts)

        assertThat(field.visibility).isEqualTo(View.VISIBLE)
        assertThat(field.isMasked).isFalse()
        compose.runOnUiThread { controller.canvasText.value = CanvasText.EMPTY }
        settle()

        assertThat(field.visibility).isEqualTo(View.GONE)
        assertThat(hostView().textFields.isHandingOff).isFalse()
        assertThat(hostView().textFields.ghostView).isNull()
    }

    @Test
    fun textToolTap_creationShowsNoFieldFirst_stillTracesTapToField() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        PerfMonitor.reset()
        PerfMonitor.enabled = true
        try {
            compose.runOnUiThread {
                hostView().textFields.tapped(System.nanoTime())
                // The new flow arrives before its focus: page text without a field.
                controller.canvasText.value = CanvasText(emptyMap(), null)
            }
            settle()
            showFocusedField(texts)

            assertThat(PerfMonitor.snapshot()[TextFieldHost.TRACE_ARRIVE]?.count).isEqualTo(1L)
        } finally {
            PerfMonitor.enabled = false
            PerfMonitor.reset()
        }
    }

    // A heading, a paragraph and a list on the first page, selected from inside the heading into the list.
    private fun showThreeBlockSelection(): SelectionHighlight {
        val page =
            controller.document.value.pages[0]
                .id
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>())
        val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
        val flowId = FlowId("body")
        val bodyRect = checkNotNull(TextZones.bodyRect(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template))
        val frame = FlowFrame(ObjectId("frame0"), flowId, bodyRect, 0, FrameRole.BODY, autoGrow = false)
        val markdown =
            "# Selection\n\nA paragraph of body text that runs past the end of its first line and keeps going onto the next one.\n\n" +
                "- first item\n- second item"
        val pages = controller.document.value.pages.size
        val doc =
            ModelFixtures.document(
                List(pages) { if (it == 0) ModelFixtures.page(page.value, listOf(frame)) else ModelFixtures.page("p$it") },
                flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
            )
        val layouts = PageTexts.layouts(doc, layouter)
        val layout = checkNotNull(layouts[flowId])
        val blocks = layout.markdown.blocks
        val selection = TextSelection(Caret(blocks[0].id, 4), Caret(blocks[2].id, 9))
        val highlight =
            SelectionHighlight(layout.selectionBoxes(selection), layout.caretSpot(selection.anchor), layout.caretSpot(selection.focus))
        compose.runOnUiThread {
            controller.document.value = doc
            controller.canvasText.value = CanvasText(PageTexts.of(doc, layouts), null, highlight)
        }
        settle()
        return highlight
    }

    @Test
    fun selectionOverThreeBlocks_showsBoxesAndBothHandles() {
        controller.textController = FakeTextController()
        show()

        val highlight = showThreeBlockSelection()

        assertThat(highlight.boxes.size).isAtLeast(3)
        assertThat(hostView().selectionView.shown).isSameInstanceAs(highlight)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_selectionThreeBlocks.png")
    }

    @Test
    fun fingerOnTheFocusHandle_dragsThatEndInsteadOfPanning() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val focus = checkNotNull(showThreeBlockSelection().focus)
        val vp = controller.viewport
        val line = vp.toViewPx(focus.pageId, PointPt(focus.xPt, (focus.topPt + focus.bottomPt) / 2))
        val knobY = vp.toViewPx(focus.pageId, PointPt(focus.xPt, focus.bottomPt)).y + 8f * vp.density
        val offsetBefore = vp.offsetYPx

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, line.x to knobY))
            view.dispatchTouchEvent(event(ACTION_MOVE, 50, line.x to knobY + 100f))
            view.dispatchTouchEvent(event(ACTION_UP, 100, line.x to knobY + 100f))
        }

        assertThat(vp.offsetYPx).isEqualTo(offsetBefore)
        assertThat(texts.focusCalls).isEmpty()
        val drag = texts.extends.single().split(' ', ',')
        assertThat(drag[0]).isEqualTo("caret")
        assertThat(drag[1].toFloat()).isWithin(0.1f).of(focus.xPt)
        assertThat(drag[2].toFloat()).isWithin(0.1f).of((focus.topPt + focus.bottomPt) / 2 + 100f / vp.scale)
    }

    @Test
    fun focusedField_hostLeavesTheScreen_hidesTheKeyboard() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        showFocusedField(texts)
        val imm = ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)
        assertThat(shadowOf(imm).isSoftInputVisible).isTrue()

        compose.runOnUiThread { hostShown.value = false }
        settle()

        assertThat(shadowOf(imm).isSoftInputVisible).isFalse()
    }

    @Test
    fun focusedField_keyboardRequest_waitsForTheCaretFrameAndBlurCancelsIt() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        showFocusedField(texts)
        val imm = ApplicationProvider.getApplicationContext<Context>().getSystemService(InputMethodManager::class.java)
        assertThat(shadowOf(imm).isSoftInputVisible).isTrue()
        compose.runOnUiThread { controller.canvasText.value = CanvasText(emptyMap(), null) }
        settle()
        assertThat(shadowOf(imm).isSoftInputVisible).isFalse()
        val spec = checkNotNull(texts.field.value)

        var shownAtOnce = false
        var askedAtOnce = true
        compose.runOnUiThread {
            controller.canvasText.value = CanvasText(emptyMap(), spec)
            shownAtOnce = hostView().liveTextField?.spec != null
            askedAtOnce = shadowOf(imm).isSoftInputVisible
            controller.canvasText.value = CanvasText(emptyMap(), null)
        }
        settle()

        assertThat(shownAtOnce).isTrue()
        assertThat(askedAtOnce).isFalse()
        assertThat(shadowOf(imm).isSoftInputVisible).isFalse()
    }

    @Test
    fun firstEventsAfterAttach_reachTheRouter_tapFocusesAndStrokeDraws() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val view = hostView()
        assertThat(view.touchCounts.fingerDowns + view.touchCounts.stylusDowns).isEqualTo(0)

        tapAt(500f, 500f)
        stylusDownUp(500f, 900f)

        assertThat(view.touchCounts.fingerDowns).isEqualTo(1)
        assertThat(view.touchCounts.fingerTaps).isEqualTo(1)
        assertThat(texts.focusCalls).hasSize(1)
        assertThat(view.touchCounts.stylusDowns).isEqualTo(1)
        assertThat(view.inkStats.started).isEqualTo(1)
    }

    @Test
    fun focusedFieldLowOnThePage_keyboardComesUp_scrollsTheCaretLineAboveIt() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val field = showFocusedField(texts, topPt = 300f)
        val view = hostView()
        val before = controller.viewport.offsetYPx
        val lineBottomPx = field.caretLineBottomPx()
        assertThat(lineBottomPx).isLessThan(view.height.toFloat())
        val keyboardPx = (view.height - lineBottomPx + view.height / 4f).toInt()

        compose.runOnUiThread { coveredPx.intValue = keyboardPx }
        settle()

        assertThat(controller.viewport.offsetYPx).isLessThan(before)
        assertThat(field.caretLineBottomPx()).isAtMost(view.height - keyboardPx.toFloat())
        assertThat(field.caretLineTopPx()).isAtLeast(0f)
    }

    private fun stylusDownUp(
        xPx: Float,
        yPx: Float,
    ) = compose.runOnUiThread {
        hostView().dispatchTouchEvent(event(ACTION_DOWN, 3000, xPx to yPx, toolType = TOOL_TYPE_STYLUS))
        hostView().dispatchTouchEvent(event(ACTION_UP, 3100, xPx + 50f to yPx, toolType = TOOL_TYPE_STYLUS))
    }

    @Test
    fun stylusDown_onTheFocusedBlock_endsTheEditingAndThePenStillDraws() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val field = showFocusedField(texts)
        val insideX = field.x + 10f
        val insideY = field.y + field.height / 2f

        stylusDownUp(insideX, field.y + field.height + 400f)
        val blurredByStrokeBelow = texts.blurCount
        stylusDownUp(insideX, insideY)

        assertThat(blurredByStrokeBelow).isEqualTo(0)
        assertThat(texts.blurCount).isEqualTo(1)
        assertThat(field.visibility).isEqualTo(View.GONE)
        assertThat(hostView().inkStats.started).isEqualTo(2)
    }

    @Test
    fun stylusHover_overTheFocusedBlock_endsTheEditingBeforeThePenTouches() {
        val texts = FakeTextController().also { controller.textController = it }
        show()
        val field = showFocusedField(texts)

        compose.runOnUiThread {
            hostView().rootView.dispatchGenericMotionEvent(
                event(ACTION_HOVER_ENTER, 1900, field.x + 10f to field.y + field.height / 2f, toolType = TOOL_TYPE_STYLUS),
            )
        }

        assertThat(texts.blurCount).isEqualTo(1)
        assertThat(field.visibility).isEqualTo(View.GONE)
    }

    @Test
    fun stylusDown_onTheFocusedBlockInReadMode_keepsTheEditing() {
        val texts = FakeTextController().also { controller.textController = it }
        controller.activeTool = CanvasTool.NAVIGATE
        show()
        val field = showFocusedField(texts)

        stylusDownUp(field.x + 10f, field.y + field.height / 2f)

        assertThat(texts.blurCount).isEqualTo(0)
    }

    @Test
    fun fingerTap_readMode_doesNotFocus() {
        val texts = FakeTextController().also { controller.textController = it }
        controller.activeTool = CanvasTool.NAVIGATE
        show()

        tapAt(500f, 500f)

        assertThat(texts.focusCalls).isEmpty()
    }

    @Test
    fun fingerTap_withoutTextController_isIgnored() {
        show()
        val offsetBefore = controller.viewport.offsetYPx

        tapAt(500f, 500f)

        assertThat(controller.viewport.offsetYPx).isEqualTo(offsetBefore)
    }

    @Test
    fun fingerDrag_pans_doesNotFocus() {
        val texts = FakeTextController().also { controller.textController = it }
        show()

        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, 500f to 1500f))
            view.dispatchTouchEvent(event(ACTION_MOVE, 50, 500f to 1000f))
            view.dispatchTouchEvent(event(ACTION_UP, 100, 500f to 1000f))
        }

        assertThat(texts.focusCalls).isEmpty()
    }

    private fun tapAt(
        xPx: Float,
        yPx: Float,
    ) {
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 0, xPx to yPx))
            view.dispatchTouchEvent(event(ACTION_UP, 80, xPx to yPx))
        }
    }

    @Test
    fun finishedStroke_committedToThePageUnderThePen_wetCopyRemovedOnceItsTileIsDrawn() {
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
        settle()

        val objects =
            controller.document.value.pageBodies[page]
                ?.objects
        assertThat(objects?.single()?.bounds?.left).isWithin(1f).of(100f)
        assertThat(wet?.removed).containsExactly(key)
        assertThat(hostView().handoffStats).isEqualTo(HandoffStats(committed = 1, pending = 0, removed = 1))
        assertThat(hostView().tileStats.contentBytes).isGreaterThan(0L) // the tile under the stroke was rendered
    }

    @Test
    fun finishedStroke_viewDetachedWhileCommitSuspends_strokeStillCommittedAndWetCopyRemoved() {
        show()
        val gate = CompletableDeferred<Unit>()
        controller.commitGate = gate
        val page = ModelFixtures.page("p0").id
        var key: Any? = null
        compose.runOnUiThread {
            val view = hostView()
            view.dispatchTouchEvent(event(ACTION_DOWN, 2000, 500f to 500f, toolType = TOOL_TYPE_STYLUS))
            view.dispatchTouchEvent(event(ACTION_UP, 2100, 600f to 500f, toolType = TOOL_TYPE_STYLUS))
            key = wet?.deliver(page, floatArrayOf(100f, 150f), floatArrayOf(100f, 100f))
            // Leaving the editor right after the pen lifts: the view detaches before the session applied the commit.
            (view.parent as ViewGroup).removeView(view)
        }
        gate.complete(Unit)
        shadowOf(Looper.getMainLooper()).idle()

        assertThat(
            controller.document.value.pageBodies[page]
                ?.objects,
        ).hasSize(1)
        assertThat(wet?.removed).containsExactly(key)
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
    fun stylusHover_fromWindowRoot_reachesHostAndShowsRing() {
        show()

        // The window delivers hover at the root (ViewRootImpl -> dispatchPointerEvent ->
        // dispatchGenericMotionEvent), through Compose's AndroidView holder, not straight into the host (D-017).
        compose.runOnUiThread {
            hostView().rootView.dispatchGenericMotionEvent(
                event(ACTION_HOVER_ENTER, 1900, 600f to 500f, toolType = TOOL_TYPE_STYLUS),
            )
            hostView().rootView.dispatchGenericMotionEvent(
                event(ACTION_HOVER_MOVE, 2000, 610f to 500f, toolType = TOOL_TYPE_STYLUS),
            )
        }
        compose.waitForIdle()

        assertThat(hostView().isHoverRingShown).isTrue()
        assertThat(hostView().hoverEventCount).isEqualTo(2)
    }

    @Test
    fun stylusHover_ringEnabled_hidesSystemPointerIcon() {
        show()
        val hover = event(ACTION_HOVER_MOVE, 2000, 600f to 500f, toolType = TOOL_TYPE_STYLUS)
        compose.runOnUiThread { hostView().dispatchGenericMotionEvent(hover) }

        val icon = hostView().onResolvePointerIcon(hover, 0)

        assertThat(icon).isEqualTo(PointerIcon.getSystemIcon(hostView().context, PointerIcon.TYPE_NULL))
    }

    @Test
    fun stylusHover_preferenceOff_keepsSystemPointerIcon() {
        controller.stylusPreferences = StylusPreferences(hoverCursor = false)
        show()
        val hover = event(ACTION_HOVER_MOVE, 2000, 600f to 500f, toolType = TOOL_TYPE_STYLUS)
        compose.runOnUiThread { hostView().dispatchGenericMotionEvent(hover) }

        val icon = hostView().onResolvePointerIcon(hover, 0)

        assertThat(icon).isNotEqualTo(PointerIcon.getSystemIcon(hostView().context, PointerIcon.TYPE_NULL))
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

    @Test
    fun setDocument_publishedFromWorkerThread_appliedOnMainThread() {
        show()

        // DocumentSession.ensureLoaded publishes loaded pages from an IO worker (P04-T09 device failure:
        // the view applied it there and its tile maps threw ConcurrentModificationException).
        val doc = controller.document.value
        val worker = Thread { controller.document.value = doc.copy(pageBodies = doc.pageBodies.remove(doc.pages[0].id)) }
        worker.start()
        worker.join()
        settle()

        assertThat(controller.loadRequests).contains(doc.pages[0].id)
        assertThat(controller.loadThreads.toSet()).containsExactly(Looper.getMainLooper().thread)
    }
}
