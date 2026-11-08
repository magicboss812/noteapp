package dev.folio.feature.editor.text

import dev.folio.core.model.Borders
import dev.folio.core.model.BoxAlign
import dev.folio.core.model.BoxWidth
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.PageId
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextAlign
import dev.folio.core.model.TextFlow
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.model.edit.RemovePages
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.model.geometry.RectPt

/**
 * The page changes of the box operations (10-editor-ui.md#text-boxes: Move, Width, Alignment, Font size and color,
 * Delete) on a document, before the reflow [EditorText] runs after them; pure, so they run on any thread. Null where
 * nothing would change.
 */
internal object BoxCommands {
    /**
     * Box [flowId] dragged so that its first frame's text column is [rect] on page [toPage] (P07-T13): the first frame
     * goes there; the box's anchor and saved custom borders move along in x and its vertical offset becomes the
     * distance of [rect]'s top from its nearest rule (0 after a drag, which snaps; P07-T14 keys may leave it between
     * the lines); the frames on later pages of an auto-continuing box go (with their page when it held nothing else),
     * so the reflow continues the box from its new place, while other frames only move in x. Null if the first frame
     * is not a free box, [toPage] is unknown, or nothing changes.
     */
    fun move(
        doc: Document,
        flowId: FlowId,
        toPage: PageId,
        rect: RectPt,
    ): EditCommand? {
        val flow = doc.flows[flowId] ?: return null
        val frames = framesOf(doc, flowId)
        val (fromPage, first) = frames.firstOrNull() ?: return null
        val target = doc.pageRef(toPage)
        if (first.role != FrameRole.BOX || target == null || doc.page(toPage) == null) return null
        val dx = rect.left - first.rect.left
        val placed = RectPt(rect.left, rect.top, rect.left + first.rect.widthPt, rect.top + first.rect.heightPt)
        val offset = TextZones.offsetOf(target.background.template, placed.top)
        if (fromPage == toPage && placed == first.rect && flow.box.offsetPt == offset) return null
        val moved = first.copy(rect = placed)
        val commands = ArrayList<EditCommand>()
        if (fromPage == toPage) {
            commands += UpdateObjects(fromPage, listOf(moved))
        } else {
            commands += RemoveObjects(fromPage, listOf(first.id))
            commands += AddObjects(toPage, listOf(moved))
        }
        commands += followers(doc, flow, frames, toPage, dx)
        val box = flow.box
        val shifted =
            box.copy(
                anchorXPt = box.anchorXPt?.plus(dx),
                custom = box.custom?.shiftedX(dx),
                centerSaved = box.centerSaved?.shiftedX(dx),
                offsetPt = offset,
            )
        if (shifted != box) commands += UpdateFlows(listOf(flow.copy(box = shifted)))
        return Batch(commands)
    }

    /**
     * Box [flowId] with a width knob let go at [borders] (P07-T15): every frame of the box takes them (only the active
     * border set changes; the custom set stops being auto width). Null if the box is not a free box or nothing changes.
     */
    fun width(
        doc: Document,
        flowId: FlowId,
        borders: Borders,
    ): EditCommand? {
        val flow = doc.flows[flowId] ?: return null
        val frames = framesOf(doc, flowId).takeIf { it.firstOrNull()?.second?.role == FrameRole.BOX } ?: return null
        return bordersOf(flow, frames, borders, flow.copy(box = BoxWidth.knobbed(flow.box)))
    }

    /**
     * Box [flowId] aligned [align] (P07-T16): the flow's alignment changes and, for fixed borders, Center saves and
     * centers them on the anchor while leaving Center restores them ([BoxAlign]). The body text of older notes only
     * changes its alignment. Null for an unknown flow, a flow without frames, or the same alignment.
     */
    fun align(
        doc: Document,
        flowId: FlowId,
        align: TextAlign,
    ): EditCommand? {
        val flow = doc.flows[flowId] ?: return null
        val frames = framesOf(doc, flowId)
        val (pageId, first) = frames.firstOrNull() ?: return null
        if (flow.style.align == align) return null
        val aligned = flow.copy(style = flow.style.copy(align = align))
        val ref = doc.pageRef(pageId)
        if (first.role != FrameRole.BOX || ref == null) return UpdateFlows(listOf(aligned))
        val zone = TextZones.boxZone(ref.spec, ref.background.template)
        val active = Borders(first.rect.left, first.rect.right)
        val change = BoxAlign.aligned(flow.box, flow.style.align, align, active, Borders(zone.left, zone.right))
        return bordersOf(flow, frames, change.borders, aligned.copy(box = change.box))
    }

    /**
     * Box [flowId] restyled by [change] as a whole (P07-T17: its body size or text color). Sizes stay derived, so the
     * reflow keeps every line on the rules. Null for an unknown flow, a flow without frames, or the same style.
     */
    fun restyle(
        doc: Document,
        flowId: FlowId,
        change: (FlowStyle) -> FlowStyle,
    ): EditCommand? {
        val flow = doc.flows[flowId] ?: return null
        if (framesOf(doc, flowId).isEmpty()) return null
        val style = change(flow.style)
        return if (style == flow.style) null else UpdateFlows(listOf(flow.copy(style = style)))
    }

    /**
     * The fill width button on box [flowId] (P07-T15): the frames switch between the custom borders and the fill
     * borders (the page text zone edges the first time) and the box keeps the other set. Null if the box is not a free
     * box (the body text of older notes always fills its pages).
     */
    fun fill(
        doc: Document,
        flowId: FlowId,
    ): EditCommand? {
        val flow = doc.flows[flowId] ?: return null
        val frames = framesOf(doc, flowId)
        val (pageId, first) = frames.firstOrNull()?.takeIf { it.second.role == FrameRole.BOX } ?: return null
        val ref = doc.pageRef(pageId) ?: return null
        val zone = TextZones.boxZone(ref.spec, ref.background.template)
        val change = BoxWidth.toggled(flow.box, Borders(first.rect.left, first.rect.right), Borders(zone.left, zone.right))
        return bordersOf(flow, frames, change.borders, flow.copy(box = change.box))
    }

    /**
     * Box [flowId] removed with its text (P07-T13 Delete): its frames, its flow, and the pages after its first one that
     * held nothing but its frame when the box continues there by itself. Null for an unknown flow or a flow without
     * frames (sticky notes keep theirs).
     */
    fun delete(
        doc: Document,
        flowId: FlowId,
    ): EditCommand? {
        val flow = doc.flows[flowId] ?: return null
        val frames = framesOf(doc, flowId)
        if (frames.isEmpty()) return null
        if (doc.pageBodies.values.any { page -> page.objects.any { it is StickyNote && it.flow == flowId } }) return null
        val commands = ArrayList<EditCommand>()
        val emptied = ArrayList<PageId>()
        for ((pageId, ids) in frames.groupBy({ it.first }, { it.second.id })) {
            commands += RemoveObjects(pageId, ids)
            val alone = doc.page(pageId)?.objects?.size == ids.size
            if (flow.autoContinue && alone && pageId != frames.first().first) emptied += pageId
        }
        if (emptied.isNotEmpty()) commands += RemovePages(emptied)
        commands += UpdateFlows(emptyList(), remove = listOf(flowId))
        return Batch(commands)
    }

    // The free box frames of [flow] ([frames]) between [borders] and the flow becoming [next]; null when neither changes.
    private fun bordersOf(
        flow: TextFlow,
        frames: List<Pair<PageId, FlowFrame>>,
        borders: Borders,
        next: TextFlow,
    ): EditCommand? {
        val commands = ArrayList<EditCommand>()
        for ((pageId, pageFrames) in frames.groupBy({ it.first }, { it.second })) {
            val changed =
                pageFrames
                    .filter { it.role == FrameRole.BOX && (it.rect.left != borders.leftPt || it.rect.right != borders.rightPt) }
                    .map { it.copy(rect = it.rect.copy(left = borders.leftPt, right = borders.rightPt)) }
            if (changed.isNotEmpty()) commands += UpdateObjects(pageId, changed)
        }
        if (next != flow) commands += UpdateFlows(listOf(next))
        return if (commands.isEmpty()) null else Batch(commands)
    }

    // What a move by [dx] does to the frames after the first ([frames] of [flow], the first one going to [toPage]): an
    // auto-continuing box drops them (and their pages that held nothing else), so its reflow continues from the new
    // place; other boxes move them along in x.
    private fun followers(
        doc: Document,
        flow: TextFlow,
        frames: List<Pair<PageId, FlowFrame>>,
        toPage: PageId,
        dx: Float,
    ): List<EditCommand> {
        val commands = ArrayList<EditCommand>()
        val emptied = ArrayList<PageId>()
        val fromPage = frames.first().first
        for ((pageId, frame) in frames.drop(1)) {
            if (flow.autoContinue) {
                commands += RemoveObjects(pageId, listOf(frame.id))
                val alone = doc.page(pageId)?.objects?.size == 1
                if (alone && pageId != toPage && pageId != fromPage) emptied += pageId
            } else {
                commands += UpdateObjects(pageId, listOf(frame.copy(rect = frame.rect.shiftedX(dx))))
            }
        }
        if (emptied.isNotEmpty()) commands += RemovePages(emptied.distinct())
        return commands
    }

    // The frames of [flowId] on the loaded pages, in page order and then frame order.
    private fun framesOf(
        doc: Document,
        flowId: FlowId,
    ): List<Pair<PageId, FlowFrame>> {
        val out = ArrayList<Pair<PageId, FlowFrame>>()
        for (ref in doc.pages) {
            val page = doc.page(ref.id) ?: continue
            page.objects
                .filterIsInstance<FlowFrame>()
                .filter { it.flow == flowId }
                .sortedBy { it.order }
                .forEach { out += ref.id to it }
        }
        return out
    }

    private fun RectPt.shiftedX(dx: Float): RectPt = copy(left = left + dx, right = right + dx)

    private fun Borders.shiftedX(dx: Float): Borders = Borders(leftPt + dx, rightPt + dx)
}
