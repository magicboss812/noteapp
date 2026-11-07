package dev.folio.feature.editor.text

import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.TextFlow
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.flow.FlowFrames
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.FlowLayouter
import dev.folio.core.text.flow.FrameSlot
import dev.folio.core.text.flow.hit
import dev.folio.core.text.flow.toTextStyle
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import kotlin.math.floor

/** Where a Text tool tap or a keystroke with nothing focused puts the caret (10-editor-ui.md#text-tool). */
internal sealed interface CaretPlan {
    /** The point is on text: the block under it gets the focus like a finger tap. */
    data object Hit : CaretPlan

    /** The caret goes to the end of block [blockIndex] of [flowId]; nothing changes. */
    data class End(
        val flowId: FlowId,
        val blockIndex: Int,
    ) : CaretPlan

    /**
     * [setup] runs first (a new flow with its frame, or a new body frame), then [lines] empty paragraphs are added
     * after the last block of [flowId] and the caret goes to the last block: the tapped line.
     */
    data class Append(
        val flowId: FlowId,
        val setup: List<EditCommand>,
        val lines: Int,
    ) : CaretPlan
}

/** Plans for [CaretPlan]: pure functions of a document and the layouts of its flows. */
internal object CaretPlans {
    /**
     * The plan for a Text tool tap at ([xPt], [yPt]) on page [pageId]: on text the block under it; below the end of a
     * flow its last block, with empty paragraphs down to the tapped line; in the body zone of a page without text
     * the body flow (created, or continued with a body frame on a page after its last one); anywhere else, and in the
     * body zone of a page before the body flow's last page, a new text box at the tap. Null for an unknown page.
     */
    fun forTap(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        style: FlowStyle,
        newFlowId: () -> FlowId = FlowId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): CaretPlan? {
        val ref = doc.pageRef(pageId) ?: return null
        val page = doc.page(pageId) ?: return null
        val body = TextZones.bodyRect(ref.spec, ref.background.template)
        val ownsBody = page.objects.any { it is FlowFrame && it.role == FrameRole.BODY }
        val new = NewText(style, newFlowId(), newObjectId())
        return onFlows(doc, layouts, pageId, xPt, yPt)
            ?: if (body == null || ownsBody || !body.contains(xPt, yPt)) {
                newBox(pageId, TextZones.newBoxRect(ref.spec, ref.background.template, xPt, yPt), new)
            } else {
                inBody(doc, layouts, ref, body, yPt, new)
            }
    }

    // A tap on a frame of some flow: on its text, or below the end of its text (Append), or null off all frames.
    private fun onFlows(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): CaretPlan? {
        val hit =
            layouts.entries.firstNotNullOfOrNull { (flowId, layout) ->
                layout.hit(pageId, xPt, yPt)?.let { Triple(flowId, layout, it) }
            }
        // Else a frame of a flow whose text does not reach it (a continuation page emptied by a deletion).
        val emptied =
            if (hit != null) {
                null
            } else {
                layouts.entries.firstNotNullOfOrNull { (flowId, layout) ->
                    val slot =
                        layout.slots.indices.firstOrNull {
                            layout.slots[it].pageId == pageId &&
                                layout.slots[it].rect.contains(xPt, yPt)
                        }
                    slot?.let { Triple(flowId, layout, it) }
                }
            }
        return when {
            hit != null -> {
                val (flowId, layout, at) = hit
                if (!at.pastEnd || at.blockIndex != layout.markdown.blocks.lastIndex) {
                    CaretPlan.Hit
                } else {
                    CaretPlan.Append(flowId, emptyList(), FillPlan.fill(doc, flowId, layout, target = at.slot, yPt = yPt))
                }
            }

            emptied != null -> {
                val (flowId, layout, slot) = emptied
                CaretPlan.Append(flowId, emptyList(), FillPlan.fill(doc, flowId, layout, target = slot, yPt = yPt))
            }

            else -> {
                null
            }
        }
    }

    // A tap in the [body] zone of a page without a body frame: the body flow, created or continued on this page.
    private fun inBody(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        ref: PageRef,
        body: RectPt,
        yPt: Float,
        new: NewText,
    ): CaretPlan {
        val (flowId, layout) = bodyFlowOf(doc, layouts) ?: return newBody(ref, body, yPt, new)
        val last = layout.slots.last()
        val lastFrame = doc.page(last.pageId)?.objects?.firstOrNull { it.id == last.frameId } as? FlowFrame
        val after = doc.indexOfPage(ref.id) > doc.indexOfPage(last.pageId)
        val continues = after && lastFrame?.role == FrameRole.BODY && ref.spec !is PageSpec.Infinite
        return if (lastFrame == null || !continues) {
            // The body flow runs from page to page: a frame here would pull its text back. A box on the tapped line.
            val line = TextZones.newBoxRect(ref.spec, ref.background.template, body.left, yPt)
            newBox(ref.id, line.copy(left = body.left, right = body.right), new)
        } else {
            val frame = FlowFrame(new.frameId, flowId, body, lastFrame.order + 1, FrameRole.BODY, autoGrow = false)
            val slots = layout.slots + FlowFrames.slotOf(ref, frame)
            val lines = FillPlan.fill(doc, flowId, layout, slots, slots.lastIndex, yPt)
            CaretPlan.Append(flowId, listOf(AddObjects(ref.id, listOf(frame))), lines)
        }
    }

    /**
     * The plan for typing on page [pageId] with nothing focused: the end of the body text shown on the page; on a page
     * after the body text ends, an empty paragraph on the page's first line; on a page without body text, the first
     * line of its body zone like a Text tool tap there. Null when the page has no body zone.
     */
    fun forTyping(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        pageId: PageId,
        style: FlowStyle,
        newFlowId: () -> FlowId = FlowId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): CaretPlan? {
        val ref = doc.pageRef(pageId) ?: return null
        val page = doc.page(pageId) ?: return null
        val frame =
            page.objects.firstOrNull { it is FlowFrame && it.role == FrameRole.BODY } as? FlowFrame
                ?: return TextZones.bodyRect(ref.spec, ref.background.template)?.let { zone ->
                    forTap(doc, layouts, pageId, zone.left, zone.top, style, newFlowId, newObjectId)
                }
        val layout = layouts[frame.flow] ?: return null
        val slot = layout.slots.indexOfFirst { it.frameId == frame.id }
        if (slot < 0) return null
        val end = FillPlan.endSlot(layout)
        val shown = layout.slices(slot)
        return when {
            slot < end && shown.isNotEmpty() -> {
                CaretPlan.End(frame.flow, shown.last().blockIndex)
            }

            slot == end -> {
                CaretPlan.Append(frame.flow, emptyList(), if (lastIsEmpty(layout.markdown)) 0 else 1)
            }

            else -> {
                CaretPlan.Append(
                    frame.flow,
                    emptyList(),
                    FillPlan.fill(doc, frame.flow, layout, target = slot, yPt = layout.slots[slot].rect.top),
                )
            }
        }
    }

    /**
     * The text to append to [markdown] (whose parse is [parsed]) so that it ends in [lines] more empty paragraphs, as
     * one edit; null when nothing is to add or the last block would swallow the newlines (an open code fence).
     */
    fun emptyParagraphs(
        markdown: String,
        parsed: MarkdownDocument,
        lines: Int,
    ): TextEdit? {
        if (lines <= 0) return null
        val last = parsed.blocks.lastOrNull() ?: return null
        val tail = last.source + last.gap
        // The newline count that ends a block and opens empty paragraphs depends on the separator already there.
        for (extra in 1..lines + 2) {
            val blocks = MarkdownDocument.parse(tail + "\n".repeat(extra)).blocks
            val kept = if (last.source.isEmpty()) 0 else 1
            val fits =
                blocks.size == kept + lines + (if (kept == 0) 1 else 0) &&
                    (kept == 0 || blocks[0].source == last.source) &&
                    blocks.drop(kept).all { it.content == BlockContent.Empty }
            if (fits) return TextEdit(markdown.length, markdown.length, "\n".repeat(extra))
        }
        return null
    }

    private fun lastIsEmpty(markdown: MarkdownDocument): Boolean = markdown.blocks.lastOrNull()?.content == BlockContent.Empty

    // The flow of the first BODY frame in page order, with its layout.
    private fun bodyFlowOf(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
    ): Pair<FlowId, FlowLayout>? =
        doc.pages.firstNotNullOfOrNull { ref ->
            val frame = doc.page(ref.id)?.objects?.firstOrNull { it is FlowFrame && it.role == FrameRole.BODY } as? FlowFrame
            val layout = frame?.let { layouts[it.flow] }
            if (frame != null && layout != null && layout.slots.isNotEmpty()) frame.flow to layout else null
        }

    private fun newBox(
        pageId: PageId,
        rect: RectPt,
        new: NewText,
    ): CaretPlan {
        val flow = TextFlow(new.flowId, "", new.style, autoContinue = false)
        val frame = FlowFrame(new.frameId, new.flowId, rect, 0, FrameRole.BOX, autoGrow = true)
        return CaretPlan.Append(new.flowId, listOf(UpdateFlows(listOf(flow)), AddObjects(pageId, listOf(frame))), 0)
    }

    private fun newBody(
        ref: PageRef,
        body: RectPt,
        yPt: Float,
        new: NewText,
    ): CaretPlan {
        val flow = TextFlow(new.flowId, "", new.style, autoContinue = true)
        val frame = FlowFrame(new.frameId, new.flowId, body, 0, FrameRole.BODY, autoGrow = false)
        val slot = FlowFrames.slotOf(ref, frame)
        val box = FlowLayouter.bodyBoxUnits(new.style.toTextStyle())
        val gap = new.style.paragraphGapLines.coerceIn(0, 1) * box
        // The new flow is one empty paragraph on the first line; the tapped line is that many lines further down.
        val lines = FillPlan.linesTo(listOf(slot), 0, box, FillPlan.unitOf(slot, yPt), 0, box, gap)
        return CaretPlan.Append(new.flowId, listOf(UpdateFlows(listOf(flow)), AddObjects(ref.id, listOf(frame))), lines)
    }

    // The style and ids a flow created by a plan gets.
    private class NewText(
        val style: FlowStyle,
        val flowId: FlowId,
        val frameId: ObjectId,
    )
}

// How many empty paragraphs reach a tapped line: a walk of the flow distribution's line placement.
private object FillPlan {
    // Upper bound of empty paragraphs one tap adds (a tap far down an unbounded page).
    private const val MAX_FILL = 400

    // Empty paragraphs after the end of [layout] until one covers [yPt] in slot [target] of [slots].
    fun fill(
        doc: Document,
        flowId: FlowId,
        layout: FlowLayout,
        slots: List<FrameSlot> = layout.slots,
        target: Int,
        yPt: Float,
    ): Int {
        val flowStyle = doc.flows[flowId]?.style ?: return 0
        val box = FlowLayouter.bodyBoxUnits(flowStyle.toTextStyle())
        val gap = flowStyle.paragraphGapLines.coerceIn(0, 1) * box
        val end = endSlot(layout)
        return linesTo(slots, end, layout.distribution.usedUnits(end), unitOf(slots[target], yPt), target, box, gap)
    }

    // The last slot that shows text (0 for a flow without any).
    fun endSlot(layout: FlowLayout): Int = layout.slots.indices.lastOrNull { layout.slices(it).isNotEmpty() } ?: 0

    // Lines of [box] units (a gap of [gap] before every line but a frame's first) placed from [used] units into slot
    // [from], until the line that reaches below [targetUnit] of slot [target]; a full slot continues in the next one.
    @Suppress("LongParameterList") // one distribution walk: where it starts, where it ends and the line pitch
    fun linesTo(
        slots: List<FrameSlot>,
        from: Int,
        used: Int,
        targetUnit: Int,
        target: Int,
        box: Int,
        gap: Int,
    ): Int {
        var slot = from
        var top = used
        var lines = 0

        fun beforeTarget() = slot < target || (slot == target && top <= targetUnit)
        while (lines < MAX_FILL && beforeTarget()) {
            val need = (if (top > 0) gap else 0) + box
            if (top + need > slots[slot].capacityUnits && slot < slots.lastIndex) {
                slot++
                top = 0
            } else {
                lines++
                top += need
            }
        }
        return lines
    }

    // The grid unit of [slot] that [yPt] falls in, counted from the slot's top.
    fun unitOf(
        slot: FrameSlot,
        yPt: Float,
    ): Int = floor((yPt - slot.rect.top) / slot.grid.unitPt).toInt().coerceAtLeast(0)
}
