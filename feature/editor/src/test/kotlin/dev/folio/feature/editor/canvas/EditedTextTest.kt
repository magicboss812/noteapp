package dev.folio.feature.editor.canvas

import android.content.Context
import android.os.Looper
import android.view.MotionEvent
import android.view.View
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
import dev.folio.core.model.TextZones
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.flow.EditedBlock
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.TextFieldSpec
import dev.folio.feature.editor.text.TextFocus
import dev.folio.feature.editor.text.fieldOf
import dev.folio.feature.editor.text.highlightOf
import dev.folio.feature.editor.text.shownIn
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/** The edited block drawn by the canvas overlay from the page layout, with its caret (P07-T10, A-058). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class EditedTextTest {
    @get:Rule
    val compose = createComposeRule()

    private val controller = FakeCanvasController(pages = 3)
    private var host: CanvasHostView? = null
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val bodyRect = checkNotNull(TextZones.bodyRect(ModelFixtures.A4, template))
    private val flowId = FlowId("body")

    private fun show() {
        controller.textController = FakeTextController()
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

    private fun edited(): EditedTextView = checkNotNull(hostView().textFields.editedView)

    // The body flow [markdown] over the first two pages with block [pick] focused, the caret at [caret] and the selection
    // from [anchor] when given; the canvas gets the page text of the edited layout, like EditorText makes it.
    private fun showFocused(
        markdown: String,
        pick: (List<MarkdownBlock>) -> Int,
        caret: (String) -> Int,
        anchor: ((String) -> Int)? = null,
    ): TextFieldSpec {
        val pages = controller.document.value.pages
        val frames = List(2) { FlowFrame(ObjectId("frame$it"), flowId, bodyRect, it, FrameRole.BODY, autoGrow = false) }
        val doc =
            ModelFixtures.document(
                List(pages.size) { ModelFixtures.page(pages[it].id.value, if (it < 2) listOf(frames[it]) else emptyList()) },
                flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
            )
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext<Context>())
        val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
        val blocks = checkNotNull(PageTexts.flowLayout(doc, flowId, layouter)).markdown.blocks
        val index = pick(blocks)
        val block = blocks[index]
        val layouts = mapOf(flowId to checkNotNull(PageTexts.flowLayout(doc, flowId, layouter, editBlockId = block.id)))
        val focus =
            TextFocus(flowId, block.id, index, caret(block.source), 1, anchor = anchor?.let { Caret(block.id, it(block.source)) })
        val spec = checkNotNull(layouts.fieldOf(focus))
        compose.runOnUiThread {
            controller.document.value = doc
            controller.canvasText.value = CanvasText(PageTexts.of(doc, layouts, focus.shownIn(layouts)), spec, layouts.highlightOf(focus))
        }
        settle()
        return spec
    }

    private val longParagraph = (1..90).joinToString(" ") { "word$it" }

    @Test
    fun focusedParagraph_acrossAPageBreak_overlayDrawsBothPartsAndTheFieldNothing() {
        show()
        val lines = TextZones.capacityUnits(bodyRect.heightPt, template.spacingPt)
        val markdown = (1 until lines - 2).joinToString("\n\n") { "Line $it" } + "\n\n" + longParagraph

        val spec = showFocused(markdown, pick = { it.lastIndex }, caret = { it.length })
        // Page 2 at the view top, then zoomed out around the view center: the break sits in the upper part of the view.
        compose.runOnUiThread {
            hostView().scrollToPage(1, 0L)
            hostView().animateZoom(1f, ZOOM_OUT, 0L)
        }
        settle()

        val drawn = checkNotNull(edited().shown)
        val pages = controller.document.value.pages
        assertThat(drawn.pieces.map { it.pageId }).containsExactly(pages[0].id, pages[1].id).inOrder()
        assertThat(drawn).isSameInstanceAs(spec.edited)
        assertThat(edited().drawsText).isTrue()
        assertThat(edited().caretOffset).isEqualTo(longParagraph.length)
        assertThat(checkNotNull(drawn.caretSpot(longParagraph.length)).pageId).isEqualTo(pages[1].id)
        val field = checkNotNull(hostView().liveTextField)
        assertThat(field.isOverlaid).isTrue()
        assertThat(field.isCursorVisible).isFalse()
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_editedAcrossPageBreak.png")
    }

    @Test
    fun caretContinuedOnTheNextPage_keyboardComesUp_scrollsTheOverlayCaretLineAboveIt() {
        show()
        val lines = TextZones.capacityUnits(bodyRect.heightPt, template.spacingPt)
        val markdown = (1 until lines - 2).joinToString("\n\n") { "Line $it" } + "\n\n" + longParagraph
        showFocused(markdown, pick = { it.lastIndex }, caret = { it.length })
        val view = hostView()
        val keyboardPx = view.height / 3

        compose.runOnUiThread { view.caret.coveredBottomPx = keyboardPx }
        settle()

        // The caret is on page 2, where the overlay draws it, not where the field's own unbroken layout has it.
        val line = FloatArray(2)
        assertThat(edited().linePx(longParagraph.length, line)).isTrue()
        assertThat(line[1]).isAtMost(view.height - keyboardPx.toFloat())
        assertThat(line[0]).isAtLeast(0f)
    }

    @Test
    fun focusedEmptyBlock_showsTheCaretAtOnceWithTheLineHeight() {
        show()

        val spec = showFocused("alpha\n\n\n\n\n\nbeta", pick = { blocks -> blocks.indexOfFirst { it.source.isBlank() } }, caret = { 0 })

        val drawn = checkNotNull(edited().shown)
        val spot = checkNotNull(drawn.caretSpot(0))
        assertThat(edited().caretOffset).isEqualTo(0)
        assertThat(spot.bottomPt - spot.topPt).isEqualTo(spec.layout.boxUnits * template.spacingPt)
        assertThat(spot.xPt).isEqualTo(bodyRect.left)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_editedEmptyBlockCaret.png")
    }

    @Test
    fun selectionInALineWithMarkers_boxesAndHandlesFollowTheSourceAndNoCaret() {
        show()
        val source = "Some ==marked== and **bold** words with \$a_2\$ inline"

        showFocused(source, pick = { 0 }, caret = { it.indexOf("words") + 5 }, anchor = { it.indexOf("marked") })

        val drawn = checkNotNull(edited().shown)
        val highlight = checkNotNull(hostView().selectionView.shown)
        val box = highlight.boxes.single()
        assertThat(edited().caretOffset).isEqualTo(EditedTextView.NO_CARET)
        assertThat(box.rect.left).isEqualTo(checkNotNull(drawn.caretSpot(source.indexOf("marked"))).xPt)
        assertThat(box.rect.right).isEqualTo(checkNotNull(drawn.caretSpot(source.indexOf("words") + 5)).xPt)
        assertThat(checkNotNull(highlight.anchor).xPt).isEqualTo(box.rect.left)
        assertThat(checkNotNull(highlight.focus).xPt).isEqualTo(box.rect.right)
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_editedSelectionWithMarkers.png")
    }

    @Test
    fun selectedWord_afterATouchSelection_showsHandlesAndTheFloatingMenuAboveIt() {
        show()
        val source = "Some words to select here"
        showFocused(source, pick = { 0 }, caret = { it.indexOf("select") + 6 }, anchor = { it.indexOf("select") })

        compose.runOnUiThread { hostView().selectionView.showMenu() }
        settle()

        val overlay = hostView().selectionView
        assertThat(overlay.isMenuShown).isTrue()
        val ids = ArrayList<Int>()
        overlay.onMenu = { ids += it }
        compose.onRoot().captureRoboImage("src/test/screenshots/CanvasHost_selectedWordWithMenu.png")
        // A tap on Copy runs it and closes the menu.
        val at = FloatArray(2)
        assertThat(overlay.menuButtonCenter(android.R.id.copy, at)).isTrue()
        val (x, y) = at[0] to at[1]
        compose.runOnUiThread {
            assertThat(overlay.menuTouch(TouchEvents.event(MotionEvent.ACTION_DOWN, 0L, x to y))).isTrue()
            assertThat(overlay.menuTouch(TouchEvents.event(MotionEvent.ACTION_UP, 60L, x to y))).isTrue()
        }
        assertThat(ids).containsExactly(android.R.id.copy)
        assertThat(overlay.isMenuShown).isFalse()
    }

    @Test
    fun blur_tilesAlreadyFresh_overlayDrawsNothing() {
        show()
        showFocused("alpha beta", pick = { 0 }, caret = { 2 })

        compose.runOnUiThread { controller.canvasText.value = CanvasText.EMPTY }
        settle()

        assertThat(checkNotNull(hostView().liveTextField).visibility).isEqualTo(View.GONE)
        assertThat(edited().shown).isNull()
        assertThat(edited().left).isNull()
        assertThat(hostView().textFields.ghostView).isNull()
    }

    @Test
    fun typedText_beforeTheModelAnswers_overlayDrawsTheRetypedBlock() {
        show()
        showFocused("alpha", pick = { 0 }, caret = { it.length })
        val laidOut = checkNotNull(edited().shown)
        val texts = controller.textController as FakeTextController
        val retyped = EditedBlock(laidOut.blockId, "alphax", laidOut.layout, laidOut.pieces)
        texts.retyped = retyped

        compose.runOnUiThread { checkNotNull(hostView().liveTextField).append("x") }

        assertThat(texts.retypeCalls).containsExactly("alphax")
        assertThat(edited().shown).isSameInstanceAs(retyped)
        assertThat(edited().caretOffset).isEqualTo("alphax".length)
    }

    private companion object {
        const val ZOOM_OUT = 0.45f
    }
}
