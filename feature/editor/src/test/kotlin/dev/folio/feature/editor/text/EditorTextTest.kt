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

/** The text engine of an editor session over the real storage stack (temp-dir library, in-memory index). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditorTextTest : EditorTextFixture() {
    @Test
    fun pageTexts_framesOnPagesBeyondTheDecodeLimit_everyFramePageShowsText() =
        runTest(main.testDispatcher) {
            // 40 pages > the session's 30 decoded pages; frames on pages 3, 25 and 39 are only found by loading them.
            val path = note(40, listOf(3, 25, 39), paragraphs(3 * linesPerFrame))
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)

            text.awaitReady()

            val shown = text.snapshot.value.pages.keys
            val expected =
                listOf(3, 25, 39).map {
                    documentSession.document.value.pages[it]
                        .id
                }
            assertThat(shown).containsAtLeastElementsIn(expected)
            assertThat(documentSession.decodedPageCount).isAtMost(33)
        }

    @Test
    fun editFlow_typingPastTheLastBodyFrame_addsContinuationPageInTheSameUndoStep() =
        runTest(main.testDispatcher) {
            val original = paragraphs(5)
            val path = note(1, listOf(0), original)
            val (session, documentSession) = open(path, this)
            checkNotNull(session.text).awaitReady()
            assertThat(documentSession.document.value.pages).hasSize(1)

            val typed = TextEdit(original.length, original.length, "\n\n" + paragraphs(3 * linesPerFrame))
            assertThat(session.editFlow(body, listOf(typed), "type")).isTrue()

            val grown = documentSession.document.value
            assertThat(grown.pages.size).isGreaterThan(1)
            assertThat(grown.flows.getValue(body).markdown).isEqualTo(original + typed.replacement)
            val continued =
                grown
                    .page(grown.pages[1].id)!!
                    .objects
                    .filterIsInstance<FlowFrame>()
                    .single()
            assertThat(continued.flow).isEqualTo(body)
            assertThat(continued.role).isEqualTo(FrameRole.BODY)

            assertThat(session.undo()).isTrue()

            val undone = documentSession.document.value
            assertThat(undone.pages).hasSize(1)
            assertThat(undone.flows.getValue(body).markdown).isEqualTo(original)
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun editText_enterInTheMiddleOfABlock_splitsItAsOneUndoStep() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val first = checkNotNull(text.parsed(body)).blocks[0]

            val result = session.editText(body) { BlockEditing.enter(it, TextSelection.at(Caret(first.id, 2))) }

            assertThat(markdown(documentSession)).isEqualTo("al\n\npha\n\nbeta")
            assertThat(result!!.document.blocks.map { it.source }).containsExactly("al", "pha", "beta").inOrder()
            assertThat(text.parsed(body)!!.blocks.map { it.id }).isEqualTo(result.document.blocks.map { it.id })
            assertThat(text.parsed(body)!!.blocks[0].id).isEqualTo(first.id)

            assertThat(session.undo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("alpha\n\nbeta")
            assertThat(documentSession.canUndo.value).isFalse()

            assertThat(session.redo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("al\n\npha\n\nbeta")
        }

    @Test
    fun editText_backspaceAtBlockStart_mergesAndUndoSplitsAgain() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val second = checkNotNull(text.parsed(body)).blocks[1]

            val result = session.editText(body) { BlockEditing.backspace(it, TextSelection.at(Caret(second.id, 0))) }

            assertThat(markdown(documentSession)).isEqualTo("alphabeta")
            assertThat(result!!.selection.focus.offset).isEqualTo(5)
            assertThat(session.undo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun editText_quickTypingInOneBlock_isOneUndoStep() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val first = checkNotNull(text.parsed(body)).blocks[0]

            for (typedCount in 1..3) {
                val typed =
                    session.editText(body, "type:$body:${first.id}") {
                        BlockEditing.setSource(
                            it,
                            first.id,
                            "alpha" + "xyz".take(typedCount),
                            5 + typedCount,
                        )
                    }
                assertThat(typed).isNotNull()
            }

            assertThat(markdown(documentSession)).isEqualTo("alphaxyz\n\nbeta")
            assertThat(session.undo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("alpha\n\nbeta")
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun editText_structuralEditAfterTyping_isItsOwnUndoStep() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val first = checkNotNull(text.parsed(body)).blocks[0]
            session.editText(body, "type:$body:${first.id}") { BlockEditing.setSource(it, first.id, "alphax", 6) }

            session.editText(body) { BlockEditing.enter(it, TextSelection.at(Caret(first.id, 6))) }

            assertThat(markdown(documentSession)).isEqualTo("alphax\n\n\n")
            assertThat(session.undo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("alphax")
            assertThat(session.undo()).isTrue()
            assertThat(markdown(documentSession)).isEqualTo("alpha")
        }

    @Test
    fun editText_resultThatKeepsTheText_executesNothing() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val first = checkNotNull(text.parsed(body)).blocks[0]

            val result = session.editText(body) { BlockEditing.delete(it, TextSelection.at(Caret(first.id, 2))) }

            assertThat(result).isNotNull()
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun editText_declinedOrUnknownFlow_returnsNull() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha")
            val (session, documentSession) = open(path, this)
            checkNotNull(session.text).awaitReady()

            assertThat(session.editText(body) { null }).isNull()
            assertThat(session.editText(FlowId("nope")) { BlockEditing.enter(it, TextSelection.at(Caret(it.blocks[0].id, 0))) }).isNull()
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun editText_pastingPastTheLastBodyFrame_addsAContinuationPageInTheSameUndoStep() =
        runTest(main.testDispatcher) {
            val original = paragraphs(5)
            val path = note(1, listOf(0), original)
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val last = checkNotNull(text.parsed(body)).blocks.last()
            val pasted = "\n\n" + paragraphs(3 * linesPerFrame)

            val result = session.editText(body) { BlockEditing.insert(it, TextSelection.at(Caret(last.id, last.source.length)), pasted) }

            assertThat(result).isNotNull()
            assertThat(documentSession.document.value.pages.size).isGreaterThan(1)
            assertThat(session.undo()).isTrue()
            assertThat(documentSession.document.value.pages).hasSize(1)
            assertThat(markdown(documentSession)).isEqualTo(original)
        }

    @Test
    fun focusAt_firstBlock_focusesItWithTheCaretAtTheEndOfAShortLine() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val first = checkNotNull(text.parsed(body)).blocks[0]
            val page =
                documentSession.document.value.pages[0]
                    .id

            val focused = text.focusAt(page, bodyRect.right - 4f, firstLineY())

            assertThat(focused).isTrue()
            assertThat(text.focus.value).isEqualTo(TextFocus(body, first.id, 0, "alpha".length, 1))
        }

    @Test
    fun focusAt_pointLeftOfTheText_putsTheCaretAtTheBlockStart() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id

            text.focusAt(page, bodyRect.left, firstLineY())

            assertThat(text.focus.value?.caret).isEqualTo(0)
        }

    @Test
    fun focusAt_beyondTheFrame_endsTheFocus() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            assertThat(text.focus.value).isNotNull()

            val focused = text.focusAt(page, bodyRect.left + 4f, bodyRect.bottom + 40f)

            assertThat(focused).isFalse()
            assertThat(text.focus.value).isNull()
            assertThat(text.field.first()).isNull()
        }

    @Test
    fun focusAt_secondTap_countsTheRevisionUp() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id

            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            text.focusAt(page, bodyRect.left + 8f, firstLineY())

            assertThat(text.focus.value?.revision).isEqualTo(2)
        }

    @Test
    fun field_focusedBlock_showsItsSourceAtItsPlaceOnThePage() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id

            text.focusAt(page, bodyRect.left + 4f, firstLineY())

            val field = checkNotNull(text.field.first())
            assertThat(field.block.source).isEqualTo("alpha")
            assertThat(field.pageId).isEqualTo(page)
            assertThat(field.placement.topPt).isAtLeast(bodyRect.top)
        }

    @Test
    fun snapshot_focusedBlock_canvasSkipsItWhileThumbnailsStillShowIt() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id
            val before = text.snapshot.value
            assertThat(before.canvas[page]).isSameInstanceAs(before.pages[page])

            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            yield() // the text dispatcher lays the pages out again without the focused block

            val focused = text.snapshot.value
            assertThat(focused.canvas.runCount(page)).isLessThan(focused.pages.runCount(page))
            assertThat(focused.pages[page]).isSameInstanceAs(before.pages[page])

            text.blur()
            yield()

            assertThat(text.snapshot.value.canvas[page]).isSameInstanceAs(before.pages[page])
        }

    private fun Map<PageId, PageText>.runCount(page: PageId): Int = getValue(page).runs.size

    @Test
    fun focus_blockAboveIsSplit_followsTheBlockByIdAndUndoMovesItToTheSameIndex() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id
            val parsed = checkNotNull(text.parsed(body))
            text.focusAt(page, bodyRect.left + 4f, secondLineY())
            assertThat(text.focus.value?.blockId).isEqualTo(parsed.blocks[1].id)

            session.editText(body) { BlockEditing.enter(it, TextSelection.at(Caret(parsed.blocks[0].id, 2))) }
            yield()

            assertThat(text.focus.value?.blockId).isEqualTo(parsed.blocks[1].id)
            assertThat(text.focus.value?.blockIndex).isEqualTo(2)

            assertThat(session.undo()).isTrue()
            yield()

            assertThat(text.focus.value?.blockIndex).isEqualTo(1)
            assertThat(text.fieldSource()).isEqualTo("beta")
        }

    @Test
    fun focusAt_emptyFlow_focusesAnEmptyBlock() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "")
            val (session, documentSession) = open(path, this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id

            val focused = text.focusAt(page, bodyRect.left + 4f, firstLineY())

            assertThat(focused).isTrue()
            assertThat(text.focus.value?.caret).isEqualTo(0)
            assertThat(text.fieldSource()).isEqualTo("")
        }

    @Test
    fun showSearchMatch_matchOnALaterFramePage_scrollsToThatPage() =
        runTest(main.testDispatcher) {
            val path = note(35, listOf(2, 20, 34), paragraphs(2 * linesPerFrame + 3, needleAt = 2 * linesPerFrame + 2))
            val (session, documentSession) = open(path, this)
            val jumps = ArrayList<PageId>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.pageJumps.toList(jumps) }

            val page = session.showSearchMatch("needle")
            yield() // the unconfined collector resumes once this test body suspends

            val last =
                documentSession.document.value.pages[34]
                    .id
            assertThat(page).isEqualTo(last)
            assertThat(jumps).containsExactly(last)
        }

    @Test
    fun showSearchMatch_noMatch_staysPut() =
        runTest(main.testDispatcher) {
            val path = note(3, listOf(0, 1, 2), paragraphs(10))
            val (session, _) = open(path, this)
            val jumps = ArrayList<PageId>()
            backgroundScope.launch(start = CoroutineStart.UNDISPATCHED) { session.pageJumps.toList(jumps) }

            assertThat(session.showSearchMatch("zebra")).isNull()
            assertThat(jumps).isEmpty()
        }

    @Test
    fun duplicatePages_bodyFrame_copyHoldsOnlyTheTextShownOnThatPage() =
        runTest(main.testDispatcher) {
            val markdown = paragraphs(3 * linesPerFrame - 5)
            val path = note(3, listOf(0, 1, 2), markdown)
            val (session, documentSession) = open(path, this)
            checkNotNull(session.text).awaitReady()
            val second =
                documentSession.document.value.pages[1]
                    .id

            val copy = session.duplicatePages(listOf(second))

            val doc = documentSession.document.value
            assertThat(copy).isNotNull()
            val copied =
                doc
                    .page(copy!!)!!
                    .objects
                    .filterIsInstance<FlowFrame>()
                    .single()
            val shown = doc.flows.getValue(copied.flow).markdown
            assertThat(shown).isNotEmpty()
            assertThat(shown.length).isLessThan(markdown.length)
            assertThat(markdown).contains(shown)
            assertThat(doc.flows.getValue(body).markdown).isEqualTo(markdown)
        }
}
