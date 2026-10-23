package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument

/** What Enter does in each kind of block (07-text-engine.md#editing). */
internal object Enter {
    fun apply(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult {
        val index = doc.indexOf(caret.blockId)
        val block = doc.blocks[index]
        val offset = caret.offset.coerceIn(0, block.source.length)
        return when (val content = block.content) {
            is BlockContent.Code -> code(doc, index, offset)
            is BlockContent.Math -> math(doc, index, offset)
            is BlockContent.Table -> table(doc, index, offset, content.header.size)
            is BlockContent.ListItem -> ListEnter.apply(doc, index, offset) ?: split(doc, index, offset, 0)
            is BlockContent.Quote -> quote(doc, index, offset)
            is BlockContent.Paragraph -> paragraph(doc, index, offset)
            else -> split(doc, index, offset, BlockMarkers.length(block))
        }
    }

    private fun rewrite(
        doc: MarkdownDocument,
        index: Int,
        newSource: String,
        caretOffset: Int,
        gap: String? = null,
    ): EditResult = BlockSplice.replace(doc, Range.block(index), newSource, caretOffset, gap)

    /**
     * Splits block [index] at [offset]: the text after it starts a new block led by [continuation]. A caret inside the
     * block's [markerLength] (or at its start) puts an empty paragraph above instead; an empty block gets an empty
     * paragraph below.
     */
    private fun split(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
        markerLength: Int,
        continuation: String = "",
    ): EditResult {
        val source = doc.blocks[index].source
        return when {
            source.isBlank() -> {
                appendEmpty(doc, index, source + "\n", source.length + 1)
            }

            offset <= markerLength -> {
                rewrite(doc, index, "\n$source", offset + 1)
            }

            else -> {
                val head = source.substring(0, offset)
                val tail = source.substring(offset)
                if (tail.isEmpty() && continuation.isEmpty()) {
                    appendEmpty(doc, index, "$head\n\n", head.length + 2)
                } else {
                    rewrite(doc, index, "$head\n\n$continuation$tail", head.length + 2 + continuation.length)
                }
            }
        }
    }

    /** Replaces block [index] by [newSource], which ends with a blank paragraph that gets the caret. */
    private fun appendEmpty(
        doc: MarkdownDocument,
        index: Int,
        newSource: String,
        caretOffset: Int,
    ): EditResult = rewrite(doc, index, newSource, caretOffset, gap = "\n")

    private fun paragraph(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
    ): EditResult {
        val source = doc.blocks[index].source
        val row = source.trim()
        val isPipeRow = '\n' !in source && row.length > 1 && row.startsWith("|") && row.endsWith("|")
        if (!isPipeRow || offset != source.length) return split(doc, index, offset, 0)
        // `| a | b |` + Enter: header row, delimiter row and one empty row, the caret in its first cell.
        val columns = BlockMarkers.cells(source).size
        val body = BlockMarkers.table(columns).substringAfter('\n')
        val text = "$source\n$body"
        val lastRow = BlockMarkers.emptyRow(columns)
        return rewrite(doc, index, text, text.length - lastRow.length + CELL_CARET)
    }

    private fun table(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
        columns: Int,
    ): EditResult {
        val source = doc.blocks[index].source
        val lines = source.split('\n')
        val line = source.take(offset).count { it == '\n' }
        val lastLine = lines.size - 1
        val emptyLastRow = line == lastLine && lines.size > MIN_TABLE_LINES && BlockMarkers.cells(lines[lastLine]).all { it.isEmpty() }
        if (emptyLastRow) {
            // Enter on the empty row ends the table.
            val kept = lines.dropLast(1).joinToString("\n")
            return appendEmpty(doc, index, "$kept\n\n", kept.length + 2)
        }
        // Enter anywhere else adds a row below the current one (below the delimiter when in the header).
        val after = maxOf(line, 1)
        val insertAt = lines.take(after + 1).sumOf { it.length + 1 } - 1
        val row = BlockMarkers.emptyRow(columns)
        return rewrite(doc, index, source.substring(0, insertAt) + "\n" + row + source.substring(insertAt), insertAt + 1 + CELL_CARET)
    }

    private fun code(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
    ): EditResult {
        val source = doc.blocks[index].source
        val opener = BlockMarkers.fenceOpener(source)
        val atEnd = offset == source.length
        return when {
            // ``` + Enter opens the block: the closing fence below an empty line.
            opener != null && atEnd && '\n' !in source -> {
                rewrite(doc, index, "$source\n\n$opener", source.length + 1)
            }

            // Enter after the closing fence leaves the block.
            opener != null && atEnd && BlockMarkers.isClosedFence(source, opener) -> {
                appendEmpty(
                    doc,
                    index,
                    "$source\n\n",
                    source.length + 2,
                )
            }

            else -> {
                newline(doc, index, offset)
            }
        }
    }

    private fun math(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
    ): EditResult {
        val source = doc.blocks[index].source
        val atEnd = offset == source.length
        return when {
            atEnd && source.trim() == "$$" -> rewrite(doc, index, "$source\n\n$$", source.length + 1)
            atEnd && BlockMarkers.isClosedMath(source) -> appendEmpty(doc, index, "$source\n\n", source.length + 2)
            else -> newline(doc, index, offset)
        }
    }

    private fun newline(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
    ): EditResult {
        val source = doc.blocks[index].source
        return rewrite(doc, index, source.substring(0, offset) + "\n" + source.substring(offset), offset + 1)
    }

    private fun quote(
        doc: MarkdownDocument,
        index: Int,
        offset: Int,
    ): EditResult {
        val source = doc.blocks[index].source
        val empty = source.lines().all { line -> line.all { it == '>' || it == ' ' } }
        // Enter in an empty quote leaves it.
        if (empty) return rewrite(doc, index, "", 0)
        return split(doc, index, offset, BlockMarkers.length(doc.blocks[index]), continuation = "> ")
    }

    /** The caret in a fresh table row: after the first cell's leading space. */
    private const val CELL_CARET = 2
    private const val MIN_TABLE_LINES = 2
}
