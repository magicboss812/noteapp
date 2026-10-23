package dev.folio.feature.editor.text

import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.BlockStyle
import dev.folio.core.text.edit.BlockStyles
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.EditResult
import dev.folio.core.text.edit.Formatting
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.SpanKind

/**
 * The document and selection after this edit of block [blockId] in [doc] (a move of the caret returns [doc] itself);
 * null when the block is gone (undo parsed the text again) or the key has nothing structural to do.
 */
internal fun FieldEdit.applyTo(
    doc: MarkdownDocument,
    blockId: Long,
): EditResult? {
    val index = doc.indexOf(blockId)
    if (index < 0) return null
    return when (this) {
        is FieldEdit.Typed -> typed(doc, blockId)
        is FieldEdit.Key -> key(doc, blockId, doc.blocks[index].source.length)
        is FieldEdit.Format -> format(doc, blockId, doc.blocks[index].source.length)
    }
}

private val INLINE_KINDS =
    mapOf(
        FormatAction.BOLD to SpanKind.BOLD,
        FormatAction.ITALIC to SpanKind.ITALIC,
        FormatAction.STRIKE to SpanKind.STRIKE,
        FormatAction.HIGHLIGHT to SpanKind.HIGHLIGHT,
        FormatAction.CODE to SpanKind.CODE,
        FormatAction.MATH to SpanKind.MATH,
    )

private val BLOCK_STYLES =
    mapOf(
        FormatAction.HEADING_1 to BlockStyle.HEADING_1,
        FormatAction.HEADING_2 to BlockStyle.HEADING_2,
        FormatAction.HEADING_3 to BlockStyle.HEADING_3,
        FormatAction.BULLET to BlockStyle.BULLET,
        FormatAction.ORDERED to BlockStyle.ORDERED,
        FormatAction.TASK to BlockStyle.TASK,
        FormatAction.QUOTE to BlockStyle.QUOTE,
    )

// Inline markers wrap the selection, block styles toggle at the caret, a link wraps the selection, a table goes after the block.
private fun FieldEdit.Format.format(
    doc: MarkdownDocument,
    blockId: Long,
    length: Int,
): EditResult? {
    val first = Caret(blockId, start.coerceIn(0, length))
    val selection = TextSelection(first, Caret(blockId, end.coerceIn(first.offset, length)))
    val inline = INLINE_KINDS[action]
    val style = BLOCK_STYLES[action]
    return when {
        inline != null -> Formatting.toggleInline(doc, selection, inline)
        style != null -> BlockStyles.apply(doc, first, style)
        action == FormatAction.LINK -> Formatting.link(doc, selection)
        action == FormatAction.TABLE -> Formatting.insertTable(doc, first)
        else -> null
    }
}

// The typed text replaces the block source; `[] ` at the start then becomes a task item in the same step.
private fun FieldEdit.Typed.typed(
    doc: MarkdownDocument,
    blockId: Long,
): EditResult {
    val typed = BlockEditing.setSource(doc, blockId, source, caret)
    return BlockEditing.shortcut(typed.document, typed.selection.focus) ?: typed
}

private fun FieldEdit.Key.key(
    doc: MarkdownDocument,
    blockId: Long,
    length: Int,
): EditResult? {
    // The field may be a keystroke ahead of the model: offsets past the source end are clamped.
    val first = Caret(blockId, start.coerceIn(0, length))
    val last = Caret(blockId, end.coerceIn(first.offset, length))
    val selection = TextSelection(first, last)
    return when (key) {
        FieldKey.ENTER -> BlockEditing.enter(doc, selection)
        FieldKey.SOFT_ENTER -> BlockEditing.softBreak(doc, selection)
        FieldKey.BACKSPACE -> BlockEditing.backspace(doc, selection)
        FieldKey.DELETE -> BlockEditing.deleteForward(doc, selection)
        FieldKey.LEFT -> BlockEditing.left(doc, first)?.let { EditResult(doc, TextSelection.at(it)) }
        FieldKey.RIGHT -> BlockEditing.right(doc, last)?.let { EditResult(doc, TextSelection.at(it)) }
        FieldKey.TAB -> BlockEditing.indent(doc, first, outdent = false)
        FieldKey.SHIFT_TAB -> BlockEditing.indent(doc, first, outdent = true)
    }
}
