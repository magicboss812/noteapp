package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.BlockSplitter
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.MarkdownDocument

/**
 * Replaces blocks by new source text and keeps the derived block structure equal to what parsing the flow text gives
 * (undo and a reopen re-parse), which is the plumbing under every edit in [BlockEditing], [Enter], [Backspace] and [Formatting].
 */
internal object BlockSplice {
    /** A selection range in block terms, ordered. */
    class Range(
        val first: Int,
        val startOffset: Int,
        val last: Int,
        val endOffset: Int,
    ) {
        companion object {
            fun of(
                doc: MarkdownDocument,
                selection: TextSelection,
            ): Range {
                val a = doc.indexOf(selection.anchor.blockId)
                val f = doc.indexOf(selection.focus.blockId)
                require(a >= 0 && f >= 0) { "Selection outside the document" }
                val ao = selection.anchor.offset.coerceIn(0, doc.blocks[a].source.length)
                val fo = selection.focus.offset.coerceIn(0, doc.blocks[f].source.length)
                return if (a < f || (a == f && ao <= fo)) Range(a, ao, f, fo) else Range(f, fo, a, ao)
            }

            /** The range of block [index] alone, no offsets. */
            fun block(index: Int): Range = Range(index, 0, index, 0)
        }
    }

    /**
     * Replaces the blocks of [range] by [newSource] and puts the caret [caretOffset] characters into the new text
     * (counted from the start of the first block). Separators next to the change are widened where needed.
     */
    fun replace(
        doc: MarkdownDocument,
        range: Range,
        newSource: String,
        caretOffset: Int,
        gap: String? = null,
    ): EditResult {
        val first = doc.blocks[range.first]
        val fixed = replaced(doc, range.first, range.last, newSource, gap)
        val start = TextPositions.blockStart(fixed, fixed.indexOf(first.id))
        return EditResult(fixed, TextSelection.at(TextPositions.caretAt(fixed, start + caretOffset)))
    }

    /**
     * The caret [offset] characters into the text that started as block [blockId], which may since have split into
     * several blocks (the first one keeps the id).
     */
    fun caretIn(
        doc: MarkdownDocument,
        blockId: Long,
        offset: Int,
    ): Caret = TextPositions.caretAt(doc, TextPositions.blockStart(doc, doc.indexOf(blockId)) + offset)

    /** [doc] with the source of block [blockId] replaced, its neighbours kept apart where the new text needs it. */
    fun replaceSource(
        doc: MarkdownDocument,
        blockId: Long,
        newSource: String,
    ): MarkdownDocument {
        val index = doc.indexOf(blockId)
        return replaced(doc, index, index, newSource, null)
    }

    /** [replaceSource] with the caret [caretOffset] characters into the new text of block [blockId]. */
    fun replaceSourceAt(
        doc: MarkdownDocument,
        blockId: Long,
        newSource: String,
        caretOffset: Int,
    ): EditResult {
        val result = replaceSource(doc, blockId, newSource)
        return EditResult(result, TextSelection.at(caretIn(result, blockId, caretOffset)))
    }

    private fun replaced(
        doc: MarkdownDocument,
        first: Int,
        last: Int,
        newSource: String,
        gap: String?,
    ): MarkdownDocument {
        val result = doc.replaceBlocks(doc.blocks[first].id, doc.blocks[last].id, newSource, gap)
        val newLast = result.blocks.size - (doc.blocks.size - last - 1) - 1
        return separated(endedByLine(result), first, newLast)
    }

    /** An empty paragraph at the end needs a line terminator of its own, or the text would not show it (except as the only block). */
    private fun endedByLine(doc: MarkdownDocument): MarkdownDocument {
        val last = doc.blocks.lastIndex
        val block = doc.blocks[last]
        return if (last > 0 && block.content == BlockContent.Empty && '\n' !in block.gap) doc.withGap(last, "\n") else doc
    }

    /**
     * [doc] with a blank line after each block from [from] - 1 to [to] whose single line break would make it merge
     * with the next block when the text is parsed again (a paragraph above a list that turned into text, a block above
     * an empty paragraph).
     */
    private fun separated(
        doc: MarkdownDocument,
        from: Int,
        to: Int,
    ): MarkdownDocument {
        var result = doc
        for (i in (from - 1).coerceAtLeast(0)..minOf(to, doc.blocks.size - 2)) {
            val a = result.blocks[i]
            val b = result.blocks[i + 1]
            if (a.gap == "\n" && a.content != BlockContent.Empty && !staysSeparate(a, b)) result = result.withGap(i, "\n\n")
        }
        return result
    }

    private fun staysSeparate(
        a: MarkdownBlock,
        b: MarkdownBlock,
    ): Boolean {
        // b's own separator matters: a blank line only counts as an empty paragraph when it is terminated.
        val pieces = BlockSplitter.split(a.source + "\n" + b.source + b.gap)
        return pieces.size == 2 && pieces[0].source == a.source && pieces[1].source == b.source
    }
}
