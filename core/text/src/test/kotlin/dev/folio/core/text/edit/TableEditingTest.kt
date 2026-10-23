package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.TableAlign
import org.junit.Test
import kotlin.random.Random

class TableEditingTest {
    private fun edit(
        text: String,
        op: (MarkdownDocument, Caret) -> EditResult?,
    ): String? = marked(text).let { op(it.doc, it.caret) }?.render()

    private fun move(
        text: String,
        op: (MarkdownDocument, Caret) -> Caret?,
    ): String? =
        marked(text).let { m ->
            op(m.doc, m.caret)?.let { EditResult(m.doc, TextSelection.at(it)) }?.render()
        }

    // Cells in the source

    @Test
    fun cellAt_offsets_cellOfTheSegment_delimiterStandsForTheHeader() {
        val source = "| a | bc |\n| --- | --- |\n| 1 | 2 |"

        assertThat(TableEditing.cellAt(source, 0)).isEqualTo(CellSpan(TableCell(0, 0), 2, 3))
        assertThat(TableEditing.cellAt(source, 4)).isEqualTo(CellSpan(TableCell(0, 0), 2, 3))
        assertThat(TableEditing.cellAt(source, 6)).isEqualTo(CellSpan(TableCell(0, 1), 6, 8))
        assertThat(TableEditing.cellAt(source, 14).cell).isEqualTo(TableCell(0, 0))
        assertThat(TableEditing.cellAt(source, source.length - 2).cell).isEqualTo(TableCell(1, 1))
    }

    @Test
    fun cellAt_withoutOuterPipes_cellsSplitAtInnerPipes() {
        val source = "a | b\n--- | ---\n1 | 2"

        assertThat(TableEditing.cellAt(source, 0)).isEqualTo(CellSpan(TableCell(0, 0), 0, 1))
        assertThat(TableEditing.cellAt(source, 4)).isEqualTo(CellSpan(TableCell(0, 1), 4, 5))
        assertThat(TableEditing.spanOf(source, TableCell(1, 1))).isEqualTo(CellSpan(TableCell(1, 1), 20, 21))
    }

    @Test
    fun spanOf_escapedPipeAndMissingCell_staysInTheCell_emptyAtTheRowEnd() {
        val source = "| a | b |\n| --- | --- |\n| x\\|y |"

        val first = TableEditing.spanOf(source, TableCell(1, 0))
        assertThat(source.substring(first.start, first.end)).isEqualTo("x\\|y")
        assertThat(TableEditing.spanOf(source, TableCell(1, 1))).isEqualTo(CellSpan(TableCell(1, 1), source.length, source.length))
    }

    // Typing in a cell

    @Test
    fun setCell_pipeAndLineBreak_escapedAndJoined_tableNormalized() {
        val result = edit("| a | b |\n| --- | --- |\n| 1^ | 2 |") { d, c -> TableEditing.setCell(d, c, "x|y\nz", 3) }

        assertThat(result).isEqualTo("| a      | b   |\n| ------ | --- |\n| x\\|y^ z | 2   |")
    }

    @Test
    fun setCell_header_keepsTheAlignmentRow() {
        val result = edit("| a | b |\n| :-: | --: |\n| 1 | 2 |^") { d, c -> TableEditing.setCell(d, c, "longer", 6) }

        assertThat(result).isEqualTo("| a   | b      |\n| :-: | -----: |\n| 1   | longer^ |")
    }

    // Moving between cells

    @Test
    fun nextCell_inARow_startOfTheNextCell() {
        assertThat(edit("| a^ | b |\n| --- | --- |") { d, c -> TableEditing.nextCell(d, c) })
            .isEqualTo("| a   | ^b   |\n| --- | --- |")
    }

    @Test
    fun nextCell_lastCell_addsARow() {
        assertThat(edit("| a | b |\n| --- | --- |\n| 1 | 2^ |") { d, c -> TableEditing.nextCell(d, c) })
            .isEqualTo("| a   | b   |\n| --- | --- |\n| 1   | 2   |\n| ^    |     |")
    }

    @Test
    fun previousCell_firstCell_null_elseTheCellBefore() {
        assertThat(edit("| ^a | b |\n| --- | --- |") { d, c -> TableEditing.previousCell(d, c) }).isNull()
        assertThat(edit("| a | b |\n| --- | --- |\n| ^1 | 2 |") { d, c -> TableEditing.previousCell(d, c) })
            .isEqualTo("| a   | ^b   |\n| --- | --- |\n| 1   | 2   |")
    }

    @Test
    fun leftAndRight_atCellEdges_jumpOverThePipes_outOfTheTableAtItsEnds() {
        val table = "| a | b |\n| --- | --- |\n| 1 | 2 |"
        assertThat(move("| a | ^b |\n| --- | --- |\n| 1 | 2 |") { d, c -> TableEditing.left(d, c) })
            .isEqualTo("| a^ | b |\n| --- | --- |\n| 1 | 2 |")
        assertThat(move("| a | b |\n| --- | --- |\n| 1 | 2^ |\n\nend") { d, c -> TableEditing.right(d, c) })
            .isEqualTo("$table\n\n^end")
        assertThat(move("before\n\n| ^a | b |\n| --- | --- |") { d, c -> TableEditing.left(d, c) })
            .isEqualTo("before^\n\n| a | b |\n| --- | --- |")
        assertThat(move("| a | b^ |\n| --- | --- |\n| 1 | 2 |") { d, c -> TableEditing.right(d, c) })
            .isEqualTo("| a | b |\n| --- | --- |\n| ^1 | 2 |")
    }

    @Test
    fun outsideATable_null() {
        assertThat(edit("text^") { d, c -> TableEditing.nextCell(d, c) }).isNull()
        assertThat(edit("text^") { d, c -> TableEditing.addRow(d, c) }).isNull()
        assertThat(move("text^") { d, c -> TableEditing.left(d, c) }).isNull()
    }

    // Rows, columns, alignment

    @Test
    fun addRow_fromTheHeader_firstBodyRow() {
        assertThat(edit("| a^ | b |\n| --- | --- |\n| 1 | 2 |") { d, c -> TableEditing.addRow(d, c) })
            .isEqualTo("| a   | b   |\n| --- | --- |\n| ^    |     |\n| 1   | 2   |")
    }

    @Test
    fun removeRow_header_null_bodyRow_removed() {
        assertThat(edit("| a^ | b |\n| --- | --- |\n| 1 | 2 |") { d, c -> TableEditing.removeRow(d, c) }).isNull()
        assertThat(edit("| a | b |\n| --- | --- |\n| 1 | 2^ |\n| 3 | 4 |") { d, c -> TableEditing.removeRow(d, c) })
            .isEqualTo("| a   | b   |\n| --- | --- |\n| 3   | ^4   |")
    }

    @Test
    fun addColumn_rightOfTheCaret_caretInIt() {
        assertThat(edit("| a^ | b |\n| :-: | --- |\n| 1 | 2 |") { d, c -> TableEditing.addColumn(d, c) })
            .isEqualTo("| a   | ^    | b   |\n| :-: | --- | --- |\n| 1   |     | 2   |")
    }

    @Test
    fun removeColumn_removesIt_theOnlyColumn_removesTheTable() {
        assertThat(edit("| a | b^ |\n| --- | --: |\n| 1 | 2 |") { d, c -> TableEditing.removeColumn(d, c) })
            .isEqualTo("| ^a   |\n| --- |\n| 1   |")
        assertThat(edit("before\n\n| a^ |\n| --- |\n\nafter") { d, c -> TableEditing.removeColumn(d, c) })
            .isEqualTo("before\n\n^\nafter")
    }

    @Test
    fun align_setsTheDelimiter_sameAlignmentAgain_none() {
        val right = edit("| a | b^ |\n| --- | --- |") { d, c -> TableEditing.align(d, c, TableAlign.RIGHT) }
        assertThat(right).isEqualTo("| a   | b^   |\n| --- | --: |")
        val again = marked(checkNotNull(right)).let { TableEditing.align(it.doc, it.caret, TableAlign.RIGHT) }?.render()
        assertThat(again).isEqualTo("| a   | b^   |\n| --- | --- |")
        assertThat(edit("| a^ |\n| --- |") { d, c -> TableEditing.align(d, c, TableAlign.CENTER) })
            .isEqualTo("| a^   |\n| :-: |")
    }

    // Round trip

    @Test
    fun randomEdits_markdownRoundTrip_parsedTableMatchesTheCells() {
        val seed = 20261024L
        val random = Random(seed)
        var doc = MarkdownDocument.parse("| h1 | h2 |\n| --- | :-: |\n| a | b |")
        var caret = Caret(doc.blocks.single().id, 2)
        val words = listOf("x", "a|b", "", "**bold**", "\$x^2\$", "long cell text", "a\\|b", "line\nbreak")
        repeat(STEPS) { step ->
            val text = words[random.nextInt(words.size)]
            val result =
                when (random.nextInt(9)) {
                    0 -> TableEditing.setCell(doc, caret, text, text.length)
                    1 -> TableEditing.nextCell(doc, caret)
                    2 -> TableEditing.previousCell(doc, caret)
                    3 -> TableEditing.enter(doc, caret)
                    4 -> TableEditing.addRow(doc, caret)
                    5 -> TableEditing.removeRow(doc, caret)
                    6 -> TableEditing.addColumn(doc, caret)
                    7 -> TableEditing.align(doc, caret, TableAlign.entries[random.nextInt(TableAlign.entries.size)])
                    else -> null
                }
            if (result == null ||
                result.document.blocks
                    .first()
                    .content !is BlockContent.Table
            ) {
                return@repeat
            }
            doc = result.document
            caret = result.selection.focus
            val source = doc.blocks.first().source
            val reparsed = MarkdownDocument.parse(doc.toMarkdown()).blocks.first()
            val message = "seed $seed step $step:\n$source"
            assertWithMessage(message).that(reparsed.source).isEqualTo(source)
            val content = reparsed.content as? BlockContent.Table
            assertWithMessage(message).that(content).isNotNull()
            val grid = TableGrid.parse(source)
            assertWithMessage(message).that(checkNotNull(content).aligns).isEqualTo(grid.aligns)
            assertWithMessage(message).that(content.header.size).isEqualTo(grid.columns)
            assertWithMessage(message).that(content.rows.size).isEqualTo(grid.rows.size - 1)
            assertWithMessage(message).that(grid.serialize()).isEqualTo(source)
        }
    }

    private companion object {
        const val STEPS = 300
    }
}
