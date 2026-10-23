package dev.folio.feature.editor.text

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.session.DocumentSession
import dev.folio.core.storage.session.DocumentSessions
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TempDirFolioFs
import dev.folio.core.testing.TestDispatchersRule
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.toTextStyle
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.feature.editor.state.EditorSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The edit queue of an editor session ([TextEditor]): ordering, coalescing, focus after an edit, formatting. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class TextEditorTest : EditorTextFixture() {
    // A block edit runner that applies the edit to the parsed flow without storing it, and can hold the edit at a gate.
    private class RecordingRunner(
        private val parsed: () -> MarkdownDocument,
    ) {
        val keys = ArrayList<String?>()
        val applied = ArrayList<String?>()
        val gate = CompletableDeferred<Unit>()
        var reject = false

        val run: BlockEditRunner = { _, key, edit ->
            keys += key
            val result = edit(parsed())
            applied += result?.document?.toMarkdown()
            gate.await()
            if (reject) null else result
        }
    }

    @Test
    fun edit_typedKeystrokes_updateTheFlowAndUndoAsOneStep() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val documentSession = session.documentSession
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val revision = text.focus.value?.revision

            texts.edit(FieldEdit.Typed("alphaX", 6))
            yield()
            texts.edit(FieldEdit.Typed("alphaXY", 7))
            yield()

            assertThat(markdown(documentSession)).isEqualTo("alphaXY\n\nbeta")
            assertThat(text.focus.value?.revision).isEqualTo(revision)
            assertThat(session.undo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun edit_typingInATableCell_keepsTheFieldOnThatCellAndTabMovesOn() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "| a   | b   |\n| --- | --- |\n| x   | y   |")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val revision = checkNotNull(text.focus.value).revision
            assertThat(
                text.field
                    .first()
                    ?.cell
                    ?.cell,
            ).isEqualTo(TableCell(0, 0))

            texts.edit(FieldEdit.Cell(TableCell(0, 0), "ab", 2))
            yield()
            texts.edit(FieldEdit.Cell(TableCell(0, 0), "abcdef", 6))
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("| abcdef | b   |\n| ------ | --- |\n| x      | y   |")
            assertThat(text.focus.value?.revision).isEqualTo(revision)
            val typed = checkNotNull(text.field.first()?.cell)
            assertThat(typed.text).isEqualTo("abcdef")
            assertThat(text.focus.value?.caret).isEqualTo(typed.start + 6)

            texts.edit(FieldEdit.Key(FieldKey.TAB, 6, 6, TableCell(0, 0)))
            yield()

            assertThat(
                text.field
                    .first()
                    ?.cell
                    ?.cell,
            ).isEqualTo(TableCell(0, 1))
            assertThat(text.focus.value?.revision).isGreaterThan(revision)
        }

    @Test
    fun edit_enterInTheMiddle_splitsTheBlockAndFocusesTheSecondHalf() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val revision = checkNotNull(text.focus.value).revision

            texts.edit(FieldEdit.Key(FieldKey.ENTER, 2, 2))
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("al\n\npha\n\nbeta")
            val focus = checkNotNull(text.focus.value)
            assertThat(focus.blockIndex).isEqualTo(1)
            assertThat(focus.caret).isEqualTo(0)
            assertThat(focus.revision).isGreaterThan(revision)
            assertThat(text.fieldSource()).isEqualTo("pha")
        }

    @Test
    fun edit_backspaceAtTheStartOfASecondBlock_mergesAndFocusesTheJoin() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, secondLineY())

            texts.edit(FieldEdit.Key(FieldKey.BACKSPACE, 0, 0))
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("alphabeta")
            assertThat(text.focus.value?.blockIndex).isEqualTo(0)
            assertThat(text.focus.value?.caret).isEqualTo(5)
            assertThat(text.fieldSource()).isEqualTo("alphabeta")
        }

    @Test
    fun edit_backspaceInsideAWord_changesNothing() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val before = text.focus.value

            texts.edit(FieldEdit.Key(FieldKey.BACKSPACE, 2, 2))
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("alpha\n\nbeta")
            assertThat(text.focus.value).isEqualTo(before)
        }

    @Test
    fun edit_withoutFocus_isIgnored() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, _, _) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)

            texts.edit(FieldEdit.Typed("x", 1))
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun edit_typedWhileAnEarlierEditWaits_onlyTheNewestRuns() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (_, text, page) = focusedFlow(path, this)
            val runner = RecordingRunner { checkNotNull(text.parsed(body)) }
            val editor = TextEditor(text, runner.run, backgroundScope, main.testDispatcher)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())

            editor.edit(FieldEdit.Typed("alphaA", 6))
            editor.edit(FieldEdit.Typed("alphaAB", 7))
            editor.edit(FieldEdit.Typed("alphaABC", 8))
            runner.gate.complete(Unit)
            yield()

            assertThat(runner.applied.last()).isEqualTo("alphaABC\n\nbeta")
            assertThat(runner.applied).doesNotContain("alphaA\n\nbeta")
            assertThat(runner.keys.distinct()).hasSize(1)
        }

    @Test
    fun edit_structuralKeyBetweenTypedEdits_keepsItsPlaceInTheOrder() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (_, text, page) = focusedFlow(path, this)
            val runner = RecordingRunner { checkNotNull(text.parsed(body)) }
            val editor = TextEditor(text, runner.run, backgroundScope, main.testDispatcher)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())

            editor.edit(FieldEdit.Typed("alphaA", 6))
            editor.edit(FieldEdit.Key(FieldKey.ENTER, 6, 6))
            editor.edit(FieldEdit.Typed("alphaAB", 7))
            runner.gate.complete(Unit)
            yield()

            assertThat(runner.keys.map { it != null }).isEqualTo(listOf(true, false, true))
        }

    @Test
    fun edit_forABlockThatLostTheFocusMeanwhile_isStillAppliedAndKeepsTheNewFocus() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (_, text, page) = focusedFlow(path, this)
            val runner = RecordingRunner { checkNotNull(text.parsed(body)) }
            val editor = TextEditor(text, runner.run, backgroundScope, main.testDispatcher)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            editor.edit(FieldEdit.Typed("alphaA", 6))
            yield() // the first edit is now held at the gate
            editor.edit(FieldEdit.Typed("alphaAB", 7))

            editor.focusAt(page, bodyRect.left + 4f, secondLineY())
            runner.gate.complete(Unit)
            yield()

            assertThat(runner.applied).containsExactly("alphaA\n\nbeta", "alphaAB\n\nbeta").inOrder()
            assertThat(text.focus.value?.blockIndex).isEqualTo(1)
        }

    @Test
    fun edit_rejectedTypedEdit_makesTheFieldShowTheModelTextAgain() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (_, text, page) = focusedFlow(path, this)
            val runner = RecordingRunner { checkNotNull(text.parsed(body)) }
            runner.reject = true
            runner.gate.complete(Unit)
            val editor = TextEditor(text, runner.run, backgroundScope, main.testDispatcher)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val revision = checkNotNull(text.focus.value).revision

            editor.edit(FieldEdit.Typed("alphaZ", 6))
            yield()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.revision).isEqualTo(revision + 1)
            assertThat(focus.caret).isEqualTo(6)
            assertThat(text.fieldSource()).isEqualTo("alpha")
        }

    @Test
    fun format_afterTypedKeystrokes_runsOnTheTypedTextAndGivesTheFieldTheResult() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val revision = checkNotNull(text.focus.value).revision

            texts.edit(FieldEdit.Typed("alphaX", 6))
            texts.select(6, 0)
            texts.format(FormatAction.BOLD)
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("**alphaX**\n\nbeta")
            assertThat(checkNotNull(text.focus.value).revision).isGreaterThan(revision)
            assertThat(text.fieldSource()).isEqualTo("**alphaX**")
            assertThat(session.undo()).isTrue()
            assertThat(markdown(session.documentSession)).isEqualTo("alphaX\n\nbeta")
        }

    @Test
    fun moveFocusAt_lineOfTheNextBlock_movesTheFocusThere() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())

            val moved = texts.moveFocusAt(page, bodyRect.left + 4f, secondLineY())

            assertThat(moved).isTrue()
            assertThat(text.focus.value?.blockIndex).isEqualTo(1)
        }

    @Test
    fun moveFocusAt_pointOutsideTheText_keepsTheFocus() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val before = text.focus.value

            val moved = texts.moveFocusAt(page, bodyRect.left + 4f, bodyRect.bottom + 40f)

            assertThat(moved).isFalse()
            assertThat(text.focus.value).isEqualTo(before)
        }
}
