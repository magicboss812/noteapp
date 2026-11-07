package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.PageId
import dev.folio.core.model.TextAlign
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

    @Test
    fun placeCaret_belowTheLastBlock_fillsEmptyParagraphsDownToTheTappedLineAsOneUndoStep() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[0], bodyRect.left + 10f, lineY(3))
            val focus = text.focus.first { it != null }!!

            val blocks = text.parsed(body)!!.blocks
            assertThat(blocks.map { it.content is BlockContent.Empty }).containsExactly(false, true, true, true).inOrder()
            assertThat(focus.flowId).isEqualTo(body)
            assertThat(focus.blockIndex).isEqualTo(3)
            val field = text.field.first { it != null }!!
            assertThat(field.placement.topPt).isWithin(0.5f).of(bodyRect.top + 3 * lineBoxPt)

            assertThat(session.undo()).isTrue()
            assertThat(markdown(session.documentSession)).isEqualTo("alpha")
            assertThat(session.documentSession.canUndo.value).isFalse()
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
            assertThat(box.rect.top).isAtMost(lineY(5))
            assertThat(lineY(5) - box.rect.top).isLessThan(ModelFixtures.LINED_BACKGROUND.template.spacingPt)
            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .autoContinue,
            ).isFalse()

            assertThat(session.undo()).isTrue()
            assertThat(session.frames(pages[0]).map { it.role }).containsExactly(FrameRole.BODY)
            assertThat(session.documentSession.document.value.flows.keys).containsExactly(body)
        }

    @Test
    fun placeCaret_documentWithoutText_createsTheBodyFlowWithTheCaretOnTheTappedLine() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[0], bodyRect.left + 10f, lineY(2))
            val focus = text.focus.first { it != null }!!

            val frame = session.frames(pages[0]).single()
            assertThat(frame.role).isEqualTo(FrameRole.BODY)
            assertThat(frame.rect).isEqualTo(bodyRect)
            assertThat(
                session.documentSession.document.value.flows[frame.flow]!!
                    .autoContinue,
            ).isTrue()
            assertThat(focus.flowId).isEqualTo(frame.flow)
            assertThat(focus.blockIndex).isEqualTo(2)
            assertThat(
                text.parsed(frame.flow)!!.blocks.map { it.content },
            ).containsExactly(BlockContent.Empty, BlockContent.Empty, BlockContent.Empty)

            assertThat(session.undo()).isTrue()
            assertThat(session.documentSession.document.value.flows).isEmpty()
            assertThat(session.frames(pages[0])).isEmpty()
        }

    @Test
    fun placeCaret_pageAfterTheBodyText_continuesTheBodyFlowWithAFrameThere() =
        runTest(main.testDispatcher) {
            val path = note(2, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)

            checkNotNull(session.textController).placeCaret(pages[1], bodyRect.left + 10f, lineY(1))
            text.focus.first { it != null }

            val frame = session.frames(pages[1]).single()
            assertThat(frame.flow).isEqualTo(body)
            assertThat(frame.role).isEqualTo(FrameRole.BODY)
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
            assertThat(box.rect.left).isEqualTo(bodyRect.left)
            assertThat(box.rect.right).isEqualTo(bodyRect.right)
            assertThat(markdown(session.documentSession)).isEqualTo("alpha")
        }

    @Test
    fun typeIntoPage_nothingFocused_typesANewParagraphAfterThePageText() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, text, pages) = ready(path, this)

            assertThat(checkNotNull(session.textController).typeIntoPage(pages[0], "xy")).isTrue()
            val focus = text.focus.first { it != null }!!

            val blocks = text.parsed(body)!!.blocks
            assertThat(blocks.map { it.source }).containsExactly("alpha", "xy").inOrder()
            assertThat(focus.blockIndex).isEqualTo(1)
            assertThat(focus.caret).isEqualTo(2)
            assertThat(text.fieldSource()).isEqualTo("xy")
        }

    @Test
    fun typeIntoPage_blankDocument_createsTheBodyFlowWithTheText() =
        runTest(main.testDispatcher) {
            val path = blankNote(1)
            val (session, text, pages) = ready(path, this)

            assertThat(checkNotNull(session.textController).typeIntoPage(pages[0], "H")).isTrue()
            val focus = text.focus.first { it != null }!!

            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .markdown,
            ).isEqualTo("H")
            assertThat(session.frames(pages[0]).single().role).isEqualTo(FrameRole.BODY)
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
