package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TextSelection
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith

/** A selection across the blocks of a flow (07-text-engine.md#selection-as-built), through the editor's text controller. */
@RunWith(AndroidJUnit4::class)
class FlowSelectionTest : EditorTextFixture() {
    private companion object {
        const val RELAYOUT_YIELDS = 10
    }

    @Test
    fun shiftDown_fromTheLastLine_extendsIntoTheNextBlock() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            texts.select(2, 2)
            val first = checkNotNull(text.focus.value).blockId

            texts.extendTo(page, bodyRect.left + 1f, secondLineY())
            yield()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.blockIndex).isEqualTo(1)
            assertThat(focus.anchor).isEqualTo(Caret(first, 2))
            assertThat(focus.spansBlocks).isTrue()
            assertThat(texts.copySelection()).isEqualTo("pha\n\n")
            assertThat(checkNotNull(text.field.first()).anchorOffset).isEqualTo(0)
        }

    @Test
    fun cut_ofAThreeBlockSelection_isOneUndoStepAndGivesItsMarkdown() =
        runTest(main.testDispatcher) {
            val original = "# Title\n\n- item\n\nlast words"
            val path = note(1, listOf(0), original)
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val blocks = checkNotNull(text.parsed(body)).blocks
            text.select(body, TextSelection(Caret(blocks[0].id, 4), Caret(blocks[2].id, 4)))
            yield()

            val cut = texts.cutSelection()
            yield()

            assertThat(cut).isEqualTo("tle\n\n- item\n\nlast")
            assertThat(markdown(session.documentSession)).isEqualTo("# Ti words")
            assertThat(checkNotNull(text.focus.value).anchor).isNull()
            assertThat(session.undo()).isTrue()
            assertThat(markdown(session.documentSession)).isEqualTo(original)
        }

    @Test
    fun paste_ofMultiBlockMarkdown_splitsIntoBlocks() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            texts.select(5, 5)

            texts.edit(FieldEdit.Replace("\n\n# Head\n\n- one\n- two"))
            yield()

            val sources = checkNotNull(text.parsed(body)).blocks.map { it.source }
            assertThat(sources).containsExactly("alpha", "# Head", "- one", "- two", "beta").inOrder()
        }

    @Test
    fun paste_ofAHeadingAtTheEndOfAHeading_keepsBothHeadings() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "# Section 1\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            texts.select(11, 11)

            texts.edit(FieldEdit.Replace("# Heading one\n\n- item", paste = true))
            yield()

            val sources = checkNotNull(text.parsed(body)).blocks.map { it.source }
            assertThat(sources).containsExactly("# Section 1", "# Heading one", "- item", "beta").inOrder()
        }

    @Test
    fun selectAll_twice_selectsTheBlockThenTheFlow() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta\n\ngamma")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, secondLineY())
            texts.select(1, 1)

            texts.selectAll()
            yield()
            val block = checkNotNull(text.field.first())
            assertThat(block.anchorOffset).isEqualTo(0)
            assertThat(block.focus.caret).isEqualTo(4)
            texts.select(0, 4, extending = true) // the field reports the selection it was given
            texts.selectAll()
            yield()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.blockIndex).isEqualTo(2)
            assertThat(focus.caret).isEqualTo(5)
            assertThat(focus.anchor?.offset).isEqualTo(0)
            assertThat(texts.copySelection()).isEqualTo("alpha\n\nbeta\n\ngamma")
        }

    @Test
    fun typing_overASelectionAcrossBlocks_replacesItInOneStep() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val blocks = checkNotNull(text.parsed(body)).blocks
            text.select(body, TextSelection(Caret(blocks[0].id, 2), Caret(blocks[1].id, 2)))
            yield()

            texts.edit(FieldEdit.Replace("X"))
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("alXta")
            assertThat(text.fieldSource()).isEqualTo("alXta")
            assertThat(checkNotNull(text.focus.value).caret).isEqualTo(3)
            assertThat(session.undo()).isTrue()
            assertThat(markdown(session.documentSession)).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun typing_overThreeBlocksWithBlocksAfter_focusesTheMergedBlockAndUndoesInOneStep() =
        runTest(main.testDispatcher) {
            val original = "alpha\n\nbeta\n\ngamma\n\ndelta\n\nepsilon"
            val path = note(1, listOf(0), original)
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            texts.select(2, 2)
            texts.extendTo(page, bodyRect.left + 1f, secondLineY())
            yield()
            texts.select(0, 0, extending = true) // the field reports the selection it was given
            texts.extendTo(page, bodyRect.left + 12f, firstLineY() + 2 * (secondLineY() - firstLineY()))
            yield()
            texts.select(0, 2, extending = true)
            assertThat(checkNotNull(text.focus.value).blockIndex).isEqualTo(2)

            texts.edit(FieldEdit.Replace("X"))
            yield()
            texts.select(3, 3, extending = true) // the field shows the merged block

            val merged = checkNotNull(text.parsed(body)).blocks[0]
            val focus = checkNotNull(text.focus.value)
            assertThat(merged.source).startsWith("alX")
            assertThat(focus.blockIndex).isEqualTo(0)
            assertThat(focus.blockId).isEqualTo(merged.id)
            assertThat(focus.anchor).isNull()
            assertThat(text.fieldSource()).isEqualTo(merged.source)
            assertThat(session.undo()).isTrue()
            assertThat(markdown(session.documentSession)).isEqualTo(original)
        }

    @Test
    fun typing_overASelection_relayoutBeforeTheFocusMoves_stillFocusesTheMergedBlock() =
        runTest(main.testDispatcher) {
            val original = "alpha\n\nbeta\n\ngamma\n\ndelta\n\nepsilon"
            val path = note(1, listOf(0), original)
            val (session, text, page) = focusedFlow(path, this)
            // The device order: the relayout of the edited flow lands before the edit puts the focus.
            val runner: BlockEditRunner = {
                flowId,
                key,
                op,
                ->
                session.editText(flowId, key, op).also { repeat(RELAYOUT_YIELDS) { yield() } }
            }
            val editor = TextEditor(text, runner, backgroundScope, main.testDispatcher)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val blocks = checkNotNull(text.parsed(body)).blocks
            text.select(body, TextSelection(Caret(blocks[0].id, 2), Caret(blocks[2].id, 2)))
            yield()

            editor.edit(FieldEdit.Replace("X"))
            repeat(RELAYOUT_YIELDS * 2) { yield() }

            val merged = checkNotNull(text.parsed(body)).blocks[0]
            val focus = checkNotNull(text.focus.value)
            assertThat(merged.source).isEqualTo("alXmma")
            assertThat(focus.blockId).isEqualTo(merged.id)
            assertThat(focus.caret).isEqualTo(3)
        }

    @Test
    fun bold_overASelectionAcrossBlocks_formatsEachBlock() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val blocks = checkNotNull(text.parsed(body)).blocks
            text.select(body, TextSelection(Caret(blocks[0].id, 0), Caret(blocks[1].id, 4)))
            yield()

            texts.format(FormatAction.BOLD)
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("**alpha**\n\n**beta**")
            assertThat(session.undo()).isTrue()
            assertThat(markdown(session.documentSession)).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun selectionAcrossBlocks_snapshotHighlightsEveryBlockIncludingTheEditedOne() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta\n\ngamma")
            val (_, text, page) = focusedFlow(path, this)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val blocks = checkNotNull(text.parsed(body)).blocks
            text.select(body, TextSelection(Caret(blocks[0].id, 2), Caret(blocks[2].id, 3)))
            yield()
            checkNotNull(text.field.first())

            val highlight = checkNotNull(text.snapshot.value.selection)
            val focus = checkNotNull(highlight.focus)
            // gamma too: the overlay draws the edited block from the page layout, its boxes come from there (A-058)
            assertThat(highlight.boxes).hasSize(3)
            assertThat(highlight.boxes.map { it.pageId }.distinct()).containsExactly(page)
            assertThat(
                highlight.boxes
                    .last()
                    .rect.top,
            ).isEqualTo(focus.topPt)
            assertThat(
                highlight.boxes
                    .last()
                    .rect.right,
            ).isEqualTo(focus.xPt)
            assertThat(checkNotNull(highlight.anchor).xPt).isGreaterThan(bodyRect.left)
            assertThat(focus.xPt).isGreaterThan(bodyRect.left)
        }

    @Test
    fun plainSelection_inTheField_dropsTheEndInAnotherBlock() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            val blocks = checkNotNull(text.parsed(body)).blocks
            text.select(body, TextSelection(Caret(blocks[0].id, 2), Caret(blocks[1].id, 2)))
            yield()

            texts.select(2, 2, extending = false)
            yield()

            assertThat(checkNotNull(text.focus.value).anchor).isNull()
            assertThat(texts.copySelection()).isNull()
        }

    @Test
    fun extend_overATable_endsPastIt() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\n| a | b |\n| --- | --- |\n| 1 | 2 |\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            texts.select(5, 5)

            texts.extendHorizontally(forward = true)
            yield()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.blockIndex).isEqualTo(2)
            assertThat(focus.caret).isEqualTo(0)
            assertThat(focus.anchor?.offset).isEqualTo(5)
        }
}
