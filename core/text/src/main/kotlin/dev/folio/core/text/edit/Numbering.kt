package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.MarkdownDocument

/** Keeps ordered lists counting after an item was inserted. */
internal object Numbering {
    /**
     * [result] with the ordered items that follow block [index] (same level, deeper items skipped) renumbered to
     * continue from it. Only text after the caret changes, so the selection stays valid.
     */
    fun renumbered(
        result: EditResult,
        index: Int,
    ): EditResult {
        val base = result.document.blocks.getOrNull(index) ?: return result
        val level = levelOf(base) ?: return result
        val start = BlockMarkers.listMarker(base.source)?.takeIf { it.ordered }?.number ?: return result
        val doc = renumberFrom(result.document, index + 1, level, start)
        return if (doc === result.document) result else EditResult(doc, result.selection)
    }

    private fun renumberFrom(
        doc: MarkdownDocument,
        from: Int,
        level: Int,
        start: Int,
    ): MarkdownDocument {
        var current = doc
        var expected = start
        var i = from
        while (i < current.blocks.size && (levelOf(current.blocks[i]) ?: NOT_A_LIST) >= level) {
            val block = current.blocks[i]
            if (levelOf(block) == level) {
                val marker = BlockMarkers.listMarker(block.source)?.takeIf { it.ordered } ?: break
                expected++
                if (marker.number != expected) current = current.replaceBlock(block.id, withNumber(block.source, marker, expected))
            }
            i++
        }
        return current
    }

    private fun withNumber(
        source: String,
        marker: ListMarker,
        number: Int,
    ): String = source.substring(0, marker.indent) + number + marker.bullet.last() + source.substring(marker.indent + marker.bullet.length)

    private fun levelOf(block: MarkdownBlock): Int? = (block.content as? BlockContent.ListItem)?.level

    private const val NOT_A_LIST = -1
}
