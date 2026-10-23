package dev.folio.core.text.flow

import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.PageId
import dev.folio.core.text.markdown.MarkdownDocument

/**
 * Turns the flows of a document into what each page paints (05-canvas-rendering.md#page-renderer): every flow is
 * laid out over its frames on the loaded pages and the resulting runs are grouped by page. Sticky notes are skipped
 * (their painter arrives with P07-T05). Callers load a flow's pages first and run this on the `text` dispatcher.
 */
object PageTexts {
    /** [PageText] of every page of [doc] that shows text; other pages are absent. Flows do not auto-continue here (see [FlowReflow]). */
    fun of(
        doc: Document,
        layouter: FlowLayouter,
    ): Map<PageId, PageText> {
        val runs = LinkedHashMap<PageId, MutableList<TextRun>>()
        for ((flowId, flow) in doc.flows) {
            val slots = FlowFrames.slotsOf(doc, flowId)
            if (slots.isEmpty()) continue
            val markdown = MarkdownDocument.parse(flow.markdown)
            val layout = layouter.layout(markdown, flow.style.toTextStyle(), flow.style.paragraphGapLines, slots)
            for (i in slots.indices) {
                val slot = slots[i]
                val isFrame = doc.page(slot.pageId)?.objects?.firstOrNull { it.id == slot.frameId } is FlowFrame
                if (isFrame) runs.getOrPut(slot.pageId) { ArrayList() } += layout.runs(i)
            }
        }
        return runs.mapValues { (_, list) -> PageText.of(list) }
    }
}
