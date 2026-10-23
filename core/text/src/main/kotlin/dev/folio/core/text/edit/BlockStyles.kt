package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.ListKind
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.MarkdownDocument

/** The block kinds the formatting bar switches between. */
enum class BlockStyle { PARAGRAPH, HEADING_1, HEADING_2, HEADING_3, BULLET, ORDERED, TASK, QUOTE }

/** Block style switching as source edits (07-text-engine.md#editing): headings, lists, quote. */
object BlockStyles {
    private val quoteMarker = Regex("^ {0,3}> ?")
    private val lineBreaks = Regex("\\s*\\n\\s*")

    /** The style of the block now, for the formatting bar to show. */
    fun of(block: MarkdownBlock): BlockStyle =
        when (val content = block.content) {
            is BlockContent.Heading -> {
                when (content.level) {
                    1 -> BlockStyle.HEADING_1
                    2 -> BlockStyle.HEADING_2
                    else -> BlockStyle.HEADING_3
                }
            }

            is BlockContent.ListItem -> {
                when (content.kind) {
                    ListKind.BULLET -> BlockStyle.BULLET
                    ListKind.ORDERED -> BlockStyle.ORDERED
                    ListKind.TASK -> BlockStyle.TASK
                }
            }

            is BlockContent.Quote -> {
                BlockStyle.QUOTE
            }

            else -> {
                BlockStyle.PARAGRAPH
            }
        }

    /**
     * Gives the block at [caret] the style [style], or a paragraph when it already has it. Code, math, tables and rules
     * are left alone (null). A list item keeps its indentation when it changes to another list type.
     */
    fun apply(
        doc: MarkdownDocument,
        caret: Caret,
        style: BlockStyle,
    ): EditResult? {
        val index = doc.indexOf(caret.blockId)
        val block = doc.blocks[index]
        if (!BlockMarkers.isText(block)) return null
        val target = if (of(block) == style) BlockStyle.PARAGRAPH else style
        val indent = BlockMarkers.listMarker(block.source)?.indent ?: 0
        val body = bodyOf(block)
        val lead = " ".repeat(if (target.isList() && block.content is BlockContent.ListItem) indent else 0)
        val prefix = lead + prefixOf(target)
        val newSource = withPrefix(body, target, prefix)
        // The caret keeps its place in the text: the same transformation of the text before it gives its new offset.
        val offset = withPrefix(body.take(bodyOffset(block, caret.offset)), target, prefix).length
        return BlockSplice.replace(doc, BlockSplice.Range.block(index), newSource, offset.coerceAtMost(newSource.length))
    }

    /** Offset in [bodyOf] of the character at source [offset]: the markers before it do not count. */
    private fun bodyOffset(
        block: MarkdownBlock,
        offset: Int,
    ): Int {
        if (block.content !is BlockContent.Quote) return (offset - BlockMarkers.length(block)).coerceAtLeast(0)
        var removed = 0
        var lineStart = 0
        for (line in block.source.split('\n')) {
            val marker = quoteMarker.find(line)?.value?.length ?: 0
            if (offset <= lineStart + line.length) return (offset - removed - minOf(marker, offset - lineStart)).coerceAtLeast(0)
            removed += marker
            lineStart += line.length + 1
        }
        return (offset - removed).coerceAtLeast(0)
    }

    private fun BlockStyle.isList() = this == BlockStyle.BULLET || this == BlockStyle.ORDERED || this == BlockStyle.TASK

    /** The block's text without its marker (a quote loses the `> ` of every line). */
    private fun bodyOf(block: MarkdownBlock): String =
        if (block.content is BlockContent.Quote) {
            block.source.lines().joinToString("\n") { it.replaceFirst(quoteMarker, "") }
        } else {
            block.source.substring(BlockMarkers.length(block))
        }

    private fun prefixOf(style: BlockStyle): String =
        when (style) {
            BlockStyle.PARAGRAPH -> ""
            BlockStyle.HEADING_1 -> "# "
            BlockStyle.HEADING_2 -> "## "
            BlockStyle.HEADING_3 -> "### "
            BlockStyle.BULLET -> "- "
            BlockStyle.ORDERED -> "1. "
            BlockStyle.TASK -> "- [ ] "
            BlockStyle.QUOTE -> "> "
        }

    private fun withPrefix(
        body: String,
        style: BlockStyle,
        prefix: String,
    ): String =
        when (style) {
            // A heading is one line.
            BlockStyle.HEADING_1, BlockStyle.HEADING_2, BlockStyle.HEADING_3 -> prefix + body.replace(lineBreaks, " ")

            BlockStyle.QUOTE -> body.lines().joinToString("\n") { prefix + it }

            else -> prefix + body
        }
}
