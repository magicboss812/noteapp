package dev.folio.core.text.edit

import dev.folio.core.text.markdown.MarkdownDocument

/** A text position: [offset] characters into the source of block [blockId] (ids are those of one [MarkdownDocument]). */
data class Caret(
    val blockId: Long,
    val offset: Int,
)

/** A selection from [anchor] to [focus]; they may lie in different blocks and in either order. */
data class TextSelection(
    val anchor: Caret,
    val focus: Caret,
) {
    val isCollapsed: Boolean get() = anchor == focus

    companion object {
        fun at(caret: Caret) = TextSelection(caret, caret)
    }
}

/** The document after an edit and where the selection goes (block ids are those of [document]). */
class EditResult(
    val document: MarkdownDocument,
    val selection: TextSelection,
)

/** Conversions between block carets and offsets into the flow text (`document.toMarkdown()`). */
object TextPositions {
    /** Offset of the start of block [index] in the flow text. */
    fun blockStart(
        doc: MarkdownDocument,
        index: Int,
    ): Int {
        var at = 0
        for (i in 0 until index) at += doc.blocks[i].source.length + doc.blocks[i].gap.length
        return at
    }

    /** Offset of [caret] in the flow text. */
    fun absolute(
        doc: MarkdownDocument,
        caret: Caret,
    ): Int {
        val index = doc.indexOf(caret.blockId)
        require(index >= 0) { "No block with id ${caret.blockId}" }
        return blockStart(doc, index) + caret.offset.coerceIn(0, doc.blocks[index].source.length)
    }

    /**
     * The caret for flow-text offset [offset]. An offset inside a block or at its end stays in that block; an offset
     * in a separator maps to the start of the next block, and one past the end to the end of the last block.
     */
    fun caretAt(
        doc: MarkdownDocument,
        offset: Int,
    ): Caret {
        var start = 0
        for (block in doc.blocks) {
            val end = start + block.source.length
            if (offset <= end) return Caret(block.id, (offset - start).coerceAtLeast(0))
            start = end + block.gap.length
        }
        val last = doc.blocks.last()
        return Caret(last.id, last.source.length)
    }
}
