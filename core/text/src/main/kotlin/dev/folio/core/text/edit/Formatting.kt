package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.InlineSpan
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.SpanKind

/** Formatting bar actions as source edits (07-text-engine.md#editing): inline markers, table, link. Block styles are in [BlockStyles]. */
object Formatting {
    private val markers =
        mapOf(
            SpanKind.BOLD to listOf("**", "__"),
            SpanKind.ITALIC to listOf("*", "_"),
            SpanKind.STRIKE to listOf("~~"),
            SpanKind.HIGHLIGHT to listOf("=="),
            SpanKind.CODE to listOf("``", "`"),
            SpanKind.MATH to listOf("$"),
        )

    /**
     * Wraps the selected text in the markers of [kind], or removes them when the selection is already inside such a
     * span; with a collapsed caret an empty pair is inserted and the caret placed between. Null for a selection over
     * several blocks, code, math and tables, or a kind that has no markers.
     */
    fun toggleInline(
        doc: MarkdownDocument,
        selection: TextSelection,
        kind: SpanKind,
    ): EditResult? {
        val choices = markers[kind] ?: return null
        val range = Range.of(doc, selection)
        if (range.first != range.last) return null
        val block = doc.blocks[range.first]
        val spans = inlineSpans(block) ?: return null
        val start = range.startOffset
        val end = range.endOffset
        val enclosing = spans.filter { it.kind == kind && it.encloses(start, end) }.minByOrNull { it.sourceEnd - it.sourceStart }
        val unwrapped = enclosing?.let { unwrap(doc, block, it, choices, start, end) }
        val emptied = if (start == end) removeEmptyPair(doc, block, choices, start) else null
        return unwrapped ?: emptied ?: wrap(doc, block, if (kind == SpanKind.CODE) "`" else choices.first(), start, end)
    }

    /** A caret between a marker pair with nothing inside (what wrapping a collapsed caret leaves) removes the pair again. */
    private fun removeEmptyPair(
        doc: MarkdownDocument,
        block: MarkdownBlock,
        choices: List<String>,
        at: Int,
    ): EditResult? {
        val source = block.source
        val marker =
            choices.firstOrNull { at >= it.length && source.startsWith(it, at) && source.startsWith(it, at - it.length) } ?: return null
        return BlockSplice.replaceSourceAt(doc, block.id, source.removeRange(at - marker.length, at + marker.length), at - marker.length)
    }

    /** A caret must be strictly inside the span's markers; a selection may also cover them. */
    private fun InlineSpan.encloses(
        start: Int,
        end: Int,
    ): Boolean = if (start == end) sourceStart < start && end < sourceEnd else sourceStart <= start && end <= sourceEnd

    private fun inlineSpans(block: MarkdownBlock): List<InlineSpan>? =
        when (val content = block.content) {
            is BlockContent.Paragraph -> content.text.spans
            is BlockContent.Heading -> content.text.spans
            is BlockContent.ListItem -> content.text.spans
            is BlockContent.Quote -> content.text.spans
            BlockContent.Empty -> emptyList()
            else -> null
        }

    private fun unwrap(
        doc: MarkdownDocument,
        block: MarkdownBlock,
        span: InlineSpan,
        choices: List<String>,
        start: Int,
        end: Int,
    ): EditResult? {
        val source = block.source
        val marker =
            choices.firstOrNull { source.startsWith(it, span.sourceStart) && source.substring(0, span.sourceEnd).endsWith(it) }
                ?: return null
        val inner = source.substring(span.sourceStart + marker.length, span.sourceEnd - marker.length)
        val newSource = source.substring(0, span.sourceStart) + inner + source.substring(span.sourceEnd)

        fun moved(position: Int): Int =
            when {
                position <= span.sourceStart -> position
                position < span.sourceStart + marker.length -> span.sourceStart
                position <= span.sourceEnd - marker.length -> position - marker.length
                else -> position - 2 * marker.length
            }
        val result = BlockSplice.replaceSource(doc, block.id, newSource)
        return EditResult(result, selectionIn(result, block.id, moved(start), moved(end)))
    }

    private fun wrap(
        doc: MarkdownDocument,
        block: MarkdownBlock,
        marker: String,
        start: Int,
        end: Int,
    ): EditResult {
        val source = block.source
        var from = start
        var to = end
        while (from < to && source[from].isWhitespace()) from++
        while (to > from && source[to - 1].isWhitespace()) to--
        val newSource = source.substring(0, from) + marker + source.substring(from, to) + marker + source.substring(to)
        val result = BlockSplice.replaceSource(doc, block.id, newSource)
        val anchor = from + marker.length
        return EditResult(result, selectionIn(result, block.id, anchor, anchor + (to - from)))
    }

    private fun selectionIn(
        doc: MarkdownDocument,
        blockId: Long,
        from: Int,
        to: Int,
    ) = TextSelection(BlockSplice.caretIn(doc, blockId, from), BlockSplice.caretIn(doc, blockId, to))

    /**
     * Puts a table of [columns] columns after the block at [caret] (in place of an empty paragraph); the caret goes to
     * the first header cell.
     */
    fun insertTable(
        doc: MarkdownDocument,
        caret: Caret,
        columns: Int = 2,
    ): EditResult {
        val index = doc.indexOf(caret.blockId)
        val source = doc.blocks[index].source
        val table = BlockMarkers.table(columns)
        val lead = if (source.isBlank()) "" else "$source\n\n"
        return BlockSplice.replace(doc, Range.block(index), lead + table, lead.length + CELL_CARET)
    }

    /** Turns the selection into a link: `[text]()` with the caret between the parentheses. */
    fun link(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): EditResult? {
        val range = Range.of(doc, selection)
        if (range.first != range.last) return null
        val block = doc.blocks[range.first]
        if (inlineSpans(block) == null) return null
        val text = block.source.substring(range.startOffset, range.endOffset)
        val newSource = block.source.substring(0, range.startOffset) + "[" + text + "]()" + block.source.substring(range.endOffset)
        return BlockSplice.replaceSourceAt(doc, block.id, newSource, range.startOffset + text.length + LINK_URL_OFFSET)
    }

    private const val CELL_CARET = 2
    private const val LINK_URL_OFFSET = 3
}
