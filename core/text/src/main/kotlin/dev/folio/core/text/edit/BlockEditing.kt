package dev.folio.core.text.edit

import dev.folio.core.text.edit.BlockSplice.Range
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument

/**
 * Structural editing of a flow's blocks (07-text-engine.md#editing), pure functions from a [MarkdownDocument] and a
 * selection to a new document and selection. Plain typing inside a block is not here: the live field edits the source
 * of the focused block, and the session turns any result into one `EditFlow` by diffing the two flow texts.
 *
 * The block structure of a result is what parsing its text gives (separators are widened to a blank line where a
 * single line break would merge two blocks), so undo, which re-parses, lands on the same blocks.
 */
@Suppress("TooManyFunctions") // one entry point per editing action; the logic sits in Enter, Backspace and BlockSplice
object BlockEditing {
    /** [doc], or a document of one empty paragraph when it has no block (the text `""`). */
    fun ensureBlock(doc: MarkdownDocument): MarkdownDocument = if (doc.blocks.isEmpty()) MarkdownDocument.withEmptyBlock() else doc

    /** Length of the marker at the start of block [blockId]'s source (`- `, `> `, `## `); a caret at or before it is "at block start". */
    fun markerLength(
        doc: MarkdownDocument,
        blockId: Long,
    ): Int = BlockMarkers.length(doc.blocks[doc.indexOf(blockId)])

    /** Replaces the selection by [text] (typed or pasted Markdown, `\n` allowed); the caret ends after the text. */
    fun insert(
        doc: MarkdownDocument,
        selection: TextSelection,
        text: String,
    ): EditResult {
        val range = Range.of(doc, selection)
        val head = doc.blocks[range.first].source.substring(0, range.startOffset)
        val tail = doc.blocks[range.last].source.substring(range.endOffset)
        return BlockSplice.replace(doc, range, head + text + tail, range.startOffset + text.length)
    }

    /**
     * Sets the source of block [blockId] to [source] (the live field's text after a keystroke) with the caret at
     * [caretOffset]. Text with a blank line or several list items becomes several blocks, the first keeping the id.
     */
    fun setSource(
        doc: MarkdownDocument,
        blockId: Long,
        source: String,
        caretOffset: Int,
    ): EditResult {
        val index = doc.indexOf(blockId)
        require(index >= 0) { "No block with id $blockId" }
        return BlockSplice.replace(doc, Range.block(index), source, caretOffset.coerceIn(0, source.length))
    }

    /** Removes the selected text; blocks it spans become one. A collapsed selection changes nothing. */
    fun delete(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): EditResult = if (selection.isCollapsed) EditResult(doc, selection) else insert(doc, selection, "")

    /** The selected text as Markdown, byte for byte as in the flow (block separators between blocks included). */
    fun copy(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): String {
        val range = Range.of(doc, selection)
        val first = doc.blocks[range.first]
        if (range.first == range.last) return first.source.substring(range.startOffset, range.endOffset)
        return buildString {
            append(first.source, range.startOffset, first.source.length).append(first.gap)
            for (i in range.first + 1 until range.last) append(doc.blocks[i].source).append(doc.blocks[i].gap)
            append(doc.blocks[range.last].source, 0, range.endOffset)
        }
    }

    /** Text from the clipboard as the flow takes it: line terminators `\n`, no NUL or form feed. */
    fun normalizePaste(text: String): String = text.replace("\r\n", "\n").replace('\r', '\n').filter { it != '\u0000' && it != '\u000C' }

    /** Enter: splits the block at the caret (see the cases in 07-text-engine.md#editing); a selection is deleted first. */
    fun enter(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): EditResult {
        val base = delete(doc, selection)
        return Enter.apply(base.document, base.selection.focus)
    }

    /** Shift+Enter: a line break inside the block that keeps its quote or list-item continuation indent. */
    fun softBreak(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): EditResult {
        val base = delete(doc, selection)
        val caret = base.selection.focus
        val block = base.document.blocks[base.document.indexOf(caret.blockId)]
        // A break with nothing after it would end the block (a blank line), so at the end it is an Enter.
        val atEnd = block.source.substring(caret.offset.coerceIn(0, block.source.length)).isBlank()
        // Two trailing spaces make the break hard, or the lines would show joined.
        val lineBreak =
            when (block.content) {
                is BlockContent.Quote -> "  \n> "
                is BlockContent.ListItem -> if (atEnd) null else "  \n" + " ".repeat(BlockMarkers.listMarker(block.source)?.length ?: 0)
                is BlockContent.Paragraph -> if (atEnd) null else "  \n"
                is BlockContent.Code, is BlockContent.Math -> "\n"
                else -> null
            } ?: return Enter.apply(base.document, caret)
        return insert(base.document, TextSelection.at(caret), lineBreak)
    }

    /**
     * Backspace at the start of a block or inside its marker: strips the marker (a list item outdents first), else
     * merges the block into the previous one. Null when there is nothing structural to do (the caret is inside the
     * text, or at the very start of the flow); a collapsed selection only.
     */
    fun backspace(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): EditResult? {
        if (!selection.isCollapsed) return delete(doc, selection)
        return Backspace.apply(doc, selection.focus)
    }

    /** Delete at the end of a block: joins the next block to this one; null at the end of the flow or inside the text. */
    fun deleteForward(
        doc: MarkdownDocument,
        selection: TextSelection,
    ): EditResult? {
        if (!selection.isCollapsed) return delete(doc, selection)
        return Backspace.forward(doc, selection.focus)
    }

    /** Tab / Shift+Tab on a list item: one level deeper (at most one below the item above) or shallower. Null if it cannot. */
    fun indent(
        doc: MarkdownDocument,
        caret: Caret,
        outdent: Boolean,
    ): EditResult? {
        val index = doc.indexOf(caret.blockId)
        val block = doc.blocks[index]
        if (block.content !is BlockContent.ListItem) return null
        val marker = BlockMarkers.listMarker(block.source) ?: return null
        val level = marker.indent / BlockMarkers.INDENT_STEP
        val above = (doc.blocks.getOrNull(index - 1)?.content as? BlockContent.ListItem)?.level
        val target = if (outdent) level - 1 else level + 1
        if (!canMoveTo(target, above, outdent)) return null
        val delta = (target - level) * BlockMarkers.INDENT_STEP
        val source = if (delta > 0) " ".repeat(delta) + block.source else block.source.substring(-delta)
        return BlockSplice.replaceSourceAt(doc, block.id, source, (caret.offset + delta).coerceIn(0, source.length))
    }

    /** Whether a list item can sit at level [target]: inside the levels, and when going deeper at most one below the item [above]. */
    private fun canMoveTo(
        target: Int,
        above: Int?,
        outdent: Boolean,
    ): Boolean = target in 0..BlockMarkers.MAX_LEVEL && (outdent || (above != null && target <= above + 1))

    /**
     * The Markdown shortcut that fires once the caret sits right after a typed trigger at the start of a block: `[] `
     * or `[ ] ` becomes the task item `- [ ] `. Null when [caret] is not after a trigger.
     */
    fun shortcut(
        doc: MarkdownDocument,
        caret: Caret,
    ): EditResult? {
        val block = doc.blocks[doc.indexOf(caret.blockId)]
        val typed = block.source.substring(0, caret.offset.coerceIn(0, block.source.length))
        val match = TASK_TRIGGER.matchEntire(typed) ?: return null
        val prefix = match.groupValues[1] + "- [ ] "
        return BlockSplice.replaceSourceAt(doc, block.id, prefix + block.source.substring(typed.length), prefix.length)
    }

    /** The caret one character left of [caret], across a block boundary (to the end of the previous block); null at the start. */
    fun left(
        doc: MarkdownDocument,
        caret: Caret,
    ): Caret? {
        if (caret.offset > 0) return Caret(caret.blockId, caret.offset - 1)
        val previous = doc.blocks.getOrNull(doc.indexOf(caret.blockId) - 1) ?: return null
        return Caret(previous.id, previous.source.length)
    }

    /** The caret one character right of [caret], across a block boundary (to the start of the next block); null at the end. */
    fun right(
        doc: MarkdownDocument,
        caret: Caret,
    ): Caret? {
        val index = doc.indexOf(caret.blockId)
        if (caret.offset < doc.blocks[index].source.length) return Caret(caret.blockId, caret.offset + 1)
        val next = doc.blocks.getOrNull(index + 1) ?: return null
        return Caret(next.id, 0)
    }

    private val TASK_TRIGGER = Regex("^( *)(?:- )?\\[ ?] $")
}
