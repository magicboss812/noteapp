package dev.folio.feature.editor.text

import dev.folio.core.model.Document
import dev.folio.core.model.FlowBox
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextFlow
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.hit

/** Where a Text tool tap or a keystroke with nothing focused puts the caret (10-editor-ui.md#text-boxes). */
internal sealed interface CaretPlan {
    /** The point is on a box: the block under it gets the focus like a finger tap. */
    data object Hit : CaretPlan

    /** The caret goes to the end of block [blockIndex] of [flowId]; nothing changes. */
    data class End(
        val flowId: FlowId,
        val blockIndex: Int,
    ) : CaretPlan

    /**
     * A new empty box: [setup] (its flow and frame) runs as one step, the caret goes to its first block. The box is
     * dropped again without an undo step when it loses the focus still empty (A-059).
     */
    data class NewBox(
        val flowId: FlowId,
        val setup: List<EditCommand>,
    ) : CaretPlan
}

/** What a new sticky note looks like: paper color [argb] and tilt [rotationDeg]. */
data class StickyLook(
    val argb: Int,
    val rotationDeg: Float,
)

/** Plans for [CaretPlan]: pure functions of a document and the layouts of its flows. */
internal object CaretPlans {
    /**
     * The plan for a Text tool tap at ([xPt], [yPt]) on page [pageId]: on a box (its used lines and [slopPt] around
     * them) the block under the tap; anywhere else a new auto width box at the tap (A-059). Null for an unknown page.
     */
    @Suppress("LongParameterList") // the tap, its slop, the style of a new box and the ids it gets
    fun forTap(
        doc: Document,
        layouts: Map<FlowId, FlowLayout>,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        style: FlowStyle,
        slopPt: Float = 0f,
        newFlowId: () -> FlowId = FlowId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): CaretPlan? {
        val ref = doc.pageRef(pageId) ?: return null
        if (doc.page(pageId) == null) return null
        if (layouts.values.any { it.hit(pageId, xPt, yPt, slopPt) != null }) return CaretPlan.Hit
        val rect = TextZones.newBoxAt(ref.spec, ref.background.template, xPt, yPt, style.baselineLiftUnits, style.align)
        return newBox(pageId, rect, style, newFlowId(), newObjectId())
    }

    /**
     * The plan for typing on page [pageId] with nothing focused: the end of the last box on the page (the one whose
     * text there ends lowest), else a new box on the first line of the page's text zone. Null for an unknown page.
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
        if (doc.page(pageId) == null) return null
        lastBoxEnd(layouts, pageId)?.let { return it }
        val template = ref.background.template
        val zone = TextZones.boxZone(ref.spec, template)
        val rect =
            TextZones.newBoxAt(
                ref.spec,
                template,
                TextZones.anchorOf(zone, style.align),
                zone.top,
                style.baselineLiftUnits,
                style.align,
            )
        return newBox(pageId, rect, style, newFlowId(), newObjectId())
    }

    // The end of the text shown lowest on [pageId]: the last block of the slot whose used lines end lowest.
    private fun lastBoxEnd(
        layouts: Map<FlowId, FlowLayout>,
        pageId: PageId,
    ): CaretPlan.End? {
        var best: CaretPlan.End? = null
        var bottom = Float.NEGATIVE_INFINITY
        for ((flowId, layout) in layouts) {
            for (slot in layout.slotsOn(pageId)) {
                val last = layout.slices(slot).lastOrNull() ?: continue
                val end = layout.outlineOf(slot).bottom
                if (end > bottom) {
                    bottom = end
                    best = CaretPlan.End(flowId, last.blockIndex)
                }
            }
        }
        return best
    }

    /**
     * The plan for a Sticky tool tap at ([xPt], [yPt]) on page [pageId] (A-068): a new sticky note in [look] there
     * (`TextZones.newStickyAt`) with its own empty flow in [style] and the sticky ink color; the caret goes into it.
     * Null for an unknown page.
     */
    @Suppress("LongParameterList") // the tap, the sticky's looks and the ids it gets
    fun forSticky(
        doc: Document,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        look: StickyLook,
        style: FlowStyle,
        newFlowId: () -> FlowId = FlowId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): CaretPlan.NewBox? {
        val ref = doc.pageRef(pageId) ?: return null
        if (doc.page(pageId) == null) return null
        val rect = TextZones.newStickyAt(ref.spec, ref.background.template, xPt, yPt)
        val flowId = newFlowId()
        val flow = TextFlow(flowId, "", style.copy(textArgb = StickyNote.INK_ARGB), autoContinue = false)
        val sticky = StickyNote(newObjectId(), flowId, rect, look.rotationDeg, look.argb)
        return CaretPlan.NewBox(flowId, listOf(UpdateFlows(listOf(flow)), AddObjects(pageId, listOf(sticky))))
    }

    private fun newBox(
        pageId: PageId,
        rect: RectPt,
        style: FlowStyle,
        flowId: FlowId,
        frameId: ObjectId,
    ): CaretPlan {
        val flow = TextFlow(flowId, "", style, autoContinue = true, box = FlowBox.created(TextZones.anchorOf(rect, style.align)))
        val frame = FlowFrame(frameId, flowId, rect, 0, FrameRole.BOX, autoGrow = true)
        return CaretPlan.NewBox(flowId, listOf(UpdateFlows(listOf(flow)), AddObjects(pageId, listOf(frame))))
    }
}
