package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test

class WordRangesTest {
    /** The source [WordRanges.at] selects in the single block of [markdown] at the offset of the first `|`. */
    private fun word(markdown: String): String {
        val at = markdown.indexOf('|')
        val block = MarkdownDocument.parse(markdown.removeRange(at, at + 1)).blocks.single()
        val range = WordRanges.at(block, at)
        return block.source.substring(range.start, range.end)
    }

    @Test
    fun at_insideAWord_selectsTheWord() {
        assertThat(word("one tw|o three")).isEqualTo("two")
    }

    @Test
    fun at_rightAfterAWordBeforePunctuation_selectsTheWord() {
        assertThat(word("Hello|, world")).isEqualTo("Hello")
    }

    @Test
    fun at_onPunctuation_selectsThePunctuation() {
        assertThat(word("Hello |! world")).isEqualTo("!")
    }

    @Test
    fun at_blankBetweenWords_isEmpty() {
        assertThat(word("one  | two")).isEmpty()
    }

    @Test
    fun at_digitsWithADecimalPoint_selectsTheNumber() {
        assertThat(word("pi is 3.1|4 roughly")).isEqualTo("3.14")
    }

    @Test
    fun at_boldWord_selectsTheTextInsideTheMarkers() {
        assertThat(word("a **bo|ld** b")).isEqualTo("bold")
    }

    @Test
    fun at_onABoldMarker_selectsTheWholeSpan() {
        assertThat(word("a *|*bold** b")).isEqualTo("**bold**")
    }

    @Test
    fun at_insideAFormula_selectsTheFormula() {
        assertThat(word("so \$x_|2\$ holds")).isEqualTo("\$x_2\$")
    }

    @Test
    fun at_onTheFormulaDollar_selectsTheFormula() {
        assertThat(word("so |\$x\$ holds")).isEqualTo("\$x\$")
    }

    @Test
    fun at_headingMarker_selectsTheMarker() {
        assertThat(word("#|# Title")).isEqualTo("## ")
    }

    @Test
    fun at_endOfTheBlock_selectsTheLastWord() {
        assertThat(word("one two|")).isEqualTo("two")
    }

    @Test
    fun at_offsetPastTheEnd_selectsTheLastWord() {
        val block = MarkdownDocument.parse("one two").blocks.single()
        assertThat(WordRanges.at(block, 99)).isEqualTo(WordRange(4, 7))
    }
}
