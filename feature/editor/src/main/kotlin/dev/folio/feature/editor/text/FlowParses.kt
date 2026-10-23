package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId
import dev.folio.core.model.TextFlow
import dev.folio.core.text.markdown.MarkdownDocument

/**
 * The parsed Markdown of the open flows. A flow keeps its [MarkdownDocument] (and so its block ids and cached block
 * layouts) while its text is unchanged; [adopt] hands in the document an edit produced so typing keeps the focused
 * block's id. Text that changed any other way (undo, redo) is parsed again with new ids. Shared by the layout thread
 * and the editing path.
 */
internal class FlowParses {
    private val parsed = HashMap<FlowId, Entry>()

    private class Entry(
        val markdown: String,
        val document: MarkdownDocument,
    )

    /** The parsed [flow]. */
    @Synchronized
    fun of(flow: TextFlow): MarkdownDocument {
        val entry = parsed[flow.id]
        if (entry != null && entry.markdown == flow.markdown) return entry.document
        return MarkdownDocument.parse(flow.markdown).also { parsed[flow.id] = Entry(flow.markdown, it) }
    }

    /** Remembers [document] as the parse of flow [id] whose text will be `document.toMarkdown()`. */
    @Synchronized
    fun adopt(
        id: FlowId,
        document: MarkdownDocument,
    ) {
        parsed[id] = Entry(document.toMarkdown(), document)
    }

    /** The block ids of flow [id] if it was parsed, else null. */
    @Synchronized
    fun cached(id: FlowId): MarkdownDocument? = parsed[id]?.document
}
