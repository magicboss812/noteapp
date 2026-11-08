package dev.folio.core.text.flow

import dev.folio.core.model.Borders
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
import dev.folio.core.model.TextFlow
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.InsertPages
import dev.folio.core.model.edit.RemovePages
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.layout.FlowTextStyle
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.MarkdownDocument
import kotlinx.collections.immutable.persistentListOf
import kotlin.math.abs
import kotlin.math.max

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
        val flow = doc.flows[flowId]
        val continues = flow?.autoContinue == true
        val slots = ArrayList<FrameSlot>()
        for (ref in doc.pages) {
            val page = doc.page(ref.id) ?: continue
            val widest = flow?.let { widestBorders(ref, it) }
            page.objects
                .filter { it is FlowFrame && it.flow == flowId }
                .map { it as FlowFrame }
                .sortedBy { it.order }
                .mapTo(slots) { frame ->
                    val slot = slotOf(ref, frame, continues)
                    if (widest == null ||
                        !frame.autoGrow
                    ) {
                        slot
                    } else {
                        slot.copy(growRect = RectPt(widest.leftPt, slot.rect.top, widest.rightPt, slot.rect.bottom))
                    }
                }
            page.objects
                .filterIsInstance<StickyNote>()
                .filter { it.flow == flowId }
                .mapTo(slots) { slotOf(ref, it) }
        }
        return slots
    }

    /**
     * Slot of [frame] on page [ref]: unbounded on infinite pages; an auto-growing box holds the lines down to the
     * bottom of the page text zone (at least one) when its flow [continues] on the next page (A-059), else it is
     * unbounded; other frames hold the line boxes of their rect.
     */
    fun slotOf(
        ref: PageRef,
        frame: FlowFrame,
        continues: Boolean = false,
    ): FrameSlot {
        val template = ref.background.template
        val unit = GridUnit.of(template)
        val capacity =
            when {
                ref.spec is PageSpec.Infinite -> {
                    FlowDistributor.UNBOUNDED
                }

                frame.autoGrow && continues -> {
                    max(1, TextZones.capacityUnits(TextZones.boxZone(ref.spec, template).bottom - frame.rect.top, unit.unitPt))
                }

                frame.autoGrow -> {
                    FlowDistributor.UNBOUNDED
                }

                else -> {
                    TextZones.capacityUnits(frame.rect.heightPt, unit.unitPt)
                }
            }
        return FrameSlot(ref.id, frame.id, frame.rect, LayoutGrid.of(unit), capacity)
    }

    // The widest borders of an auto width [flow] on page [ref] (A-059), null for fixed borders.
    private fun widestBorders(
        ref: PageRef,
        flow: TextFlow,
    ): Borders? {
        val anchor = flow.box.anchorXPt?.takeIf { flow.box.fitsText } ?: return null
        val zone = TextZones.boxZone(ref.spec, ref.background.template)
        return TextZones.autoBorders(zone, anchor, flow.style.align, Float.POSITIVE_INFINITY)
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
 * Keeps the page structure in step with a flow (07-text-engine.md#flows-and-frames, A-059): when the last frame of an
 * auto-continuing flow overflows on a fixed page (a BODY frame, or an auto-growing box at the text zone bottom),
 * continuation pages (same spec and background, without a PDF page) are inserted right after it, with a BODY frame or
 * a box between the same borders from the zone top; trailing frames of either kind that end up without text are
 * removed with their page when the page holds nothing else (never the flow's first page); auto-growing boxes get the
 * height of their text in whole U, and auto width boxes the borders of their widest line. Run [plan] on the document
 * after the text edit and execute `Batch(edit, command)`.
 */
class FlowReflow(
    private val layouter: FlowLayouter,
) {
    /** The page changes of [flowId] in [doc]; block [editBlock] (the one being edited) is measured as its source for auto width. */
    fun plan(
        doc: Document,
        flowId: FlowId,
        markdown: MarkdownDocument = MarkdownDocument.parse(checkNotNull(doc.flows[flowId]) { "unknown flow ${flowId.value}" }.markdown),
        newPageId: () -> PageId = PageId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
        editBlock: Int = FlowLayout.NO_BLOCK,
    ): Reflow {
        val flow = checkNotNull(doc.flows[flowId]) { "unknown flow ${flowId.value}" }
        val found = FlowFrames.slotsOf(doc, flowId)
        if (found.isEmpty()) return Reflow(null, null)
        val style = flow.style.toTextStyle()
        val borders = autoWidthBorders(doc, flow, found, markdown, style, editBlock)
        val slots = borders?.let { b -> found.map { it.copy(rect = it.rect.copy(left = b.leftPt, right = b.rightPt)) } } ?: found
        val last = slots.last()
        val pending = ArrayList<Page>()
        val continuation =
            if (flow.autoContinue) {
                continuationOf(doc, flowId, last, pending, newPageId, newObjectId)
            } else {
                null
            }
        val layout = layouter.layout(markdown, style, flow.style.paragraphGapLines, slots, continuation)
        val commands = ArrayList<EditCommand>()
        if (pending.isNotEmpty()) {
            commands += InsertPages(doc.indexOfPage(last.pageId) + 1, grownPending(pending, layout, slots.size))
        }
        val removed = if (pending.isEmpty()) emptyPages(doc, slots, layout) else null
        removed?.let { commands += it }
        commands += resizedBoxes(doc, slots, layout).filter { it.pageId !in removed?.ids.orEmpty() }
        val command =
            when (commands.size) {
                0 -> null
                1 -> commands.single()
                else -> Batch(commands)
            }
        return Reflow(layout, command)
    }

    /**
     * Makes continuation pages after [last] (a BODY frame or an auto-growing box on a fixed page) into [pending];
     * null when the flow cannot continue from there.
     */
    private fun continuationOf(
        doc: Document,
        flowId: FlowId,
        last: FrameSlot,
        pending: MutableList<Page>,
        newPageId: () -> PageId,
        newObjectId: () -> ObjectId,
    ): ((FrameSlot) -> FrameSlot?)? {
        val lastRef = checkNotNull(doc.pageRef(last.pageId))
        val lastFrame = frameOf(doc, last)
        if (lastFrame == null || lastRef.spec is PageSpec.Infinite) return null
        val background = lastRef.background.copy(pdf = null)
        val rect =
            when {
                lastFrame.role == FrameRole.BODY -> {
                    TextZones.bodyRect(lastRef.spec, background.template)
                }

                lastFrame.autoGrow -> {
                    val top = TextZones.boxZone(lastRef.spec, background.template).top
                    RectPt(last.rect.left, top, last.rect.right, top + last.grid.unitPt)
                }

                else -> {
                    null
                }
            } ?: return null
        var order = lastFrame.order
        return { _ ->
            val frame = FlowFrame(newObjectId(), flowId, rect, ++order, lastFrame.role, lastFrame.autoGrow)
            val page = Page(newPageId(), lastRef.spec, background, persistentListOf(frame))
            pending += page
            FlowFrames.slotOf(page.toRef(), frame, continues = true)
        }
    }

    /** [pending] continuation pages with their auto-growing boxes as tall as their text; slot `firstSlot + i` is on page i. */
    private fun grownPending(
        pending: List<Page>,
        layout: FlowLayout,
        firstSlot: Int,
    ): List<Page> =
        pending.mapIndexed { i, page ->
            val frame = page.objects.single() as FlowFrame
            if (!frame.autoGrow) {
                page
            } else {
                val slot = layout.slots[firstSlot + i]
                val grown = TextZones.grownBox(frame.rect, layout.distribution.usedUnits(firstSlot + i), slot.grid.unitPt)
                page.copy(objects = persistentListOf(frame.copy(rect = grown)))
            }
        }

    /**
     * Borders of an auto width box (A-059): anchored where the box was made, as wide as its widest line laid out at
     * the widest borders the page text zone allows, the same on every page; null for other flows.
     */
    private fun autoWidthBorders(
        doc: Document,
        flow: TextFlow,
        slots: List<FrameSlot>,
        markdown: MarkdownDocument,
        style: FlowTextStyle,
        editBlock: Int,
    ): Borders? {
        val first = slots.first()
        val frame = frameOf(doc, first)?.takeIf { it.role == FrameRole.BOX }
        val ref = doc.pageRef(first.pageId)
        if (!flow.box.fitsText || frame == null || ref == null) return null
        val zone = TextZones.boxZone(ref.spec, ref.background.template)
        val align = flow.style.align
        val anchor = flow.box.anchorXPt ?: TextZones.anchorOf(frame.rect, align)
        val widest = TextZones.autoBorders(zone, anchor, align, Float.POSITIVE_INFINITY)
        val content = layouter.contentWidthPt(markdown, style, widest.widthPt, first.grid, editBlock)
        return TextZones.autoBorders(zone, anchor, align, content + AUTO_WIDTH_SLACK_PT)
    }

    /** Pages of trailing text-less BODY frames or auto-growing boxes that hold nothing but that frame (the first slot's page stays). */
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
            val continued = only != null && (only.role == FrameRole.BODY || only.autoGrow)
            val isAuto = page != null && only?.id == slot.frameId && continued && page.spec !is PageSpec.Infinite
            if (isAuto && slot.pageId != firstPage) removable += slot.pageId
            i--
        }
        return if (removable.isEmpty()) null else RemovePages(removable.reversed())
    }

    /** Boxes whose rect differs from their slot's: auto-growing ones take the height of their text, auto width ones their borders. */
    private fun resizedBoxes(
        doc: Document,
        slots: List<FrameSlot>,
        layout: FlowLayout,
    ): List<UpdateObjects> {
        val updates = LinkedHashMap<PageId, MutableList<PageObject>>()
        slots.forEachIndexed { i, slot ->
            val frame = frameOf(doc, slot) ?: return@forEachIndexed
            if (frame.role != FrameRole.BOX) return@forEachIndexed
            val bordered = frame.rect.copy(left = slot.rect.left, right = slot.rect.right)
            val rect = if (frame.autoGrow) TextZones.grownBox(bordered, layout.distribution.usedUnits(i), slot.grid.unitPt) else bordered
            if (!rect.near(frame.rect)) updates.getOrPut(slot.pageId) { ArrayList() } += frame.copy(rect = rect)
        }
        return updates.map { (page, objects) -> UpdateObjects(page, objects) }
    }

    private fun RectPt.near(other: RectPt): Boolean =
        abs(left - other.left) <= EPS_PT &&
            abs(right - other.right) <= EPS_PT &&
            abs(top - other.top) <= EPS_PT &&
            abs(bottom - other.bottom) <= EPS_PT

    private fun frameOf(
        doc: Document,
        slot: FrameSlot,
    ): FlowFrame? = doc.page(slot.pageId)?.objects?.firstOrNull { it.id == slot.frameId } as? FlowFrame

    private companion object {
        const val EPS_PT = 1e-3f

        // Added to the widest line so laying the text out again at the box width never wraps it.
        const val AUTO_WIDTH_SLACK_PT = 1f
    }
}
