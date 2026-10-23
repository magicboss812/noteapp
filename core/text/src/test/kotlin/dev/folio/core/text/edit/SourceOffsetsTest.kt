package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test

class SourceOffsetsTest {
    private fun block(markdown: String): MarkdownBlock = MarkdownDocument.parse(markdown).blocks.single()

    @Test
    fun fromPlain_plainParagraph_isTheSameOffset() {
        val block = block("Hello world")

        assertThat((0..11).map { SourceOffsets.fromPlain(block, it) }).isEqualTo((0..11).toList())
    }

    @Test
    fun fromPlain_heading_skipsTheHashes() {
        val block = block("## Title")

        assertThat(SourceOffsets.fromPlain(block, 0)).isEqualTo(3)
        assertThat(SourceOffsets.fromPlain(block, 2)).isEqualTo(5)
    }

    @Test
    fun fromPlain_listItem_skipsBulletAndBox() {
        val block = block("  - [ ] todo")

        assertThat(SourceOffsets.fromPlain(block, 0)).isEqualTo(8)
        assertThat(SourceOffsets.fromPlain(block, 3)).isEqualTo(11)
    }

    @Test
    fun fromPlain_bold_skipsTheDelimiters() {
        val block = block("a **b** c")

        assertThat(SourceOffsets.fromPlain(block, 2)).isEqualTo(4)
        assertThat(SourceOffsets.fromPlain(block, 3)).isEqualTo(7)
        assertThat(SourceOffsets.fromPlain(block, 4)).isEqualTo(8)
    }

    @Test
    fun fromPlain_link_skipsBracketsAndTarget() {
        val block = block("[docs](http://x) now")

        assertThat(SourceOffsets.fromPlain(block, 0)).isEqualTo(1)
        assertThat(SourceOffsets.fromPlain(block, 4)).isEqualTo(16)
        assertThat(SourceOffsets.fromPlain(block, 5)).isEqualTo(17)
    }

    @Test
    fun fromPlain_escapedCharacter_pointsAtTheCharacter() {
        val block = block("a \\* b")

        assertThat(SourceOffsets.fromPlain(block, 2)).isEqualTo(3)
    }

    @Test
    fun fromPlain_softBreak_matchesTheLineBreak() {
        val block = block("one\ntwo")

        assertThat(SourceOffsets.fromPlain(block, 3)).isEqualTo(3)
        assertThat(SourceOffsets.fromPlain(block, 4)).isEqualTo(4)
    }

    @Test
    fun fromPlain_codeBlock_startsAfterTheFenceLine() {
        val block = block("```kotlin\nval a\n```")

        assertThat(SourceOffsets.fromPlain(block, 0)).isEqualTo(10)
        assertThat(SourceOffsets.fromPlain(block, 4)).isEqualTo(14)
    }

    @Test
    fun fromPlain_callout_ignoresTheMarkerLine() {
        val block = block("> [!NOTE]\n> hello note")

        assertThat(SourceOffsets.fromPlain(block, 0)).isEqualTo(12)
        assertThat(SourceOffsets.fromPlain(block, 6)).isEqualTo(18)
    }

    @Test
    fun fromPlain_pastTheText_isTheEndOfTheSource() {
        val block = block("**bold**")

        assertThat(SourceOffsets.fromPlain(block, 4)).isEqualTo(8)
        assertThat(SourceOffsets.fromPlain(block, 99)).isEqualTo(8)
    }

    @Test
    fun fromPlain_blocksWithoutOwnText_areTheEndOfTheSource() {
        val table = block("| a | b |\n| --- | --- |\n| 1 | 2 |")

        assertThat(SourceOffsets.fromPlain(table, 1)).isEqualTo(table.source.length)
        assertThat(SourceOffsets.fromPlain(block("---"), 0)).isEqualTo(3)
        assertThat(SourceOffsets.fromPlain(block("\$\$\nx\n\$\$"), 0)).isEqualTo(7)
    }

    @Test
    fun fromPlain_negativeOffset_isTheStartOfTheText() {
        assertThat(SourceOffsets.fromPlain(block("> said"), -3)).isEqualTo(2)
    }
}
