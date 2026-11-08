package dev.folio.core.text.flow

import androidx.compose.ui.graphics.toArgb
import dev.folio.core.model.PageId
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.markdown.BlockParser
import dev.folio.core.text.markdown.MarkdownBlock

/** One slice of the edited block: the run to paint and the page it is on. */
data class EditedPiece(
    val pageId: PageId,
    val run: TextRun,
)

/**
 * The edited block as the canvas overlay draws it (07-text-engine.md#rendering, A-058): its source layout
 * ([BlockLayout.editsSource]) and one run per slice, paginated like every other block. Comes from the flow layout
 * ([FlowLayout.editedBlock]) or, for a keystroke the flow has not reflowed yet, from [FlowLayouter.retype].
 */
class EditedBlock(
    val blockId: Long,
    /** The source shown; the overlay draws this block while the field holds the same text. */
    val source: String,
    val layout: BlockLayout,
    val pieces: List<EditedPiece>,
) {
    /** Where the caret before source offset [offset] is drawn (a full line box), null when its line is not placed. */
    fun caretSpot(offset: Int): CaretSpot? {
        val at = offset.coerceIn(0, source.length)
        val line = layout.lineForOffset(at)
        for (piece in pieces) {
            val run = piece.run
            if (line < run.firstLine || line >= run.firstLine + run.lineCount) continue
            val top = run.topPt + layout.lineTopPt(line)
            val x = run.leftPt + layout.textLeftPt + (layout.text?.xPt(at) ?: 0f)
            return CaretSpot(piece.pageId, x, top, top + layout.boxUnits * layout.unitPt)
        }
        return null
    }
}

/** Block [index] as the overlay draws it while edited, null unless it is laid out as its source and placed. */
fun FlowLayout.editedBlock(index: Int): EditedBlock? {
    val layout = layoutOf(index)?.takeIf { it.editsSource } ?: return null
    val block = markdown.blocks[index]
    val pieces = ArrayList<EditedPiece>(2)
    for (frame in slots.indices) {
        if (slices(frame).none { it.blockIndex == index }) continue
        for (run in runs(frame, onlyBlock = index)) pieces += EditedPiece(slots[frame].pageId, run)
    }
    return EditedBlock(block.id, block.source, layout, pieces)
}

/**
 * Block [index] of [flow] with [source] typed into it, laid out and distributed again from where it starts while the
 * rest of the flow stays (the overlay's answer to a keystroke before the reflow, 07-text-engine.md#rendering). Lines
 * past the last slot are not placed. Null when the block is not placed in [flow].
 */
internal fun retype(
    blockLayouter: BlockLayouter,
    parser: BlockParser,
    flow: FlowLayout,
    index: Int,
    source: String,
): EditedBlock? {
    val old = flow.markdown.blocks.getOrNull(index) ?: return null
    val start =
        flow.distribution.startFrames
            .getOrNull(index)
            ?.takeIf { it >= 0 } ?: return null
    val first = flow.slices(start).firstOrNull { it.blockIndex == index } ?: return null
    val slot = flow.slots[start]
    val block = MarkdownBlock(old.id, source, old.gap, parser.parse(source))
    // An auto width box widens with the text (A-059): the keystroke is laid out at the widest borders, as the reflow
    // will set them, so a line does not wrap for a frame.
    val layout = blockLayouter.editLayout(block, slot.growRect.widthPt, flow.style, slot.grid)
    val item = FlowLayouter.itemOf(block.content, layout)
    val distribution =
        FlowDistributor.distribute(
            1,
            0,
            capacity = { frame -> flow.slots.getOrNull(start + frame)?.capacityUnits },
            item = { _, _ -> item },
            startUnits = first.topUnit,
        )
    val codeBackground =
        flow.style.palette.codeBackground
            .toArgb()
    val pieces = ArrayList<EditedPiece>(2)
    for ((frame, slices) in distribution.frames.withIndex()) {
        val s = flow.slots[start + frame]
        for (slice in slices) {
            val line = slice.fromUnit / layout.boxUnits
            val top = s.rect.top + slice.topUnit * s.grid.unitPt - layout.lineTopPt(line)
            val column = s.growRect
            val run =
                TextRun(s.frameId, layout, line, slice.heightUnits / layout.boxUnits, column.left, top, column.widthPt, codeBackground)
            pieces += EditedPiece(s.pageId, run)
        }
    }
    return EditedBlock(block.id, source, layout, pieces)
}
