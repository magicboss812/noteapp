package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.SpanKind

/**
 * A selection over several blocks of a flow (07-text-engine.md#selection-as-built, P06-T14): Ctrl+A, moving one end
 * across block boundaries, and formatting each selected block. Copy, cut, paste and typing over it are
 * [BlockEditing.copy], [BlockEditing.delete] and [BlockEditing.insert].
 */
object SelectionEditing {
    /** True when [selection] lies in more than one block. */
    fun spansBlocks(selection: TextSelection): Boolean = selection.anchor.blockId != selection.focus.blockId

    /**
     * Ctrl+A: the whole block of [selection]'s focus, or the whole flow when the selection already covers that block
     * (or more). The focus goes to the end.
     */
    fun selectAll(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): TextSelection {
        val range = Range.of(doc, selection)
        val block = doc.blocks[doc.indexOf(selection.focus.blockId)]
        val wholeBlock = range.startOffset == 0 && range.endOffset == block.source.length
        if (range.first == range.last && !wholeBlock) {
            return TextSelection(Caret(block.id, 0), Caret(block.id, block.source.length))
        }
        val last = doc.blocks.last()
        return TextSelection(Caret(doc.blocks.first().id, 0), Caret(last.id, last.source.length))
    }

    /**
     * Where a selection end goes when it moves into block [index] from the block before ([forward]) or after: the near
     * edge of that block. Tables are passed over (the live field edits one cell at a time, so a selection end never
     * rests in one); null when only tables lie that way.
     */
    fun entering(
        doc: MarkdownDocument,
        index: Int,
        forward: Boolean,
    ): Caret? {
        val step = if (forward) 1 else -1
        var at = index
        while (at in doc.blocks.indices && doc.blocks[at].content is BlockContent.Table) at += step
        val block = doc.blocks.getOrNull(at) ?: return null
        return Caret(block.id, if (forward) 0 else block.source.length)
    }

    /** [caret], or when it lies in a table the near edge of the next block beyond it in the direction [forward]. */
    fun outsideTables(
        doc: MarkdownDocument,
        caret: Caret,
        forward: Boolean,
    ): Caret? {
        val index = doc.indexOf(caret.blockId)
        if (index < 0) return null
        return if (doc.blocks[index].content is BlockContent.Table) entering(doc, index, forward) else caret
    }

    /**
     * A formatting bar inline action ([kind]) over a selection that spans blocks: each selected block that takes inline
     * markers (paragraphs, headings, list items, quotes) gets them around its part of the selection; when every such
     * part already sits inside a span of [kind] they are removed instead. A one-block selection is
     * [Formatting.toggleInline]. Null when no block takes it.
     */
    fun toggleInline(
        doc: MarkdownDocument,
        selection: TextSelection,
        kind: SpanKind,
    ): EditResult? {
        if (!spansBlocks(selection)) return Formatting.toggleInline(doc, selection, kind)
        val range = Range.of(doc, selection)
        val parts = (range.first..range.last).mapNotNull { partOf(doc, range, it) }
        if (parts.isEmpty()) return null
        val unwrap = parts.all { Formatting.isInside(doc, it, kind) }
        var current = doc
        var start = Caret(doc.blocks[range.first].id, range.startOffset)
        var end = Caret(doc.blocks[range.last].id, range.endOffset)
        for (part in parts.filter { Formatting.isInside(doc, it, kind) == unwrap }) {
            // Inline markers keep a block's id and touch no other block, so the part addresses the same text here.
            val result = Formatting.toggleInline(current, part, kind) ?: continue
            current = result.document
            val edited = Range.of(current, result.selection)
            if (part.anchor.blockId == start.blockId) start = Caret(start.blockId, edited.startOffset)
            if (part.anchor.blockId == end.blockId) end = Caret(end.blockId, edited.endOffset)
        }
        if (current === doc) return null
        val forward = selection.anchor.blockId == start.blockId
        return EditResult(current, if (forward) TextSelection(start, end) else TextSelection(end, start))
    }

    // The selected part of block [index] (after its line markers), or null when it is empty or takes no inline markers.
    private fun partOf(
        doc: MarkdownDocument,
        range: Range,
        index: Int,
    ): TextSelection? {
        val block = doc.blocks[index]
        val inline =
            when (block.content) {
                is BlockContent.Paragraph, is BlockContent.Heading, is BlockContent.ListItem, is BlockContent.Quote -> true
                else -> false
            }
        val markers = BlockMarkers.length(block)
        val start = if (index == range.first) maxOf(range.startOffset, markers) else markers
        val end = if (index == range.last) range.endOffset else block.source.length
        if (!inline || start >= end) return null
        return TextSelection(Caret(block.id, start), Caret(block.id, end))
    }
}
