package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test

class EditMarkersTest {
    /** The dimmed parts of the single block of [markdown], as the substrings the ranges cover. */
    private fun dimmed(markdown: String): List<String> {
        val block = MarkdownDocument.parse(markdown).blocks.single()
        val ranges = EditMarkers.ranges(block)
        return (ranges.indices step 2).map { block.source.substring(ranges[it], ranges[it + 1]) }
    }

    @Test
    fun ranges_plainParagraph_isEmpty() {
        assertThat(dimmed("Just words")).isEmpty()
    }

    @Test
    fun ranges_heading_dimsTheHashes() {
        assertThat(dimmed("## Title")).containsExactly("## ")
    }

    @Test
    fun ranges_taskItem_dimsBulletAndBox() {
        assertThat(dimmed("  - [ ] todo")).containsExactly("  - [ ] ")
    }

    @Test
    fun ranges_orderedItem_dimsTheNumber() {
        assertThat(dimmed("12. step")).containsExactly("12. ")
    }

    @Test
    fun ranges_quote_dimsTheArrow() {
        assertThat(dimmed("> said")).containsExactly("> ")
    }

    @Test
    fun ranges_boldItalicAndCode_dimTheirDelimiters() {
        assertThat(dimmed("a **b** and *c* and `d`"))
            .containsExactly("**", "**", "*", "*", "`", "`")
            .inOrder()
    }

    @Test
    fun ranges_highlightAndStrike_dimDoubleDelimiters() {
        assertThat(dimmed("==hi== ~~no~~")).containsExactly("==", "==", "~~", "~~").inOrder()
    }

    @Test
    fun ranges_link_dimsBracketsAndTarget() {
        assertThat(dimmed("see [the docs](https://example.com) now")).containsExactly("[", "](https://example.com)").inOrder()
    }

    @Test
    fun ranges_docLink_dimsTheDoubleBrackets() {
        assertThat(dimmed("go [[Other note]] next")).containsExactly("[[", "]]").inOrder()
    }

    @Test
    fun ranges_inlineMath_dimsTheDollars() {
        assertThat(dimmed("so \$x^2\$ holds")).containsExactly("\$", "\$").inOrder()
    }

    @Test
    fun ranges_listItemWithBold_mergesTouchingRanges() {
        assertThat(dimmed("- **x**")).containsExactly("- **", "**").inOrder()
    }

    @Test
    fun ranges_boldAndItalicTogether_areMergedIntoOneRange() {
        val block = MarkdownDocument.parse("***x***").blocks.single()
        val ranges = EditMarkers.ranges(block)

        assertThat(ranges.toList()).isEqualTo(listOf(0, 3, 4, 7))
    }

    @Test
    fun ranges_closedFence_dimsBothFenceLines() {
        assertThat(dimmed("```kotlin\nval a = 1\n```")).containsExactly("```kotlin", "```").inOrder()
    }

    @Test
    fun ranges_openFence_dimsOnlyTheOpeningLine() {
        assertThat(dimmed("```\ncode")).containsExactly("```")
    }

    @Test
    fun ranges_table_dimsPipesAndSeparatorRow() {
        val dim = dimmed("| a | b |\n| --- | --- |\n| 1 | 2 |")

        assertThat(dim).contains("| --- | --- |")
        assertThat(dim.count { it == "|" }).isEqualTo(6)
    }

    @Test
    fun ranges_tableCellWithBold_dimsItsDelimitersToo() {
        val dim = dimmed("| **a** | b |\n| --- | --- |\n| 1 | `2` |")

        assertThat(dim.filter { it.trim('|', ' ').isNotEmpty() && !it.startsWith("| -") })
            .containsExactly("**", "**", "`", "`")
            .inOrder()
    }

    @Test
    fun ranges_rule_dimsTheWholeSource() {
        assertThat(dimmed("---")).containsExactly("---")
    }

    @Test
    fun ranges_asteriskInsideWords_isNotDimmed() {
        assertThat(dimmed("2 * 3 = 6")).isEmpty()
    }

    @Test
    fun ranges_areAscendingAndDisjoint() {
        val block = MarkdownDocument.parse("> **a** [b](c) `d` ==e==").blocks.single()
        val ranges = EditMarkers.ranges(block)

        assertThat(ranges.toList().zipWithNext().all { (a, b) -> a < b }).isTrue()
    }
}
