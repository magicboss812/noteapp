package dev.folio.core.text.flow

import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextFlow
import dev.folio.core.model.geometry.RectPt
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
 * laid out over its frames on the loaded pages and the resulting runs are grouped by page, sticky notes with their
 * paint areas (A-068). Callers load a flow's pages first and run this on the `text` dispatcher.
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

    /** Layout of every flow of [doc] that has frames on loaded pages; note links to [brokenLinks] are drawn broken. */
    fun layouts(
        doc: Document,
        layouter: FlowLayouter,
        markdown: (TextFlow) -> MarkdownDocument = parseFlow,
        brokenLinks: Set<String> = emptySet(),
    ): Map<FlowId, FlowLayout> {
        val out = LinkedHashMap<FlowId, FlowLayout>()
        for (flowId in doc.flows.keys) {
            flowLayout(doc, flowId, layouter, markdown, brokenLinks, editBlockId = null)?.let { out[flowId] = it }
        }
        return out
    }

    /**
     * Layout of flow [flowId] as [layouts] makes it, null when it has no frames on loaded pages; block [editBlockId]
     * (the edited one, A-058) is laid out as its source and paginated with the rest.
     */
    fun flowLayout(
        doc: Document,
        flowId: FlowId,
        layouter: FlowLayouter,
        markdown: (TextFlow) -> MarkdownDocument = parseFlow,
        brokenLinks: Set<String> = emptySet(),
        editBlockId: Long? = null,
    ): FlowLayout? {
        val flow = doc.flows[flowId] ?: return null
        val slots = FlowFrames.slotsOf(doc, flowId)
        if (slots.isEmpty()) return null
        val style = flow.style.toTextStyle().let { if (brokenLinks.isEmpty()) it else it.copy(brokenLinks = brokenLinks) }
        val parsed = markdown(flow)
        val edit = editBlockId?.let(parsed::indexOf) ?: FlowLayout.NO_BLOCK
        return layouter.layout(parsed, style, flow.style.paragraphGapLines, slots, editBlock = edit)
    }

    /** Page texts of [layouts]; the runs of [focused] are left out. */
    fun of(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        focused: FocusedBlock? = null,
    ): Map<PageId, PageText> {
        val runs = LinkedHashMap<PageId, MutableList<TextRun>>()
        val areas = HashMap<PageId, MutableMap<ObjectId, RectPt>>()
        val straight = HashMap<PageId, MutableSet<ObjectId>>()
        for ((flowId, layout) in layouts) {
            val skip = if (focused?.flowId == flowId) layout.markdown.indexOf(focused.blockId) else FlowLayout.NO_BLOCK
            val cell = focused?.cell ?: FlowLayout.NO_CELL
            for (i in layout.slots.indices) {
                val slot = layout.slots[i]
                when (val o = doc.page(slot.pageId)?.objects?.firstOrNull { it.id == slot.frameId }) {
                    is FlowFrame -> {
                        runs.getOrPut(slot.pageId) { ArrayList() } += layout.runs(i, skip, cell)
                    }

                    // A sticky note is drawn straight while its text is edited (A-068).
                    is StickyNote -> {
                        runs.getOrPut(slot.pageId) { ArrayList() } += layout.runs(i, skip, cell)
                        areas.getOrPut(slot.pageId) { HashMap() }[o.id] = o.paintBounds
                        if (focused?.flowId == flowId) straight.getOrPut(slot.pageId) { HashSet() } += o.id
                    }

                    else -> {
                        Unit
                    }
                }
            }
        }
        return runs.mapValues { (id, list) -> PageText.of(list, areas[id].orEmpty(), straight[id].orEmpty()) }
    }
}
