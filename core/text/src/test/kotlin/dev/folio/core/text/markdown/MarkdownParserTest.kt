package dev.folio.core.text.markdown

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class MarkdownParserTest {
    private fun single(markdown: String): BlockContent =
        MarkdownDocument
            .parse(markdown)
            .blocks
            .single()
            .content

    private fun text(markdown: String): InlineText = inlineTexts(single(markdown)).single().second

    private fun spanSource(
        markdown: String,
        kind: SpanKind,
    ): String {
        val span = text(markdown).spans.first { it.kind == kind }
        return markdown.substring(span.sourceStart, span.sourceEnd)
    }

    @Test
    fun parse_paragraphs_splitOnBlankLine() {
        val doc = MarkdownDocument.parse("one\n\ntwo")

        assertThat(doc.blocks.map { it.source }).containsExactly("one", "two").inOrder()
        assertThat(doc.blocks[0].gap).isEqualTo("\n\n")
    }

    @Test
    fun parse_extraBlankLines_becomeEmptyParagraphs() {
        val doc = MarkdownDocument.parse("a\n\n\n\nb")

        assertThat(doc.blocks.map { it.content }.map { it::class.simpleName })
            .containsExactly("Paragraph", "Empty", "Empty", "Paragraph")
            .inOrder()
    }

    @Test
    fun parse_emptyInput_hasNoBlocks() {
        assertThat(MarkdownDocument.parse("").blocks).isEmpty()
        assertThat(MarkdownDocument.parse("").toMarkdown()).isEmpty()
    }

    @Test
    fun parse_trailingNewlinesAndCrlf_roundTrip() {
        for (md in listOf("a\n", "a\n\n", "a\n\n\n", "a\r\nb\r\n\r\nc\r\n", "\n\na", "a\n  ", "- a\n- b\n")) {
            assertThat(MarkdownDocument.parse(md).toMarkdown()).isEqualTo(md)
        }
    }

    @Test
    fun parse_heading_levelAndText() {
        val content = single("## Section **x**")

        assertThat(content).isInstanceOf(BlockContent.Heading::class.java)
        assertThat((content as BlockContent.Heading).level).isEqualTo(2)
        assertThat(content.text.plain).isEqualTo("Section x")
    }

    @Test
    fun parse_setextHeading_isOneBlock() {
        val content = single("Title\n=====")

        assertThat((content as BlockContent.Heading).level).isEqualTo(1)
    }

    @Test
    fun parse_styles_spansCoverMarkersInSource() {
        assertThat(spanSource("a **bold** b", SpanKind.BOLD)).isEqualTo("**bold**")
        assertThat(spanSource("a *it* b", SpanKind.ITALIC)).isEqualTo("*it*")
        assertThat(spanSource("a ~~gone~~ b", SpanKind.STRIKE)).isEqualTo("~~gone~~")
        assertThat(spanSource("a ==mark== b", SpanKind.HIGHLIGHT)).isEqualTo("==mark==")
        assertThat(spanSource("a `x()` b", SpanKind.CODE)).isEqualTo("`x()`")
        assertThat(spanSource("a [t](https://x.y) b", SpanKind.LINK)).isEqualTo("[t](https://x.y)")
    }

    @Test
    fun parse_styles_plainTextDropsMarkers() {
        val t = text("a **bold** and ==mark== and `c`")

        assertThat(t.plain).isEqualTo("a bold and mark and c")
        val bold = t.spans.first { it.kind == SpanKind.BOLD }
        assertThat(t.plain.substring(bold.plainStart, bold.plainEnd)).isEqualTo("bold")
    }

    @Test
    fun parse_nestedStyles_bothSpansPresentOuterFirst() {
        val t = text("***both***")

        assertThat(t.spans.map { it.kind }).containsExactly(SpanKind.ITALIC, SpanKind.BOLD)
        assertThat(t.plain).isEqualTo("both")
    }

    @Test
    fun parse_inlineMath_keepsLatexAndIgnoresMarkdownInside() {
        val t = text("so $" + "a_b * c_d$ holds")

        val math = t.spans.single { it.kind == SpanKind.MATH }
        assertThat(math.target).isEqualTo("a_b * c_d")
        assertThat(t.spans.none { it.kind == SpanKind.ITALIC }).isTrue()
        assertThat(spanSource("so $" + "a_b * c_d$ holds", SpanKind.MATH)).isEqualTo("$" + "a_b * c_d$")
    }

    @Test
    fun parse_dollarAmounts_stayLiteral() {
        val t = text("costs $" + "5 and $" + "6 total")

        assertThat(t.spans).isEmpty()
        assertThat(t.plain).isEqualTo("costs $" + "5 and $" + "6 total")
    }

    @Test
    fun parse_escapedDollar_isLiteral() {
        val t = text("\\$" + "x\\$")

        assertThat(t.spans).isEmpty()
        assertThat(t.plain).isEqualTo("$" + "x$")
    }

    @Test
    fun parse_docLinkShortcut_isDocLinkSpanWithTitle() {
        val t = text("see [[Meeting notes]] ok")

        val span = t.spans.single()
        assertThat(span.kind).isEqualTo(SpanKind.DOC_LINK)
        assertThat(span.target).isNull()
        assertThat(t.plain).isEqualTo("see Meeting notes ok")
        assertThat(spanSource("see [[Meeting notes]] ok", SpanKind.DOC_LINK)).isEqualTo("[[Meeting notes]]")
    }

    @Test
    fun parse_docLinkShortcut_twoLinksAndBrokenOnesInOneLine() {
        val md = "[[A]] and [[B]] but [[C and [[ ]] stay"
        val t = text(md)

        assertThat(t.spans.map { md.substring(it.sourceStart, it.sourceEnd) }).containsExactly("[[A]]", "[[B]]").inOrder()
        assertThat(t.plain).isEqualTo("A and B but [[C and [[ ]] stay")
    }

    @Test
    fun parse_folioLink_targetIsNoteId() {
        val t = text("[Plan](folio://doc/abc-123)")

        assertThat(t.spans.single().kind).isEqualTo(SpanKind.DOC_LINK)
        assertThat(t.spans.single().target).isEqualTo("abc-123")
        assertThat(t.plain).isEqualTo("Plan")
    }

    @Test
    fun parse_hardBreak_isNewlineInPlainText() {
        assertThat(text("a\\\nb").plain).isEqualTo("a\nb")
        assertThat(text("a\nb").plain).isEqualTo("a b")
    }

    @Test
    fun parse_bullets_eachItemIsABlock() {
        val doc = MarkdownDocument.parse("- one\n- two\n  - nested\n1. x\n2. y")

        val items = doc.blocks.map { it.content as BlockContent.ListItem }
        assertThat(items.map { it.kind })
            .containsExactly(ListKind.BULLET, ListKind.BULLET, ListKind.BULLET, ListKind.ORDERED, ListKind.ORDERED)
            .inOrder()
        assertThat(items.map { it.level }).containsExactly(0, 0, 1, 0, 0).inOrder()
        assertThat(items.map { it.number }).containsExactly(null, null, null, 1, 2).inOrder()
        assertThat(items[2].text.plain).isEqualTo("nested")
    }

    @Test
    fun parse_deepIndent_spansKeepBlockSourceOffsets() {
        val md = "      - **x** y"
        val item = single(md) as BlockContent.ListItem

        assertThat(item.level).isEqualTo(3)
        val span = item.text.spans.single()
        assertThat(md.substring(span.sourceStart, span.sourceEnd)).isEqualTo("**x**")
    }

    @Test
    fun parse_taskItems_checkedState() {
        val items = MarkdownDocument.parse("- [ ] open\n- [x] done").blocks.map { it.content as BlockContent.ListItem }

        assertThat(items.map { it.kind }).containsExactly(ListKind.TASK, ListKind.TASK)
        assertThat(items.map { it.checked }).containsExactly(false, true).inOrder()
        assertThat(items.map { it.text.plain }).containsExactly("open", "done").inOrder()
    }

    @Test
    fun parse_paragraphThenOrderedTwo_staysOneParagraph() {
        assertThat(MarkdownDocument.parse("text\n2. not a list").blocks).hasSize(1)
        assertThat(MarkdownDocument.parse("text\n- item").blocks).hasSize(2)
    }

    @Test
    fun parse_quoteAndCallout() {
        val quote = single("> a\n> b") as BlockContent.Quote
        val note = single("> [!NOTE]\n> Remember") as BlockContent.Quote

        assertThat(quote.callout).isNull()
        assertThat(quote.text.plain).isEqualTo("a b")
        assertThat(note.callout).isEqualTo(CalloutType.NOTE)
        assertThat(note.text.plain).isEqualTo("Remember")
    }

    @Test
    fun parse_fencedCode_languageAndBlankLinesInside() {
        val doc = MarkdownDocument.parse("```kotlin\nval a = 1\n\nval b = 2\n```\nafter")

        assertThat(doc.blocks).hasSize(2)
        val code = doc.blocks[0].content as BlockContent.Code
        assertThat(code.language).isEqualTo("kotlin")
        assertThat(code.code).isEqualTo("val a = 1\n\nval b = 2")
    }

    @Test
    fun parse_mathBlock_multiAndSingleLine() {
        assertThat(single("$$" + "\n\\int x\n$$")).isEqualTo(BlockContent.Math("\\int x"))
        assertThat(single("$$" + " a+b $$")).isEqualTo(BlockContent.Math("a+b"))
    }

    @Test
    fun parse_table_headerRowsAndAlignment() {
        val table = single("| a | b |\n|:--|--:|\n| **1** | 2 |\n| 3 | 4 |") as BlockContent.Table

        assertThat(table.aligns).containsExactly(TableAlign.LEFT, TableAlign.RIGHT).inOrder()
        assertThat(table.header.map { it.plain }).containsExactly("a", "b").inOrder()
        assertThat(table.rows.map { r -> r.map { it.plain } }).containsExactly(listOf("1", "2"), listOf("3", "4")).inOrder()
        assertThat(
            table.rows[0][0]
                .spans
                .single()
                .kind,
        ).isEqualTo(SpanKind.BOLD)
    }

    @Test
    fun parse_rules_andUnknownSyntax() {
        assertThat(single("---")).isEqualTo(BlockContent.Rule)
        assertThat(single("***")).isEqualTo(BlockContent.Rule)
        assertThat(single("<div>raw</div>")).isInstanceOf(BlockContent.Paragraph::class.java)
        assertThat(single("[ref]: https://example.com")).isEqualTo(BlockContent.Paragraph(InlineText("[ref]: https://example.com")))
    }

    @Test
    fun plainText_mixedDocument_joinsBlocksForSearch() {
        val doc = MarkdownDocument.parse("# Title\n\nSome **bold** text\n\n- [ ] task\n\n```\ncode\n```\n\n$$\nx^2\n$$")

        assertThat(doc.plainText()).isEqualTo("Title\nSome bold text\ntask\ncode\nx^2")
    }
}
