package dev.folio.core.text.layout

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.GridUnit
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.TableAlign
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TableLayoutTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = BlockLayouter(resolver, FontMetricsCache(resolver))
    private val lined = LayoutGrid.of(GridUnit(GridUnit.COLLEGE_UNIT_PT, 0f, 36f, snapsColumns = false))
    private val graph = LayoutGrid.of(GridUnit(GridUnit.GRAPH_UNIT_PT, 0f, 36f, snapsColumns = true))

    private fun content(markdown: String) =
        MarkdownDocument
            .parse(markdown)
            .blocks
            .single()
            .content

    private fun layout(
        markdown: String,
        grid: LayoutGrid = lined,
    ): BlockLayout = layouter.build(content(markdown), WIDTH_PT, FlowTextStyle(), grid)

    private fun table(layout: BlockLayout): TableLayout = checkNotNull(layout.table)

    @Test
    fun table_rowHeightIsTheTallestCell_lineCountSumsTheRows() {
        val long = "word ".repeat(30).trim()
        val result = layout("| a | b |\n| --- | --- |\n| short | $long |\n| x | y |")
        val t = table(result)

        assertThat(t.rows).isEqualTo(3)
        assertThat(t.columns).isEqualTo(2)
        assertThat(t.rowLineCount(0)).isEqualTo(1)
        assertThat(t.rowLineCount(1)).isEqualTo(t.cellLineCount(t.cellIndex(1, 1)))
        assertThat(t.rowLineCount(1)).isGreaterThan(1)
        assertThat(t.rowLineCount(2)).isEqualTo(1)
        assertThat(result.lineCount).isEqualTo(t.rowFirstLine(3))
        assertThat(result.heightPt).isWithin(TOLERANCE_PT).of(result.lineCount * lined.unitPt)
    }

    @Test
    fun table_cellBaselines_onTheRulesOfTheirRow() {
        val result = layout("| Name | Note |\n| :-- | --: |\n| a | ${"long text ".repeat(12).trim()} |")
        val t = table(result)
        val unit = lined.unitPt

        for (row in 0 until t.rows) {
            for (column in 0 until t.columns) {
                val cell = t.cellIndex(row, column)
                for (line in 0 until t.cellLineCount(cell)) {
                    val baseline = result.lineTopPt(t.rowFirstLine(row)) + t.cellBaselinePt(cell, line)
                    val rule = (t.rowFirstLine(row) + line + 1) * unit
                    assertWithMessage("cell $cell line $line").that(baseline).isWithin(TOLERANCE_PT).of(rule)
                }
            }
        }
    }

    @Test
    fun table_columns_fillTheWidth_paddingInside_alignmentsFromTheDelimiter() {
        val t = table(layout("| a | b | c |\n| :-- | :-: | --: |\n| 1 | 2 | 3 |"))

        assertThat(t.columnLeftPt(0)).isEqualTo(0f)
        assertThat(t.columnLeftPt(t.columns)).isWithin(TOLERANCE_PT).of(WIDTH_PT)
        assertThat(t.aligns).containsExactly(TableAlign.LEFT, TableAlign.CENTER, TableAlign.RIGHT).inOrder()
        assertThat(t.paddingPt).isWithin(TOLERANCE_PT).of(0.4f * lined.unitPt)
        assertThat(t.textLeftPt(t.cellIndex(1, 1))).isWithin(TOLERANCE_PT).of(t.columnLeftPt(1) + t.paddingPt)
        assertThat(t.headerFace.bold).isTrue()
        assertThat(t.cellText(t.cellIndex(0, 2))).isEqualTo("c")
    }

    @Test
    fun table_wideContent_widerColumn() {
        val t = table(layout("| id | description |\n| --- | --- |\n| 1 | a much longer description of the row |"))

        val first = t.columnLeftPt(1) - t.columnLeftPt(0)
        val second = t.columnLeftPt(2) - t.columnLeftPt(1)
        assertThat(second).isGreaterThan(first)
        assertThat(first).isAtLeast(TableColumns.MIN_COLUMN_PT - TOLERANCE_PT)
    }

    @Test
    fun table_graphGrid_innerBordersOnGridColumns() {
        val t = table(layout("| a | bb | ccc |\n| --- | --- | --- |\n| 1 | 22 | 333 |", graph))

        for (c in 1 until t.columns) {
            val steps = t.columnLeftPt(c) / graph.unitPt
            assertWithMessage("edge $c").that(steps).isWithin(TOLERANCE_PT).of(Math.round(steps).toFloat())
        }
    }

    @Test
    fun table_missingCells_emptyCellsAndTheHeaderColumnCount() {
        val t = table(layout("| a | b | c |\n| --- | --- | --- |\n| 1 |"))

        assertThat(t.columns).isEqualTo(3)
        assertThat(t.cellText(t.cellIndex(1, 2))).isEmpty()
    }

    @Test
    fun flowItem_table_breaksBetweenBodyRows_headerKeptWithTheFirstRow() {
        val markdown = "| a | b |\n| --- | --- |\n| ${"word ".repeat(30).trim()} | 1 |\n| x | 2 |\n| y | 3 |"
        val result = layout(markdown)
        val t = table(result)
        val item = FlowLayouter.itemOf(content(markdown), result)

        assertThat(item.heightUnits).isEqualTo(result.lineCount)
        assertThat(item.breakUnits.toList()).containsExactly(t.rowFirstLine(2), t.rowFirstLine(3)).inOrder()
        val small = "| a |\n| --- |\n| b |"
        assertThat(FlowLayouter.itemOf(content(small), layout(small)).breakUnits.toList()).isEmpty()
    }

    @Test
    fun table_tapPositions_rowAndColumnUnderThePoint() {
        val result = layout("| a | b |\n| --- | --- |\n| ${"word ".repeat(30).trim()} | y |")
        val t = table(result)

        assertThat(t.rowOfLine(0)).isEqualTo(0)
        assertThat(t.rowOfLine(1)).isEqualTo(1)
        assertThat(t.rowOfLine(result.lineCount + 3)).isEqualTo(1)
        assertThat(t.columnAt(-5f)).isEqualTo(0)
        assertThat(t.columnAt(t.columnLeftPt(1) + 1f)).isEqualTo(1)
        assertThat(t.columnAt(WIDTH_PT + 50f)).isEqualTo(1)
    }

    private companion object {
        const val WIDTH_PT = 400f
        const val TOLERANCE_PT = 1e-3f
    }
}
