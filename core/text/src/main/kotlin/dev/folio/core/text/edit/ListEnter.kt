package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.MarkdownDocument

/** What Enter does in a list item (07-text-engine.md#editing). */
internal object ListEnter {
    /** Enter at [offset] in the list item at [index]; null when the block has no list marker after all. */
    fun apply(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
    ): EditResult? {
        val source = doc.blocks[index].source
        val marker = BlockMarkers.listMarker(source) ?: return null
        val empty = source.substring(marker.length).isBlank()
        return if (empty) leave(doc, index, offset, marker) else continueList(doc, index, offset, marker)
    }

    /** Enter in an empty item moves it out one level, or out of the list. */
    private fun leave(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
        marker: ListMarker,
    ): EditResult {
        if (marker.indent == 0) return rewrite(doc, index, "", 0)
        val step = minOf(marker.indent, BlockMarkers.INDENT_STEP)
        return rewrite(doc, index, doc.blocks[index].source.substring(step), maxOf(0, offset - step))
    }

    /** Enter in an item with text: a new item for the text after the caret, or an empty one above when the caret is in the marker. */
    private fun continueList(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
        marker: ListMarker,
    ): EditResult {
        val source = doc.blocks[index].source
        val result =
            if (offset < marker.length) {
                val empty = ListMarker.prefix(marker.indent, marker.bullet, marker.spaces, marker.box != null)
                rewrite(doc, index, empty + "\n" + source, empty.length + 1 + offset)
            } else {
                val next = marker.next()
                rewrite(doc, index, source.substring(0, offset) + "\n" + next + source.substring(offset), offset + 1 + next.length)
            }
        return if (marker.ordered) Numbering.renumbered(result, index) else result
    }

    private fun rewrite(
        doc: MarkdownDocument,
        index: Int,
        newSource: String,
        caretOffset: Int,
    ): EditResult = BlockSplice.replace(doc, Range.block(index), newSource, caretOffset)
}
