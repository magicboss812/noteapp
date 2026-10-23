package dev.folio.core.text.markdown

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import java.io.File

/** P06-T01: lossless round trip for the 60-case corpus in testdata/format/markdown. */
class MarkdownCorpusTest {
    private val cases: List<Pair<String, String>> = loadCorpus()

    @Test
    fun corpus_has60Cases() {
        assertThat(cases).hasSize(60)
    }

    @Test
    fun parse_corpus_serializesLossless() {
        for ((name, text) in cases) {
            assertWithMessage(name).that(MarkdownDocument.parse(text).toMarkdown()).isEqualTo(text)
        }
    }

    @Test
    fun parse_corpus_blocksReparseToSameContent() {
        for ((name, text) in cases) {
            val doc = MarkdownDocument.parse(text)
            val again = MarkdownDocument.parse(doc.toMarkdown())
            assertWithMessage(name).that(again.blocks.map { it.content }).isEqualTo(doc.blocks.map { it.content })
            assertWithMessage(name).that(again.blocks.map { it.source }).isEqualTo(doc.blocks.map { it.source })
        }
    }

    @Test
    fun parse_corpus_everyBlockSourceParsesAloneToItsOwnContent() {
        for ((name, text) in cases) {
            for (block in MarkdownDocument.parse(text).blocks.filter { it.source.isNotEmpty() }) {
                val alone = MarkdownDocument.parse(block.source).blocks.single()
                assertWithMessage("$name: ${block.source}").that(alone.content).isEqualTo(block.content)
            }
        }
    }

    @Test
    fun parse_corpus_blockIdsAreUnique() {
        for ((name, text) in cases) {
            val ids = MarkdownDocument.parse(text).blocks.map { it.id }
            assertWithMessage(name).that(ids.toSet()).hasSize(ids.size)
        }
    }

    @Test
    fun parse_corpus_spansStayInsideTheirRanges() {
        for ((name, text) in cases) {
            for (block in MarkdownDocument.parse(text).blocks) {
                for (span in inlineTexts(block.content).flatMap { it.second.spans }) {
                    assertWithMessage("$name: $span").that(span.sourceStart).isAtLeast(0)
                    assertWithMessage("$name: $span").that(span.sourceEnd).isAtMost(block.source.length)
                    assertWithMessage("$name: $span").that(span.sourceStart).isAtMost(span.sourceEnd)
                }
            }
        }
    }

    private fun loadCorpus(): List<Pair<String, String>> {
        val lines = File("../../testdata/format/markdown/corpus.md").readText().removeSuffix("\n").split("\n")
        val out = ArrayList<Pair<String, String>>()
        var name: String? = null
        val body = ArrayList<String>()

        fun flush() {
            name?.let { out.add(it to body.joinToString("\n")) }
            body.clear()
        }
        for (line in lines) {
            if (line.startsWith("%%% ")) {
                flush()
                name = line.removePrefix("%%% ")
            } else {
                body.add(line)
            }
        }
        flush()
        return out
    }
}

/** All inline texts of [content] with a label; shared by the parser tests. */
internal fun inlineTexts(content: BlockContent): List<Pair<String, InlineText>> =
    when (content) {
        BlockContent.Empty, BlockContent.Rule, is BlockContent.Code, is BlockContent.Math -> {
            emptyList()
        }

        is BlockContent.Paragraph -> {
            listOf("p" to content.text)
        }

        is BlockContent.Heading -> {
            listOf("h" to content.text)
        }

        is BlockContent.ListItem -> {
            listOf("li" to content.text)
        }

        is BlockContent.Quote -> {
            listOf("q" to content.text)
        }

        is BlockContent.Table -> {
            (content.header + content.rows.flatten()).map { "cell" to it }
        }
    }
