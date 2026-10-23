package dev.folio.core.text.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownEditTest {
    private val source = (1..50).joinToString("\n\n") { "paragraph $it with **bold** text" }

    @Test
    fun replaceBlock_oneBlock_reparsesOnlyThatBlock() {
        val doc = MarkdownDocument.parse(source)
        val before = doc.parsedBlockCount
        val id = doc.blocks[10].id

        val edited = doc.replaceBlock(id, "changed ==text==")

        assertThat(edited.parsedBlockCount - before).isEqualTo(1)
        assertThat(edited.blocks[10].source).isEqualTo("changed ==text==")
        assertThat(edited.blocks[10].id).isEqualTo(id)
    }

    @Test
    fun replaceBlock_untouchedBlocks_areSharedSameInstances() {
        val doc = MarkdownDocument.parse(source)

        val edited = doc.replaceBlock(doc.blocks[10].id, "x")

        for (i in doc.blocks.indices) {
            if (i != 10) assertThat(edited.blocks[i]).isSameInstanceAs(doc.blocks[i])
        }
    }

    @Test
    fun replaceBlock_keepsSerializationConsistent() {
        val doc = MarkdownDocument.parse("a\n\nb\n\nc")

        val edited = doc.replaceBlock(doc.blocks[1].id, "B2")

        assertThat(edited.toMarkdown()).isEqualTo("a\n\nB2\n\nc")
    }

    @Test
    fun replaceBlock_textWithBlankLine_splitsIntoBlocksKeepingFirstId() {
        val doc = MarkdownDocument.parse("a\n\nb\n\nc")
        val id = doc.blocks[1].id

        val edited = doc.replaceBlock(id, "b1\n\nb2")

        assertThat(edited.blocks.map { it.source }).containsExactly("a", "b1", "b2", "c").inOrder()
        assertThat(edited.blocks[1].id).isEqualTo(id)
        assertThat(edited.blocks.map { it.id }.toSet()).hasSize(4)
        assertThat(edited.toMarkdown()).isEqualTo("a\n\nb1\n\nb2\n\nc")
    }

    @Test
    fun replaceBlock_lastBlockKeepsTrailingNewline() {
        val doc = MarkdownDocument.parse("a\n\nb\n")

        val edited = doc.replaceBlock(doc.blocks[1].id, "bb")

        assertThat(edited.toMarkdown()).isEqualTo("a\n\nbb\n")
    }

    @Test
    fun replaceBlock_changesKind_contentFollowsSource() {
        val doc = MarkdownDocument.parse("plain")

        val edited = doc.replaceBlock(doc.blocks[0].id, "# plain")

        assertThat(edited.blocks[0].content).isInstanceOf(BlockContent.Heading::class.java)
    }

    @Test
    fun replaceBlock_clearedText_staysOneEmptyBlock() {
        val doc = MarkdownDocument.parse("a\n\nb\n\nc")

        val edited = doc.replaceBlock(doc.blocks[1].id, "")

        assertThat(edited.blocks.map { it.content }.map { it::class.simpleName })
            .containsExactly("Paragraph", "Empty", "Paragraph")
            .inOrder()
        assertThat(edited.toMarkdown()).isEqualTo("a\n\n\nc")
        assertThat(MarkdownDocument.parse(edited.toMarkdown()).blocks.map { it.source }).isEqualTo(edited.blocks.map { it.source })
    }

    @Test
    fun replaceBlock_sameSource_returnsSameDocumentWithoutParsing() {
        val doc = MarkdownDocument.parse("a\n\nb")
        val before = doc.parsedBlockCount

        val edited = doc.replaceBlock(doc.blocks[0].id, "a")

        assertThat(edited).isSameInstanceAs(doc)
        assertThat(doc.parsedBlockCount).isEqualTo(before)
    }

    @Test
    fun parse_largeFlow_parsesAllBlocksExactlyOnce() {
        val big = (1..2000).joinToString("\n\n") { "Paragraph $it with some words in it" }

        val doc = MarkdownDocument.parse(big)

        assertThat(doc.blocks).hasSize(2000)
        assertThat(doc.parsedBlockCount).isEqualTo(2000)
    }
}
