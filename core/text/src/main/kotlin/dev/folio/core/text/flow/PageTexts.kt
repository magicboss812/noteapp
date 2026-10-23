package dev.folio.core.text.flow

import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextFlow
import dev.folio.core.text.markdown.MarkdownDocument

/**
 * The block a live text field draws (07-text-engine.md#editing): its runs are left out of the page text, or in a
 * table only its [cell] ([dev.folio.core.text.layout.TableLayout.cellIndex]; -1 leaves the whole block out).
 */
data class FocusedBlock(
    val flowId: FlowId,
    /** [dev.folio.core.text.markdown.MarkdownBlock.id] in the document the layouts were made from. */
    val blockId: Long,
    val cell: Int = FlowLayout.NO_CELL,
)

/**
 * Turns the flows of a document into what each page paints (05-canvas-rendering.md#page-renderer): every flow is
 * laid out over its frames on the loaded pages and the resulting runs are grouped by page. Sticky notes are skipped
 * (their painter arrives with P07-T05). Callers load a flow's pages first and run this on the `text` dispatcher.
 */
object PageTexts {
    /** Parses the flow text; the editor passes a cache that keeps block ids stable while a flow is edited. */
    private val parseFlow: (TextFlow) -> MarkdownDocument = { MarkdownDocument.parse(it.markdown) }

    /** [PageText] of every page of [doc] that shows text; other pages are absent. Flows do not auto-continue here (see [FlowReflow]). */
    fun of(
        doc: Document,
        layouter: FlowLayouter,
        markdown: (TextFlow) -> MarkdownDocument = parseFlow,
        focused: FocusedBlock? = null,
    ): Map<PageId, PageText> = of(doc, layouts(doc, layouter, markdown), focused)

    /** Layout of every flow of [doc] that has frames on loaded pages. */
    fun layouts(
        doc: Document,
        layouter: FlowLayouter,
        markdown: (TextFlow) -> MarkdownDocument = parseFlow,
    ): Map<FlowId, FlowLayout> {
        val out = LinkedHashMap<FlowId, FlowLayout>()
        for ((flowId, flow) in doc.flows) {
            val slots = FlowFrames.slotsOf(doc, flowId)
            if (slots.isEmpty()) continue
            out[flowId] = layouter.layout(markdown(flow), flow.style.toTextStyle(), flow.style.paragraphGapLines, slots)
        }
        return out
    }

    /** Page texts of [layouts]; the runs of [focused] are left out. */
    fun of(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        focused: FocusedBlock? = null,
    ): Map<PageId, PageText> {
        val runs = LinkedHashMap<PageId, MutableList<TextRun>>()
        for ((flowId, layout) in layouts) {
            val skip = if (focused?.flowId == flowId) layout.markdown.indexOf(focused.blockId) else FlowLayout.NO_BLOCK
            val cell = focused?.cell ?: FlowLayout.NO_CELL
            for (i in layout.slots.indices) {
                val slot = layout.slots[i]
                val isFrame = doc.page(slot.pageId)?.objects?.firstOrNull { it.id == slot.frameId } is FlowFrame
                if (isFrame) runs.getOrPut(slot.pageId) { ArrayList() } += layout.runs(i, skip, cell)
            }
        }
        return runs.mapValues { (_, list) -> PageText.of(list) }
    }
}
