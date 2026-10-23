package dev.folio.feature.editor.text

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test

class FieldEditsTest {
    // Block ids of a parsed document count from 1 in source order.
    private fun doc(markdown: String): MarkdownDocument = MarkdownDocument.parse(markdown)

    private fun key(
        key: FieldKey,
        start: Int,
        end: Int = start,
    ) = FieldEdit.Key(key, start, end)

    @Test
    fun applyTo_typed_replacesTheBlockSourceAndPlacesTheCaret() {
        val result = FieldEdit.Typed("abX", 3).applyTo(doc("ab\n\ncd"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("abX\n\ncd")
        assertThat(result?.selection?.focus).isEqualTo(Caret(1L, 3))
    }

    @Test
    fun applyTo_typedTaskTrigger_becomesATaskItem() {
        val result = FieldEdit.Typed("[] ", 3).applyTo(doc("a\n\nb"), 2L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("a\n\n- [ ] ")
        assertThat(result?.selection?.focus).isEqualTo(Caret(2L, 6))
    }

    @Test
    fun applyTo_unknownBlock_isNull() {
        assertThat(FieldEdit.Typed("x", 1).applyTo(doc("a"), 99L)).isNull()
        assertThat(key(FieldKey.ENTER, 0).applyTo(doc("a"), 99L)).isNull()
    }

    @Test
    fun applyTo_enterInTheMiddle_splitsTheBlockAndMovesTheCaretToTheSecondHalf() {
        val result = key(FieldKey.ENTER, 2).applyTo(doc("abcd"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("ab\n\ncd")
        val caret = result?.selection?.focus
        assertThat(caret?.blockId).isNotEqualTo(1L)
        assertThat(caret?.offset).isEqualTo(0)
    }

    @Test
    fun applyTo_softEnter_insertsALineBreakInTheBlock() {
        val result = key(FieldKey.SOFT_ENTER, 2).applyTo(doc("abcd"), 1L)

        assertThat(result?.document?.blocks).hasSize(1)
        assertThat(result?.document?.toMarkdown()).isNotEqualTo("abcd")
    }

    @Test
    fun applyTo_backspaceAtTheStartOfASecondBlock_mergesItIntoTheFirst() {
        val result = key(FieldKey.BACKSPACE, 0).applyTo(doc("ab\n\ncd"), 2L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("abcd")
        assertThat(result?.selection?.focus?.offset).isEqualTo(2)
    }

    @Test
    fun applyTo_backspaceAfterAListMarker_stripsTheMarker() {
        val result = key(FieldKey.BACKSPACE, 2).applyTo(doc("- a"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("a")
        assertThat(result?.selection?.focus).isEqualTo(Caret(1L, 0))
    }

    @Test
    fun applyTo_backspaceInsideTheText_isNull() {
        assertThat(key(FieldKey.BACKSPACE, 1).applyTo(doc("ab"), 1L)).isNull()
    }

    @Test
    fun applyTo_deleteAtTheEndOfABlock_pullsTheNextBlockIn() {
        val result = key(FieldKey.DELETE, 2).applyTo(doc("ab\n\ncd"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("abcd")
        assertThat(result?.selection?.focus).isEqualTo(Caret(1L, 2))
    }

    @Test
    fun applyTo_leftAtTheStart_movesToTheEndOfThePreviousBlock() {
        val before = doc("ab\n\ncd")

        val result = key(FieldKey.LEFT, 0).applyTo(before, 2L)

        assertThat(result?.document).isSameInstanceAs(before)
        assertThat(result?.selection?.focus).isEqualTo(Caret(1L, 2))
    }

    @Test
    fun applyTo_rightAtTheEnd_movesToTheStartOfTheNextBlock() {
        val result = key(FieldKey.RIGHT, 2).applyTo(doc("ab\n\ncd"), 1L)

        assertThat(result?.selection?.focus).isEqualTo(Caret(2L, 0))
    }

    @Test
    fun applyTo_leftInTheFirstBlockStart_isNull() {
        assertThat(key(FieldKey.LEFT, 0).applyTo(doc("ab"), 1L)).isNull()
    }

    @Test
    fun applyTo_tabInASecondListItem_indentsIt() {
        // Every list item is a block of its own: the second item is block 2.
        val result = key(FieldKey.TAB, 3).applyTo(doc("- a\n- b"), 2L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("- a\n  - b")
        assertThat(result?.selection?.focus).isEqualTo(Caret(2L, 5))
    }

    @Test
    fun applyTo_shiftTabInANestedItem_outdentsIt() {
        val result = key(FieldKey.SHIFT_TAB, 5).applyTo(doc("- a\n  - b"), 2L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("- a\n- b")
        assertThat(result?.selection?.focus).isEqualTo(Caret(2L, 3))
    }

    @Test
    fun applyTo_tabInAParagraph_isNull() {
        assertThat(key(FieldKey.TAB, 1).applyTo(doc("a"), 1L)).isNull()
    }

    @Test
    fun applyTo_offsetsPastTheSourceEnd_areClampedToTheBlock() {
        val result = key(FieldKey.RIGHT, 40).applyTo(doc("ab\n\ncd"), 1L)

        assertThat(result?.selection?.focus).isEqualTo(Caret(2L, 0))
    }
}
