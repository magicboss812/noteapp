package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.state.EditorSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

/** Box Preview and Edit (10-editor-ui.md#text-boxes, Modes; P07-T13) on a real session. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class BoxSelectionTest : EditorTextFixture() {
    private val boxZone = TextZones.boxZone(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template)

    private fun lineY(line: Int): Float = bodyRect.top + line * lineBoxPt + 2f

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

    private suspend fun blankNote(pages: Int): String =
        (
            documents.create(NewDocumentSpec("", "Blank", ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, pages)) as Outcome.Success
        ).value.path

    private fun EditorSession.frames(): List<FlowFrame> =
        documentSession.document.value.pageBodies.values
            .flatMap { it.objects }
            .filterIsInstance<FlowFrame>()

    @Test
    fun tapBox_onText_selectsInPreview_noFocus_aSecondTapStays() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(note(1, listOf(0), "alpha beta gamma"), this)
            val controller = checkNotNull(session.textController)

            controller.tapBox(pages[0], bodyRect.left + 10f, firstLineY())
            assertThat(text.mode).isEqualTo(BoxMode.Preview(body))
            assertThat(text.focus.value).isNull()
            val shown = text.snapshot.first { it.box != null }.box!!
            assertThat(shown.flowId).isEqualTo(body)
            assertThat(shown.editing).isFalse()
            assertThat(text.snapshot.value.field).isNull()

            controller.tapBox(pages[0], bodyRect.left + 30f, firstLineY())
            assertThat(text.mode).isEqualTo(BoxMode.Preview(body))
            assertThat(text.focus.value).isNull()
        }

    @Test
    fun editBox_putsTheCaretWhereThePreviewTapHitTheShownText() =
        runTest(main.testDispatcher) {
            // D-045: the spot comes from the layout shown at the tap, not from the one with markers.
            val (session, text, pages) = ready(note(1, listOf(0), "**alpha** beta gamma"), this)
            val controller = checkNotNull(session.textController)
            val x = bodyRect.left + 60f
            val expected = checkNotNull(text.caretAt(pages[0], x, firstLineY())).caret

            controller.tapBox(pages[0], x, firstLineY())
            controller.editBox()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.flowId).isEqualTo(body)
            assertThat(focus.caret).isEqualTo(expected)
            assertThat(text.mode).isEqualTo(BoxMode.Edit(body))
            assertThat(
                text.snapshot
                    .first { it.box?.editing == true }
                    .box!!
                    .flowId,
            ).isEqualTo(body)
        }

    @Test
    fun editBox_withoutATap_putsTheCaretAtTheEnd_andAgainGoesBackToPreview() =
        runTest(main.testDispatcher) {
            val (session, text, _) = ready(note(1, listOf(0), "alpha"), this)
            val controller = checkNotNull(session.textController)
            text.selectBox(body)

            controller.editBox()
            assertThat(text.focus.value?.caret).isEqualTo("alpha".length)

            controller.editBox()
            assertThat(text.mode).isEqualTo(BoxMode.Preview(body))
            assertThat(text.focus.value).isNull()
        }

    @Test
    fun back_stepsEditPreviewNone_thenLeavesBackToTheScreen() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(note(1, listOf(0), "alpha"), this)
            val controller = checkNotNull(session.textController)
            controller.focusAt(pages[0], bodyRect.left + 2f, firstLineY())
            assertThat(text.mode).isEqualTo(BoxMode.Edit(body))

            assertThat(controller.back()).isTrue()
            assertThat(text.mode).isEqualTo(BoxMode.Preview(body))
            assertThat(controller.back()).isTrue()
            assertThat(text.mode).isEqualTo(BoxMode.None)
            assertThat(controller.back()).isFalse()
        }

    @Test
    fun tapBox_inTheBoxBeingEdited_movesTheCaret_elsewhereEndsTheSelection() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(note(1, listOf(0), "alpha beta gamma"), this)
            val controller = checkNotNull(session.textController)
            controller.focusAt(pages[0], bodyRect.left + 2f, firstLineY())
            val before = checkNotNull(text.focus.value)

            controller.tapBox(pages[0], bodyRect.left + 60f, firstLineY())
            val moved = checkNotNull(text.focus.value)
            assertThat(moved.caret).isNotEqualTo(before.caret)
            assertThat(text.mode).isEqualTo(BoxMode.Edit(body))

            controller.tapBox(pages[0], bodyRect.left + 10f, lineY(12))
            assertThat(text.mode).isEqualTo(BoxMode.None)
        }

    @Test
    fun tapBox_outsideEveryBoxWithTheTextTool_startsANewBoxInEdit() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(blankNote(1), this)
            val controller = checkNotNull(session.textController)

            controller.tapBox(pages[0], boxZone.left + 20f, lineY(3), textTool = true)

            val focus = text.focus.first { it != null }!!
            assertThat(text.mode).isEqualTo(BoxMode.Edit(focus.flowId))
        }

    @Test
    fun emptyNewBox_staysInPreview_goesWhenDeselectedWithoutAnUndoStep() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(blankNote(1), this)
            val controller = checkNotNull(session.textController)
            controller.tapBox(pages[0], boxZone.left + 20f, lineY(3), textTool = true)
            val flowId = text.focus.first { it != null }!!.flowId

            controller.back()
            testScheduler.advanceUntilIdle()
            assertThat(text.mode).isEqualTo(BoxMode.Preview(flowId))
            assertThat(session.documentSession.document.value.flows).containsKey(flowId)

            controller.back()
            session.documentSession.document.first { it.flows.isEmpty() }
            assertThat(session.frames()).isEmpty()
            assertThat(session.documentSession.canUndo.value).isFalse()
        }

    @Test
    fun deleteBox_removesTheBoxWithItsText_oneUndoStep_undoRestoresTextAndPlace() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(blankNote(1), this)
            val controller = checkNotNull(session.textController)
            assertThat(controller.typeIntoPage(pages[0], "keep me")).isTrue()
            val flowId = text.focus.first { it != null }!!.flowId
            testScheduler.advanceUntilIdle()
            val before = session.documentSession.document.value
            val frame = session.frames().single()

            controller.back()
            controller.deleteBox()
            session.documentSession.document.first { flowId !in it.flows }

            assertThat(text.mode).isEqualTo(BoxMode.None)
            assertThat(session.frames()).isEmpty()
            // One step: a single undo brings back the text and the frame where it was.
            assertThat(session.undo()).isTrue()
            val restored = session.documentSession.document.value
            assertThat(restored.flows[flowId]?.markdown).isEqualTo(before.flows[flowId]?.markdown)
            assertThat(session.frames().single()).isEqualTo(frame)
        }

    @Test
    fun typing_withABoxInPreview_goesToItsEnd() =
        runTest(main.testDispatcher) {
            val (session, text, pages) = ready(note(1, listOf(0), "alpha"), this)
            val controller = checkNotNull(session.textController)
            text.selectBox(body)

            assertThat(controller.typeIntoPage(pages[0], "!")).isTrue()
            text.focus.first { it != null }
            testScheduler.advanceUntilIdle()

            assertThat(markdown(session.documentSession)).isEqualTo("alpha!")
            assertThat(text.mode).isEqualTo(BoxMode.Edit(body))
        }
}
