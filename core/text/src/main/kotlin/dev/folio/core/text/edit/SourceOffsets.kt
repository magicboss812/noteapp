package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock

/**
 * Maps a position in what a block shows (its plain text, without markers) to an offset in its Markdown source, so a
 * tap on the rendered text puts the caret on the same character in the live field (07-text-engine.md#editing).
 */
object SourceOffsets {
    /**
     * Offset into the source of [block] of the caret before character [plainOffset] of its visible text; the end of
     * the source for an offset at or past the end of the text and for blocks that show no text of their own (math,
     * tables, rules). The visible text is matched against the source from the end of the block marker on: delimiters,
     * link targets and entities that the text leaves out are skipped.
     */
    fun fromPlain(
        block: MarkdownBlock,
        plainOffset: Int,
    ): Int {
        val plain = visibleText(block.content)
        val offset = plainOffset.coerceAtLeast(0)
        if (plain == null || offset >= plain.length) return block.source.length
        val found = locate(block, plain, offset)
        return if (found < 0) fallback(block, offset) else found
    }

    /**
     * Offset into the table [source] of the caret before character [plainOffset] of a cell's visible text [plain],
     * the cell's Markdown being `source[start, end)`; the end of the cell at or past the end of its text.
     */
    fun fromCellPlain(
        source: String,
        start: Int,
        end: Int,
        plain: String,
        plainOffset: Int,
    ): Int {
        if (plainOffset >= plain.length) return end
        var at = start
        for (i in 0..plainOffset.coerceAtLeast(0)) {
            val found = find(source, at, plain[i])
            if (found < 0 || found >= end) return (start + plainOffset).coerceAtMost(end)
            at = if (i == plainOffset) found else found + 1
        }
        return at
    }

    // Walks the source in step with the visible text; -1 when the text is not a subsequence of the source.
    private fun locate(
        block: MarkdownBlock,
        plain: String,
        offset: Int,
    ): Int {
        var at = textStart(block)
        for (i in 0 until offset) {
            at = find(block.source, at, plain[i]) + 1
            if (at == 0) return -1
        }
        return find(block.source, at, plain[offset])
    }

    /** The text a block lays out for its own lines (without a callout title); null for blocks drawn by other means. */
    fun visibleText(content: BlockContent): String? =
        when (content) {
            is BlockContent.Paragraph -> content.text.plain
            is BlockContent.Heading -> content.text.plain
            is BlockContent.ListItem -> content.text.plain
            is BlockContent.Quote -> content.text.plain
            is BlockContent.Code -> content.code
            is BlockContent.Math, is BlockContent.Table, BlockContent.Rule, BlockContent.Empty -> null
        }

    // Where the visible text starts in the source: after the marker; after the fence line of code; after the callout line.
    private fun textStart(block: MarkdownBlock): Int {
        val content = block.content
        val afterFirstLine = block.source.indexOf('\n').let { if (it < 0) block.source.length else it + 1 }
        return when {
            content is BlockContent.Code -> afterFirstLine
            content is BlockContent.Quote && content.callout != null -> afterFirstLine
            else -> BlockMarkers.length(block)
        }
    }

    // Index of the first character at or after [from] that shows as [shown]; line breaks and spaces stand for each other.
    private fun find(
        source: String,
        from: Int,
        shown: Char,
    ): Int {
        for (i in from until source.length) {
            val c = source[i]
            if (c == shown || (shown.isWhitespace() && c.isWhitespace())) return i
        }
        return -1
    }

    private fun fallback(
        block: MarkdownBlock,
        plainOffset: Int,
    ): Int = (textStart(block) + plainOffset).coerceIn(0, block.source.length)
}
