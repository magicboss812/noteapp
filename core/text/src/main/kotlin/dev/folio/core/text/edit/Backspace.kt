package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument

/** Backspace and Delete at block boundaries (07-text-engine.md#editing). */
internal object Backspace {
    private val quoteMarker = Regex("^ {0,3}> ?")

    fun apply(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? {
        val index = doc.indexOf(caret.blockId)
        val block = doc.blocks[index]
        val offset = caret.offset.coerceIn(0, block.source.length)
        val marker = BlockMarkers.length(block)
        return when {
            marker > 0 && offset <= marker -> stripMarker(doc, index, offset, marker)
            offset > 0 || index == 0 -> null
            else -> mergeIntoPrevious(doc, index)
        }
    }

    fun forward(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? {
        val index = doc.indexOf(caret.blockId)
        val block = doc.blocks[index]
        if (caret.offset < block.source.length || index + 1 >= doc.blocks.size) return null
        return mergeNext(doc, index)
    }

    /** A list item moves out one level, any other marker (`- `, `> `, `## `) is removed; the text stays. */
    private fun stripMarker(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
        marker: Int,
    ): EditResult {
        val block = doc.blocks[index]
        val listMarker = BlockMarkers.listMarker(block.source)
        val (newSource, removed) =
            when {
                block.content is BlockContent.ListItem && listMarker != null && listMarker.indent > 0 -> {
                    val step = minOf(listMarker.indent, BlockMarkers.INDENT_STEP)
                    block.source.substring(step) to step
                }

                block.content is BlockContent.Quote -> {
                    // Every line of the quote loses its `> `, or the later lines would stay a quote of their own.
                    block.source.lines().joinToString("\n") { it.replaceFirst(quoteMarker, "") } to marker
                }

                else -> {
                    block.source.substring(marker) to marker
                }
            }
        return BlockSplice.replace(doc, Range.block(index), newSource, maxOf(0, offset - removed))
    }

    /** Backspace at the start of block [index]: joins its text to the previous block. */
    private fun mergeIntoPrevious(
        doc: MarkdownDocument,
        index: Int,
    ): EditResult {
        val previous = doc.blocks[index - 1]
        val block = doc.blocks[index]
        val range = Range(index - 1, 0, index, 0)
        return when {
            // An empty paragraph just goes away.
            block.source.isBlank() -> BlockSplice.replace(doc, range, previous.source, previous.source.length, gap = previous.gap)

            // So does a rule: the caret ends up at the start of this block.
            previous.content == BlockContent.Rule -> BlockSplice.replace(doc, range, block.source, 0)

            // Text is never merged into code, math or a table (nor those into text): the caret moves up instead.
            !BlockMarkers.isText(
                previous,
            ) || !BlockMarkers.isText(block) -> EditResult(doc, TextSelection.at(Caret(previous.id, previous.source.length)))

            else -> BlockSplice.replace(doc, range, previous.source + block.source, previous.source.length)
        }
    }

    /** Delete at the end of block [index]: joins the next block's text to it. */
    private fun mergeNext(
        doc: MarkdownDocument,
        index: Int,
    ): EditResult {
        val block = doc.blocks[index]
        val next = doc.blocks[index + 1]
        val range = Range(index, 0, index + 1, 0)
        return when {
            block.source.isBlank() -> {
                BlockSplice.replace(doc, range, next.source, 0, gap = next.gap)
            }

            next.source.isBlank() -> {
                BlockSplice.replace(doc, range, block.source, block.source.length, gap = block.gap)
            }

            next.content == BlockContent.Rule && BlockMarkers.isText(block) -> {
                BlockSplice.replace(doc, range, block.source, block.source.length, gap = next.gap)
            }

            !BlockMarkers.isText(block) || !BlockMarkers.isText(next) -> {
                EditResult(doc, TextSelection.at(Caret(next.id, 0)))
            }

            else -> {
                val text = next.source.substring(BlockMarkers.length(next))
                BlockSplice.replace(doc, range, block.source + text, block.source.length, gap = next.gap)
            }
        }
    }
}
