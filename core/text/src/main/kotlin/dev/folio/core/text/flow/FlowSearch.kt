package dev.folio.core.text.flow

import dev.folio.core.model.Document
import dev.folio.core.model.PageId
import dev.folio.core.text.layout.BlockLayout

/**
 * Finds where a library search hit sits in a note (09-storage-library.md#search, A-033): the page whose flow lines
 * show the first match. A match is any whitespace-separated query term found in a block's laid-out text, ignoring
 * case; text that overflows the last frame is not on any page.
 */
object FlowSearch {
    /** The page with the first match in [layout] (flow order), or null when nothing on a page matches. */
    fun firstPage(
        layout: FlowLayout,
        query: String,
    ): PageId? {
        val terms = terms(query)
        if (terms.isEmpty()) return null
        for (block in layout.markdown.blocks.indices) {
            val unit = layout.layoutOf(block)?.let { matchUnit(it, terms) } ?: continue
            pageOf(layout, block, unit)?.let { return it }
        }
        return null
    }

    /** Offset in U from the block top of the line showing the earliest term, or null when no term occurs. */
    private fun matchUnit(
        layout: BlockLayout,
        terms: List<String>,
    ): Int? {
        val text = layout.plainText
        val offset = terms.map { text.indexOf(it, ignoreCase = true) }.filter { it >= 0 }.minOrNull()
        return offset?.let { layout.lineForOffset(it) * layout.boxUnits }
    }

    /** The first page of [doc] (page order) holding a match in any of [layouts], or null for a miss. */
    fun firstPage(
        doc: Document,
        layouts: List<FlowLayout>,
        query: String,
    ): PageId? =
        layouts
            .mapNotNull { firstPage(it, query) }
            .minByOrNull { doc.indexOfPage(it).takeIf { index -> index >= 0 } ?: Int.MAX_VALUE }

    private fun terms(query: String): List<String> = query.split(WHITESPACE).filter { it.isNotEmpty() }

    private fun pageOf(
        layout: FlowLayout,
        block: Int,
        unit: Int,
    ): PageId? {
        for (frame in layout.slots.indices) {
            val hit = layout.slices(frame).any { it.blockIndex == block && unit >= it.fromUnit && unit < it.toUnit }
            if (hit) return layout.slots[frame].pageId
        }
        return null
    }

    private val WHITESPACE = Regex("\\s+")
}
