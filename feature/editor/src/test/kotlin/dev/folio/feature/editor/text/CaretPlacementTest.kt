package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.FlowBox
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.PageId
import dev.folio.core.model.TextAlign
import dev.folio.core.model.TextZones
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.markdown.BlockContent
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.TextOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Text tool taps and keyboard typing with nothing focused (10-editor-ui.md#text-tool). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class CaretPlacementTest : EditorTextFixture() {
    private suspend fun blankNote(pages: Int): String =
        (
            documents.create(NewDocumentSpec("", "Blank", ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, pages)) as Outcome.Success
        ).value.path

    private suspend fun ready(
        path: String,
        test: TestScope,
    ): Triple<EditorSession, EditorText, List<PageId>> {
        val (session, documentSession) = open(path, test)
        val text = checkNotNull(session.text)
        text.awaitReady()
        documentSession.loadPages(
            documentSession.document.value.pages
                .map { it.id },
        )
        return Triple(
            session,
            text,
            documentSession.document.value.pages
                .map { it.id },
        )
    }

    private fun lineY(line: Int): Float = bodyRect.top + line * lineBoxPt + 2f

    private fun EditorSession.frames(page: PageId): List<FlowFrame> =
        documentSession.document.value
            .page(page)!!
            .objects
            .filterIsInstance<FlowFrame>()

    private val boxZone = TextZones.boxZone(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template)

    @Test
    fun placeCaret_belowTheLastBlock_startsAnAutoWidthBoxAtTheTapInsteadOfFillingTheBody() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)
            val x = bodyRect.left + 40f

            checkNotNull(session.textController).placeCaret(pages[0], x, lineY(3))
            val focus = text.focus.first { it != null }!!

            assertThat(markdown(session.documentSession)).isEqualTo("alpha")
            val box = session.frames(pages[0]).single { it.role == FrameRole.BOX }
            assertThat(box.flow).isEqualTo(focus.flowId)
            assertThat(box.autoGrow).isTrue()
            assertThat(box.rect.left).isEqualTo(x)
            assertThat(box.rect.top).isWithin(0.5f).of(bodyRect.top + 3 * lineBoxPt)
            val flow = session.documentSession.document.value.flows[focus.flowId]!!
            assertThat(flow.autoContinue).isTrue()
            assertThat(flow.box).isEqualTo(FlowBox.created(x))
            val field = text.field.first { it != null }!!
            assertThat(field.placement.topPt).isWithin(0.5f).of(bodyRect.top + 3 * lineBoxPt)

            assertThat(session.undo()).isTrue()
            assertThat(session.frames(pages[0]).map { it.role }).containsExactly(FrameRole.BODY)
            assertThat(session.documentSession.canUndo.value).isFalse()
        }

    @Test
    fun placeCaret_justBelowABoxsLastLine_startsANewBoxThere() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)
            val controller = checkNotNull(session.textController)
            assertThat(controller.typeIntoPage(pages[0], "alpha")).isTrue()
            val first = text.focus.first { it != null }!!.flowId
            controller.blur()

            controller.placeCaret(pages[0], boxZone.left + 4f, boxZone.top + lineBoxPt + 2f)
            val second = text.focus.first { it != null && it.flowId != first }!!.flowId

            val boxes = session.frames(pages[0])
            assertThat(boxes.map { it.flow }).containsExactly(first, second)
            assertThat(boxes.single { it.flow == second }.rect.top).isWithin(0.5f).of(boxZone.top + lineBoxPt)
        }

    @Test
    fun blur_emptyNewBox_removesItWithoutAnUndoStep() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)
            val controller = checkNotNull(session.textController)

            controller.placeCaret(pages[0], boxZone.left + 20f, lineY(2))
            text.focus.first { it != null }
            controller.blur()
            session.documentSession.document.first { it.flows.isEmpty() }

            assertThat(session.frames(pages[0])).isEmpty()
            assertThat(session.documentSession.canUndo.value).isFalse()
            assertThat(session.documentSession.canRedo.value).isFalse()
        }

    @Test
    fun placeCaret_elsewhereFromAnEmptyNewBox_movesTheNewBoxThereAsOneUndoStep() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)
            val controller = checkNotNull(session.textController)

            controller.placeCaret(pages[0], boxZone.left + 20f, lineY(2))
            val first = text.focus.first { it != null }!!.flowId
            controller.placeCaret(pages[0], boxZone.left + 60f, lineY(6))
            val second = text.focus.first { it != null && it.flowId != first }!!.flowId
            session.documentSession.document.first { first !in it.flows }

            val box = session.frames(pages[0]).single()
            assertThat(box.flow).isEqualTo(second)
            assertThat(box.rect.left).isEqualTo(boxZone.left + 60f)
            assertThat(session.undo()).isTrue()
            assertThat(session.documentSession.document.value.flows).isEmpty()
            assertThat(session.documentSession.canUndo.value).isFalse()
        }

    @Test
    fun blur_newBoxWithText_keepsIt() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)
            val controller = checkNotNull(session.textController)

            controller.typeIntoPage(pages[0], "H")
            val focus = text.focus.first { it != null }!!
            controller.blur()
            testScheduler.advanceUntilIdle()

            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .markdown,
            ).isEqualTo("H")
            assertThat(session.frames(pages[0]).single().flow).isEqualTo(focus.flowId)
        }

    @Test
    fun placeCaret_onText_focusesTheBlockUnderTheTap() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[0], bodyRect.left + 2f, secondLineY())

            assertThat(text.focus.value?.blockIndex).isEqualTo(1)
            assertThat(markdown(session.documentSession)).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun placeCaret_inTheMargin_createsAFocusedGrowingBoxOnTheTappedRule() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)
            assertThat(bodyRect.left).isGreaterThan(10f)

            checkNotNull(session.textController).placeCaret(pages[0], 4f, lineY(5))
            val focus = text.focus.first { it != null }!!

            val box = session.frames(pages[0]).single { it.role == FrameRole.BOX }
            assertThat(box.flow).isEqualTo(focus.flowId)
            assertThat(box.autoGrow).isTrue()
            assertThat(box.rect.left).isEqualTo(boxZone.left)
            assertThat(box.rect.top).isAtMost(lineY(5))
            assertThat(lineY(5) - box.rect.top).isLessThan(ModelFixtures.LINED_BACKGROUND.template.spacingPt)
            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .autoContinue,
            ).isTrue()

            assertThat(session.undo()).isTrue()
            assertThat(session.frames(pages[0]).map { it.role }).containsExactly(FrameRole.BODY)
            assertThat(session.documentSession.document.value.flows.keys).containsExactly(body)
        }

    @Test
    fun placeCaret_documentWithoutText_createsABoxWithTheCaretAtTheTap() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[0], bodyRect.left + 10f, lineY(2))
            val focus = text.focus.first { it != null }!!

            val frame = session.frames(pages[0]).single()
            assertThat(frame.role).isEqualTo(FrameRole.BOX)
            assertThat(frame.rect.left).isEqualTo(bodyRect.left + 10f)
            assertThat(frame.rect.top).isWithin(0.5f).of(bodyRect.top + 2 * lineBoxPt)
            assertThat(focus.flowId).isEqualTo(frame.flow)
            assertThat(focus.blockIndex).isEqualTo(0)
            assertThat(text.parsed(frame.flow)!!.blocks.map { it.content }).containsExactly(BlockContent.Empty)

            assertThat(session.undo()).isTrue()
            assertThat(session.documentSession.document.value.flows).isEmpty()
            assertThat(session.frames(pages[0])).isEmpty()
        }

    @Test
    fun placeCaret_pageAfterTheBodyText_startsABoxThereInsteadOfContinuingTheBody() =
        runTest(main.testDispatcher) {
            val path = note(2, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[1], bodyRect.left + 10f, lineY(1))
            val focus = text.focus.first { it != null }!!

            val frame = session.frames(pages[1]).single()
            assertThat(frame.flow).isEqualTo(focus.flowId)
            assertThat(frame.flow).isNotEqualTo(body)
            assertThat(frame.role).isEqualTo(FrameRole.BOX)
            assertThat(markdown(session.documentSession)).isEqualTo("alpha")
            val field = text.field.first { it != null }!!
            assertThat(field.pageId).isEqualTo(pages[1])
            assertThat(field.placement.topPt).isWithin(0.5f).of(bodyRect.top + lineBoxPt)
        }

    @Test
    fun placeCaret_pageBeforeTheBodyText_addsABoxOnTheTappedLineInsteadOfPullingTheTextBack() =
        runTest(main.testDispatcher) {
            val path = note(2, listOf(1), "alpha")
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[0], bodyRect.left + 10f, lineY(2))
            val focus = text.focus.first { it != null }!!

            val box = session.frames(pages[0]).single()
            assertThat(box.role).isEqualTo(FrameRole.BOX)
            assertThat(box.flow).isEqualTo(focus.flowId)
            assertThat(box.rect.left).isEqualTo(bodyRect.left + 10f)
            assertThat(markdown(session.documentSession)).isEqualTo("alpha")
        }

    @Test
    fun typeIntoPage_nothingFocused_typesAtTheEndOfTheLastBoxOnThePage() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)

            assertThat(checkNotNull(session.textController).typeIntoPage(pages[0], "xy")).isTrue()
            val focus = text.focus.first { it != null }!!

            assertThat(text.parsed(body)!!.blocks.map { it.source }).containsExactly("alphaxy")
            assertThat(focus.blockIndex).isEqualTo(0)
            assertThat(focus.caret).isEqualTo(7)
            assertThat(text.fieldSource()).isEqualTo("alphaxy")
        }

    @Test
    fun typeIntoPage_blankDocument_createsABoxOnTheFirstLineOfTheTextZone() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)

            assertThat(checkNotNull(session.textController).typeIntoPage(pages[0], "H")).isTrue()
            val focus = text.focus.first { it != null }!!

            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .markdown,
            ).isEqualTo("H")
            val box = session.frames(pages[0]).single()
            assertThat(box.role).isEqualTo(FrameRole.BOX)
            assertThat(box.rect.left).isEqualTo(boxZone.left)
            assertThat(box.rect.top).isEqualTo(boxZone.top)
            assertThat(focus.caret).isEqualTo(1)
        }

    @Test
    fun typeIntoPage_textToolOptions_newFlowTakesTheirFontAndSize() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)
            session.updateOptions { it.copy(text = TextOptions("Kalam").withBodyCap(0.6f)) }

            checkNotNull(session.textController).typeIntoPage(pages[0], "H")
            val focus = text.focus.first { it != null }!!

            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .style,
            ).isEqualTo(FlowStyle("Kalam", 0.6f, 0, TextAlign.START))
        }

    @Test
    fun typeIntoPage_blockFocused_leavesTheKeyToTheField() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)
            text.focusAt(pages[0], bodyRect.left + 2f, firstLineY())

            assertThat(checkNotNull(session.textController).typeIntoPage(pages[0], "x")).isFalse()
            assertThat(markdown(session.documentSession)).isEqualTo("alpha")
        }
}
