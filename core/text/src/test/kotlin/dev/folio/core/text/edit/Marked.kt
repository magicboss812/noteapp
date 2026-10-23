package dev.folio.core.text.edit

import dev.folio.core.text.markdown.MarkdownDocument

/** A document and selection read from text where `^` is the caret and `«` `»` mark a selection's anchor and focus. */
internal class Marked(
    val doc: MarkdownDocument,
    val selection: TextSelection,
) {
    val caret: Caret get() = selection.focus
}

internal fun marked(text: String): Marked {
    val plain = StringBuilder()
    var anchor = -1
    var focus = -1
    for (c in text) {
        when (c) {
            '^' -> {
                anchor = plain.length
                focus = plain.length
            }

            '«' -> {
                anchor = plain.length
            }

            '»' -> {
                focus = plain.length
            }

            else -> {
                plain.append(c)
            }
        }
    }
    require(anchor >= 0 && focus >= 0) { "No caret or selection marker in: $text" }
    val doc = BlockEditing.ensureBlock(MarkdownDocument.parse(plain.toString()))
    return Marked(doc, TextSelection(TextPositions.caretAt(doc, anchor), TextPositions.caretAt(doc, focus)))
}

/** The flow text of the result with the caret as `^` (or the selection as `«` `»`), the inverse of [marked]. */
internal fun EditResult.render(): String {
    val anchor = TextPositions.absolute(document, selection.anchor)
    val focus = TextPositions.absolute(document, selection.focus)
    val text = StringBuilder(document.toMarkdown())
    if (anchor == focus) {
        text.insert(anchor, '^')
    } else if (anchor < focus) {
        text.insert(focus, '»').insert(anchor, '«')
    } else {
        text.insert(anchor, '«').insert(focus, '»')
    }
    return text.toString()
}

/** Block sources of the flow text parsed afresh: what undo or a reopen would show. */
internal fun MarkdownDocument.reparsedSources(): List<String> = MarkdownDocument.parse(toMarkdown()).blocks.map { it.source }
