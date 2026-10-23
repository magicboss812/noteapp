package dev.folio.core.text.flow

import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.GridUnit
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.InsertPages
import dev.folio.core.model.edit.RemovePages
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.MarkdownDocument
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs

/** Frame slots of flows in a document (07-text-engine.md#flows-and-frames). */
object FlowFrames {
    /**
     * Slots of [flowId] on the loaded pages of [doc] in document order (page order, then frame order); a sticky note
     * of the flow is one slot in its own coordinates. Callers load the bodies of the flow's pages first.
     */
    fun slotsOf(
        doc: Document,
        flowId: FlowId,
    ): List<FrameSlot> {
        val slots = ArrayList<FrameSlot>()
        for (ref in doc.pages) {
            val page = doc.page(ref.id) ?: continue
            page.objects
                .filter { it is FlowFrame && it.flow == flowId }
                .map { it as FlowFrame }
                .sortedBy { it.order }
                .mapTo(slots) { slotOf(ref, it) }
            page.objects
                .filterIsInstance<StickyNote>()
                .filter { it.flow == flowId }
                .mapTo(slots) { slotOf(ref, it) }
        }
        return slots
    }

    /** Slot of [frame] on page [ref]: unbounded on infinite pages and for auto-growing boxes. */
    fun slotOf(
        ref: PageRef,
        frame: FlowFrame,
    ): FrameSlot {
        val unit = GridUnit.of(ref.background.template)
        val unbounded = ref.spec is PageSpec.Infinite || frame.autoGrow
        val capacity = if (unbounded) FlowDistributor.UNBOUNDED else TextZones.capacityUnits(frame.rect.heightPt, unit.unitPt)
        return FrameSlot(ref.id, frame.id, frame.rect, LayoutGrid.of(unit), capacity)
    }

    /** Slot of [sticky]: its padded content rect in sticky coordinates, lines at the page's U without column snapping. */
    fun slotOf(
        ref: PageRef,
        sticky: StickyNote,
    ): FrameSlot {
        val unitPt = GridUnit.of(ref.background.template).unitPt
        val rect = TextZones.stickyContentRect(sticky, unitPt)
        return FrameSlot(
            ref.id,
            sticky.id,
            rect,
            LayoutGrid(unitPt, LayoutGrid.LINED_INDENT_PT),
            TextZones.capacityUnits(rect.heightPt, unitPt),
        )
    }
}

/** A flow's layout and the page changes it needs ([command], null when none) to run in the same undo step as the edit. */
class Reflow(
    val layout: FlowLayout?,
    val command: EditCommand?,
)

/**
 * Keeps the page structure in step with a flow (07-text-engine.md#flows-and-frames): when the last BODY frame of an
 * auto-continuing flow overflows on a fixed page, continuation pages (same spec and background, without a PDF page)
 * with a BODY frame are inserted right after it; trailing BODY frames that end up without text are removed with
 * their page when the page holds nothing else (never the flow's first page); auto-growing boxes get the height of
 * their text in whole U. Run [plan] on the document after the text edit and execute `Batch(edit, command)`.
 */
class FlowReflow(
    private val layouter: FlowLayouter,
) {
    fun plan(
        doc: Document,
        flowId: FlowId,
        markdown: MarkdownDocument = MarkdownDocument.parse(checkNotNull(doc.flows[flowId]) { "unknown flow ${flowId.value}" }.markdown),
        newPageId: () -> PageId = PageId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): Reflow {
        val flow = checkNotNull(doc.flows[flowId]) { "unknown flow ${flowId.value}" }
        val slots = FlowFrames.slotsOf(doc, flowId)
        if (slots.isEmpty()) return Reflow(null, null)
        val last = slots.last()
        val lastRef = checkNotNull(doc.pageRef(last.pageId))
        val lastFrame = frameOf(doc, last)
        val pending = ArrayList<Page>()
        val continuation: ((FrameSlot) -> FrameSlot?)? =
            if (flow.autoContinue && lastFrame?.role == FrameRole.BODY && lastRef.spec !is PageSpec.Infinite) {
                var order = lastFrame.order
                val background = lastRef.background.copy(pdf = null)
                val rect = TextZones.bodyRect(lastRef.spec, background.template)
                rect?.let {
                    { _ ->
                        val frame = FlowFrame(newObjectId(), flowId, rect, ++order, FrameRole.BODY, autoGrow = false)
                        val page = Page(newPageId(), lastRef.spec, background, persistentListOf(frame))
                        pending += page
                        FlowFrames.slotOf(page.toRef(), frame)
                    }
                }
            } else {
                null
            }
        val layout = layouter.layout(markdown, flow.style.toTextStyle(), flow.style.paragraphGapLines, slots, continuation)
        val commands = ArrayList<EditCommand>()
        if (pending.isNotEmpty()) {
            commands += InsertPages(doc.indexOfPage(last.pageId) + 1, pending)
        } else {
            emptyPages(doc, slots, layout)?.let { commands += it }
        }
        commands += grownBoxes(doc, slots, layout)
        val command =
            when (commands.size) {
                0 -> null
                1 -> commands.single()
                else -> Batch(commands)
            }
        return Reflow(layout, command)
    }

    /** Pages of trailing text-less BODY frames that hold nothing but that frame (the first slot's page stays). */
    private fun emptyPages(
        doc: Document,
        slots: List<FrameSlot>,
        layout: FlowLayout,
    ): RemovePages? {
        val firstPage = slots.first().pageId
        val removable = ArrayList<PageId>()
        var i = slots.lastIndex
        while (i > 0 && layout.slices(i).isEmpty()) {
            val slot = slots[i]
            val page = doc.page(slot.pageId)
            val only = page?.objects?.singleOrNull() as? FlowFrame
            val isAuto = page != null && only?.id == slot.frameId && only.role == FrameRole.BODY && page.spec !is PageSpec.Infinite
            if (isAuto && slot.pageId != firstPage) removable += slot.pageId
            i--
        }
        return if (removable.isEmpty()) null else RemovePages(removable.reversed())
    }

    private fun grownBoxes(
        doc: Document,
        slots: List<FrameSlot>,
        layout: FlowLayout,
    ): List<EditCommand> {
        val updates = LinkedHashMap<PageId, MutableList<PageObject>>()
        slots.forEachIndexed { i, slot ->
            val frame = frameOf(doc, slot) ?: return@forEachIndexed
            if (frame.role != FrameRole.BOX || !frame.autoGrow) return@forEachIndexed
            val grown = TextZones.grownBox(frame.rect, layout.distribution.usedUnits(i), slot.grid.unitPt)
            if (abs(grown.bottom - frame.rect.bottom) > EPS_PT) updates.getOrPut(slot.pageId) { ArrayList() } += frame.copy(rect = grown)
        }
        return updates.map { (page, objects) -> UpdateObjects(page, objects) }
    }

    private fun frameOf(
        doc: Document,
        slot: FrameSlot,
    ): FlowFrame? = doc.page(slot.pageId)?.objects?.firstOrNull { it.id == slot.frameId } as? FlowFrame

    private companion object {
        const val EPS_PT = 1e-3f
    }
}
