package dev.folio.core.text.markdown

/**
 * The Markdown of one flow as blocks (07-text-engine.md#blocks). Immutable; edits return a new document that shares
 * every untouched block, so layout caches keyed by block id and source stay valid.
 */
class MarkdownDocument private constructor(
    /** Blocks in reading order. */
    val blocks: List<MarkdownBlock>,
    private val parser: BlockParser,
    private val nextId: Long,
) {
    /** The flow text, byte for byte what was parsed or edited in. */
    fun toMarkdown(): String =
        buildString {
            for (block in blocks) {
                append(block.source)
                append(block.gap)
            }
        }

    /** Plain text of all blocks for the search index, one line per block. */
    fun plainText(): String =
        blocks
            .asSequence()
            .map { plainTextOf(it.content) }
            .filter { it.isNotEmpty() }
            .joinToString("\n")

    /** Index of the block with [id], or -1. */
    fun indexOf(id: Long): Int = blocks.indexOfFirst { it.id == id }

    /**
     * Replaces the source of block [id] and re-parses only that block. If the new text contains a blank line or starts
     * several blocks (a pasted list), the first part keeps [id] and the rest become new blocks right after it.
     * The block keeps its separator unless [newSource] ends in line terminators of its own.
     */
    fun replaceBlock(
        id: Long,
        newSource: String,
    ): MarkdownDocument {
        val index = indexOf(id)
        require(index >= 0) { "No block with id $id" }
        val old = blocks[index]
        if (newSource == old.source) return this
        // A cleared block stays one empty paragraph instead of turning its separator into extra blocks.
        val pieces =
            if (newSource.isBlank() && '\n' !in newSource) {
                // An empty paragraph carries a single line terminator; its blank separator line would be one more block.
                listOf(RawBlock(newSource, old.gap.substring(0, old.gap.indexOf('\n') + 1)))
            } else {
                BlockSplitter.split(newSource + old.gap)
            }
        var next = nextId
        val replaced =
            pieces.mapIndexed { i, piece ->
                val blockId = if (i == 0) id else next++
                MarkdownBlock(blockId, piece.source, piece.gap, parser.parse(piece.source))
            }
        val list = ArrayList<MarkdownBlock>(blocks.size + replaced.size)
        list.addAll(blocks.subList(0, index))
        list.addAll(replaced)
        list.addAll(blocks.subList(index + 1, blocks.size))
        return MarkdownDocument(list, parser, next)
    }

    /** Number of block parses done through this document's parser (shared by documents derived from it). */
    val parsedBlockCount: Int get() = parser.parseCount

    companion object {
        /** Splits and parses [markdown]. Always lossless: `parse(x).toMarkdown() == x`. */
        fun parse(markdown: String): MarkdownDocument {
            val parser = BlockParser()
            var id = 1L
            val blocks =
                BlockSplitter.split(markdown).map {
                    MarkdownBlock(id++, it.source, it.gap, parser.parse(it.source))
                }
            return MarkdownDocument(blocks, parser, id)
        }

        private fun plainTextOf(content: BlockContent): String =
            when (content) {
                BlockContent.Empty, BlockContent.Rule -> {
                    ""
                }

                is BlockContent.Paragraph -> {
                    content.text.plain
                }

                is BlockContent.Heading -> {
                    content.text.plain
                }

                is BlockContent.ListItem -> {
                    content.text.plain
                }

                is BlockContent.Quote -> {
                    content.text.plain
                }

                is BlockContent.Code -> {
                    content.code
                }

                is BlockContent.Math -> {
                    content.latex
                }

                is BlockContent.Table -> {
                    (listOf(content.header) + content.rows).joinToString("\n") { row -> row.joinToString(" ") { it.plain } }
                }
            }
    }
}
