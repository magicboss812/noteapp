package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.markdown.SpanKind
import org.junit.Test

class FormattingTest {
    private fun inline(
        text: String,
        kind: SpanKind,
    ): String? = marked(text).let { Formatting.toggleInline(it.doc, it.selection, kind) }?.render()

    private fun style(
        text: String,
        style: BlockStyle,
    ): String? = marked(text).let { BlockStyles.apply(it.doc, it.caret, style) }?.render()

    @Test
    fun toggleInline_bold_wrapsTheSelection() {
        assertThat(inline("a «bc» d", SpanKind.BOLD)).isEqualTo("a **«bc»** d")
    }

    @Test
    fun toggleInline_boldAgain_unwrapsIt() {
        assertThat(inline("a **«bc»** d", SpanKind.BOLD)).isEqualTo("a «bc» d")
    }

    @Test
    fun toggleInline_caretInsideBold_unwrapsTheSpan() {
        assertThat(inline("a **b^c** d", SpanKind.BOLD)).isEqualTo("a b^c d")
    }

    @Test
    fun toggleInline_selectionCoveringTheMarkers_unwraps() {
        assertThat(inline("a «**bc**» d", SpanKind.BOLD)).isEqualTo("a «bc» d")
    }

    @Test
    fun toggleInline_collapsedCaret_insertsAnEmptyPair() {
        assertThat(inline("a^b", SpanKind.BOLD)).isEqualTo("a**^**b")
    }

    @Test
    fun toggleInline_caretInsideAnEmptyPair_removesIt() {
        assertThat(inline("a**^**b", SpanKind.BOLD)).isEqualTo("a^b")
    }

    @Test
    fun toggleInline_italicInsideBold_wrapsOnlyTheSelection() {
        assertThat(inline("**a «b» c**", SpanKind.ITALIC)).isEqualTo("**a *«b»* c**")
    }

    @Test
    fun toggleInline_selectionWithOuterSpaces_keepsTheSpacesOutside() {
        assertThat(inline("a« bc »d", SpanKind.BOLD)).isEqualTo("a **«bc»** d")
    }

    @Test
    fun toggleInline_highlightStrikeCodeMath_useTheirOwnMarkers() {
        assertThat(inline("«x»", SpanKind.HIGHLIGHT)).isEqualTo("==«x»==")
        assertThat(inline("«x»", SpanKind.STRIKE)).isEqualTo("~~«x»~~")
        assertThat(inline("«x»", SpanKind.CODE)).isEqualTo("`«x»`")
        assertThat(inline("«x»", SpanKind.MATH)).isEqualTo("$«x»$")
    }

    @Test
    fun toggleInline_inCodeBlock_isRefused() {
        assertThat(inline("```\n«x»\n```", SpanKind.BOLD)).isNull()
    }

    @Test
    fun toggleInline_acrossBlocks_isRefused() {
        assertThat(inline("«a\n\nb»", SpanKind.BOLD)).isNull()
    }

    @Test
    fun toggleInline_inHeading_wrapsTheText() {
        assertThat(inline("# «Title»", SpanKind.ITALIC)).isEqualTo("# *«Title»*")
    }

    @Test
    fun toggleInline_inTableCell_wrapsAndUnwrapsTheCellText() {
        assertThat(inline("| a |\n| - |\n| «x» |", SpanKind.BOLD)).isEqualTo("| a |\n| - |\n| **«x»** |")
        assertThat(inline("| a |\n| - |\n| **x^** |", SpanKind.BOLD)).isEqualTo("| a |\n| - |\n| x^ |")
    }

    @Test
    fun setStyle_paragraphToHeading_addsTheMarkerAndKeepsTheCaretInTheText() {
        assertThat(style("ab^c", BlockStyle.HEADING_2)).isEqualTo("## ab^c")
    }

    @Test
    fun setStyle_sameStyleAgain_returnsToParagraph() {
        assertThat(style("## ab^c", BlockStyle.HEADING_2)).isEqualTo("ab^c")
    }

    @Test
    fun setStyle_headingToBullet_replacesTheMarker() {
        assertThat(style("# a^", BlockStyle.BULLET)).isEqualTo("- a^")
    }

    @Test
    fun setStyle_listToOtherListType_keepsTheIndent() {
        assertThat(style("- a\n  - b^", BlockStyle.ORDERED)).isEqualTo("- a\n  1. b^")
    }

    @Test
    fun setStyle_task_addsAnUncheckedBox() {
        assertThat(style("a^", BlockStyle.TASK)).isEqualTo("- [ ] a^")
    }

    @Test
    fun setStyle_multiLineParagraphToQuote_prefixesEveryLine() {
        assertThat(style("a  \nb^", BlockStyle.QUOTE)).isEqualTo("> a  \n> b^")
    }

    @Test
    fun setStyle_multiLineParagraphToHeading_joinsTheLines() {
        assertThat(style("a  \nb^", BlockStyle.HEADING_1)).isEqualTo("# a b^")
    }

    @Test
    fun setStyle_quoteToParagraph_stripsEveryMarker() {
        assertThat(style("> a  \n> b^", BlockStyle.QUOTE)).isEqualTo("a  \nb^")
    }

    @Test
    fun setStyle_codeBlock_isRefused() {
        assertThat(style("```\nx^\n```", BlockStyle.HEADING_1)).isNull()
    }

    @Test
    fun styleOf_eachKind_matchesTheBlock() {
        val kinds =
            mapOf(
                "plain" to BlockStyle.PARAGRAPH,
                "# a" to BlockStyle.HEADING_1,
                "### a" to BlockStyle.HEADING_3,
                "- a" to BlockStyle.BULLET,
                "1. a" to BlockStyle.ORDERED,
                "- [ ] a" to BlockStyle.TASK,
                "> a" to BlockStyle.QUOTE,
            )
        for ((text, expected) in kinds) {
            assertThat(BlockStyles.of(marked("$text^").doc.blocks[0])).isEqualTo(expected)
        }
    }

    @Test
    fun insertTable_emptyParagraph_becomesATableWithTheCaretInTheFirstHeaderCell() {
        val m = marked("^")

        val result = Formatting.insertTable(m.doc, m.caret)

        assertThat(result.render()).isEqualTo("| ^ |  |\n| --- | --- |\n|  |  |")
    }

    @Test
    fun insertTable_afterText_putsTheTableInABlockOfItsOwn() {
        val m = marked("abc^")

        val result = Formatting.insertTable(m.doc, m.caret, columns = 3)

        assertThat(result.document.blocks.map { it.source.lines().first() }).containsExactly("abc", "|  |  |  |").inOrder()
        assertThat(result.document.reparsedSources()).isEqualTo(result.document.blocks.map { it.source })
    }

    @Test
    fun link_selection_getsAnEmptyTargetWithTheCaretInside() {
        val m = marked("see «docs» here")

        assertThat(Formatting.link(m.doc, m.selection)?.render()).isEqualTo("see [docs](^) here")
    }
}
