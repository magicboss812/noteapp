package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument

/** Edits behind taps and the `[[` picker (07-text-engine.md#block-styles-as-built): task toggles and note links. */
object LinkEdits {
    /** Ticks or unticks the task item [blockId] (`[ ]` <-> `[x]` in its source); null when the block is not a task item. */
    fun toggleTask(
        doc: MarkdownDocument,
        blockId: Long,
    ): EditResult? {
        val block = doc.blocks.getOrNull(doc.indexOf(blockId)) ?: return null
        val checked = (block.content as? BlockContent.ListItem)?.checked ?: return null
        val box = TASK_BOX.find(block.source)?.groups?.get(1) ?: return null
        val newSource = block.source.replaceRange(box.range, if (checked) " " else "x")
        return BlockSplice.replaceSourceAt(doc, block.id, newSource, newSource.length)
    }

    /**
     * The `[[` picker's choice (09-storage-library.md#links-as-built): source [start] until the caret of block
     * [blockId] (the typed `[[query`, and a `]]` right after the caret) becomes `[title](folio://doc/<docId>)`, the
     * caret after it. Null outside text blocks or for offsets outside the source.
     */
    fun insertNoteLink(
        doc: MarkdownDocument,
        blockId: Long,
        start: Int,
        caret: Int,
        title: String,
        docId: String,
    ): EditResult? {
        val block = doc.blocks.getOrNull(doc.indexOf(blockId)) ?: return null
        val source = block.source
        val inRange = start in 0..caret && caret <= source.length
        if (!inRange || !hasInlineText(block.content)) return null
        val end = if (source.startsWith("]]", caret)) caret + 2 else caret
        val link = "[" + escapeLinkText(title.ifBlank { UNTITLED }) + "](" + NOTE_SCHEME + docId + ")"
        return BlockSplice.replaceSourceAt(doc, block.id, source.substring(0, start) + link + source.substring(end), start + link.length)
    }

    /** Where a `[[` note link query starts before [caret] in [source] (the first `[`), null when the caret is not in one. */
    fun noteLinkQueryStart(
        source: String,
        caret: Int,
    ): Int? {
        if (caret < 2 || caret > source.length) return null
        val open = source.lastIndexOf("[[", caret - 2)
        val escaped = open > 0 && source[open - 1] == '\\'
        if (open < 0 || escaped) return null
        val query = source.substring(open + 2, caret)
        return open.takeIf { query.length <= MAX_QUERY && query.none { it in QUERY_STOPS } }
    }

    private fun hasInlineText(content: BlockContent): Boolean =
        content is BlockContent.Paragraph ||
            content is BlockContent.Heading ||
            content is BlockContent.ListItem ||
            content is BlockContent.Quote ||
            content == BlockContent.Empty

    private fun escapeLinkText(title: String): String =
        buildString {
            for (c in title) {
                if (c in ESCAPED) append('\\')
                append(if (c == '\n') ' ' else c)
            }
        }

    private const val NOTE_SCHEME = "folio://doc/"
    private const val UNTITLED = "Untitled"
    private const val MAX_QUERY = 80
    private const val ESCAPED = "[]\\*_`\$=~"
    private const val QUERY_STOPS = "[]\n"
    private val TASK_BOX = Regex("""^[ \t>]*(?:[-*+]|\d{1,9}[.)])[ \t]+\[([ xX])]""")
}
