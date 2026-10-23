package dev.folio.feature.editor.text

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.edit.TableEditing
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test

class FieldEditsTest {
    // Block ids of a parsed document count from 1 in source order.
    private fun doc(markdown: String): MarkdownDocument = MarkdownDocument.parse(markdown)

    private companion object {
        const val TABLE = "| a   | b   |\n| --- | --- |\n| x   | y   |"
    }

    private fun key(
        key: FieldKey,
        start: Int,
        end: Int = start,
    ) = FieldEdit.Key(key, start, end)

    @Test
    fun applyTo_cellTyped_writesTheCellAndNormalizesTheTable() {
        val result = FieldEdit.Cell(TableCell(1, 0), "wide x", 6).applyTo(doc(TABLE), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("| a      | b   |\n| ------ | --- |\n| wide x | y   |")
        val focus = checkNotNull(result).selection.focus
        assertThat(TableEditing.cellAt(result.document.blocks[0].source, focus.offset).cell).isEqualTo(TableCell(1, 0))
    }

    @Test
    fun applyTo_tabInACell_movesToTheNextCell() {
        val result = FieldEdit.Key(FieldKey.TAB, 1, 1, TableCell(0, 0)).applyTo(doc(TABLE), 1L)

        val focus = checkNotNull(result).selection.focus
        assertThat(TableEditing.cellAt(result.document.blocks[0].source, focus.offset).cell).isEqualTo(TableCell(0, 1))
    }

    @Test
    fun applyTo_shiftTabInTheFirstCell_doesNothing() {
        assertThat(FieldEdit.Key(FieldKey.SHIFT_TAB, 0, 0, TableCell(0, 0)).applyTo(doc(TABLE), 1L)).isNull()
    }

    @Test
    fun applyTo_backspaceAtACellStart_doesNothing() {
        assertThat(FieldEdit.Key(FieldKey.BACKSPACE, 0, 0, TableCell(1, 1)).applyTo(doc(TABLE), 1L)).isNull()
    }

    @Test
    fun applyTo_rightAtTheLastCellEnd_leavesTheTable() {
        val result = FieldEdit.Key(FieldKey.RIGHT, 1, 1, TableCell(1, 1)).applyTo(doc("$TABLE\n\nafter"), 1L)

        assertThat(result?.selection?.focus).isEqualTo(Caret(2L, 0))
    }

    @Test
    fun applyTo_enterInACell_goesOneRowDown() {
        val result = FieldEdit.Key(FieldKey.ENTER, 0, 0, TableCell(0, 1)).applyTo(doc(TABLE), 1L)

        val focus = checkNotNull(result).selection.focus
        assertThat(TableEditing.cellAt(result.document.blocks[0].source, focus.offset).cell).isEqualTo(TableCell(1, 1))
    }

    @Test
    fun applyTo_tableButtons_addRowAddColumnAndAlign() {
        val row = FieldEdit.Format(FormatAction.TABLE_ROW_ADD, 0, 0, TableCell(1, 0)).applyTo(doc(TABLE), 1L)
        val column = FieldEdit.Format(FormatAction.TABLE_COLUMN_ADD, 0, 0, TableCell(0, 1)).applyTo(doc(TABLE), 1L)
        val align = FieldEdit.Format(FormatAction.TABLE_ALIGN_CENTER, 0, 0, TableCell(1, 1)).applyTo(doc(TABLE), 1L)

        assertThat(row?.document?.toMarkdown()).isEqualTo("$TABLE\n|     |     |")
        assertThat(column?.document?.toMarkdown()).isEqualTo("| a   | b   |     |\n| --- | --- | --- |\n| x   | y   |     |")
        assertThat(align?.document?.toMarkdown()).isEqualTo("| a   | b   |\n| --- | :-: |\n| x   | y   |")
    }

    @Test
    fun applyTo_tableButtonsRemove_dropTheRowAndTheColumn() {
        val row = FieldEdit.Format(FormatAction.TABLE_ROW_REMOVE, 0, 0, TableCell(1, 0)).applyTo(doc("$TABLE\n| z   | w   |"), 1L)
        val column = FieldEdit.Format(FormatAction.TABLE_COLUMN_REMOVE, 0, 0, TableCell(0, 0)).applyTo(doc(TABLE), 1L)

        assertThat(row?.document?.toMarkdown()).isEqualTo("| a   | b   |\n| --- | --- |\n| z   | w   |")
        assertThat(column?.document?.toMarkdown()).isEqualTo("| b   |\n| --- |\n| y   |")
    }

    @Test
    fun applyTo_boldInACell_wrapsTheCellSelection() {
        val result = FieldEdit.Format(FormatAction.BOLD, 0, 1, TableCell(1, 1)).applyTo(doc(TABLE), 1L)

        assertThat(result?.document?.toMarkdown()).contains("| **y**")
    }

    @Test
    fun applyTo_headingInACell_doesNothing() {
        assertThat(FieldEdit.Format(FormatAction.HEADING_1, 0, 0, TableCell(1, 1)).applyTo(doc(TABLE), 1L)).isNull()
    }

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
    fun applyTo_formatBoldOverASelection_wrapsItInMarkers() {
        val result = FieldEdit.Format(FormatAction.BOLD, 0, 5).applyTo(doc("alpha beta"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("**alpha** beta")
    }

    @Test
    fun applyTo_formatHeadingTwice_givesAHeadingThenAParagraphAgain() {
        val heading = FieldEdit.Format(FormatAction.HEADING_2, 2, 2).applyTo(doc("alpha"), 1L)
        val markdown = checkNotNull(heading).document.toMarkdown()
        val block = heading.document.blocks[0].id
        val back = FieldEdit.Format(FormatAction.HEADING_2, 5, 5).applyTo(heading.document, block)

        assertThat(markdown).isEqualTo("## alpha")
        assertThat(back?.document?.toMarkdown()).isEqualTo("alpha")
    }

    @Test
    fun applyTo_formatBulletOnAParagraph_makesAListItemAndKeepsTheCaretInTheText() {
        val result = FieldEdit.Format(FormatAction.BULLET, 3, 3).applyTo(doc("alpha"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("- alpha")
        assertThat(result?.selection?.focus?.offset).isEqualTo(5)
    }

    @Test
    fun applyTo_formatLink_wrapsTheSelectionWithTheCaretInTheUrl() {
        val result = FieldEdit.Format(FormatAction.LINK, 0, 5).applyTo(doc("alpha"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("[alpha]()")
        assertThat(result?.selection?.focus?.offset).isEqualTo(8)
    }

    @Test
    fun applyTo_formatTable_addsATableAfterTheBlock() {
        val result = FieldEdit.Format(FormatAction.TABLE, 5, 5).applyTo(doc("alpha"), 1L)

        assertThat(result?.document?.toMarkdown()).startsWith("alpha\n\n|")
    }

    @Test
    fun applyTo_formatOffsetsPastTheSource_areClamped() {
        val result = FieldEdit.Format(FormatAction.ITALIC, 3, 99).applyTo(doc("alpha"), 1L)

        assertThat(result?.document?.toMarkdown()).isEqualTo("alp*ha*")
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
