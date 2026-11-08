package dev.folio.core.text.flow

import dev.folio.core.model.PageId
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.edit.BlockSplice
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.SourceOffsets
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock

/** One line's piece of a selection highlight: [rect] (page pt) on page [pageId]. */
data class SelectionBox(
    val pageId: PageId,
    val rect: RectPt,
)

/** Where a caret or selection end is drawn: on page [pageId] at [xPt], its line box from [topPt] to [bottomPt] (page pt). */
data class CaretSpot(
    val pageId: PageId,
    val xPt: Float,
    val topPt: Float,
    val bottomPt: Float,
)

/**
 * The highlight of [selection] (07-text-engine.md#selection-as-built): one box per selected line part, in flow order,
 * on the pages the lines are on. Block [skipBlock] is left out (the live field shows its own part). Blocks that show
 * no text of their own (tables, math, rules) are highlighted whole. Empty for a collapsed selection or one whose ends
 * are not in this layout.
 */
fun FlowLayout.selectionBoxes(
    selection: TextSelection,
    skipBlock: Int = FlowLayout.NO_BLOCK,
): List<SelectionBox> {
    if (selection.isCollapsed || !contains(selection.anchor) || !contains(selection.focus)) return emptyList()
    val range = BlockSplice.Range.of(markdown, selection)
    val out = ArrayList<SelectionBox>()
    for (frame in slots.indices) {
        for (slice in slices(frame)) {
            val index = slice.blockIndex
            if (index in range.first..range.last && index != skipBlock) boxesOf(frame, slice, range, out)
        }
    }
    return out
}

/** Where [caret] is drawn in this layout, null when its block is not laid out or not placed. */
fun FlowLayout.caretSpot(caret: Caret): CaretSpot? {
    val index = markdown.indexOf(caret.blockId)
    val layout = layoutOf(index) ?: return null
    val block = markdown.blocks[index]
    val visible = SourceOffsets.visibleText(block.content)
    val atEnd = caret.offset >= block.source.length && block.content != BlockContent.Empty
    val plain = if (visible == null && !layout.editsSource) -1 else plainOf(block, layout, caret.offset)
    val line =
        if (plain >= 0) {
            layout.lineForOffset(plain)
        } else if (atEnd) {
            layout.lineCount - 1
        } else {
            0
        }
    for (frame in slots.indices) {
        val slot = slots[frame]
        val slice = slices(frame).firstOrNull { it.blockIndex == index && line - firstLine(it) in 0 until lineCount(it) }
        if (slice != null) {
            val top = slot.rect.top + slice.topUnit * slot.grid.unitPt + layout.lineTopPt(line) - layout.lineTopPt(firstLine(slice))
            val x =
                if (plain >= 0) {
                    layout.plainXPt(plain)
                } else if (atEnd) {
                    slot.rect.widthPt
                } else {
                    layout.textLeftPt
                }
            return CaretSpot(slot.pageId, slot.rect.left + x, top, top + layout.boxUnits * slot.grid.unitPt)
        }
    }
    return null
}

private fun FlowLayout.contains(caret: Caret): Boolean = layoutOf(markdown.indexOf(caret.blockId)) != null

// The boxes of the selected lines of [slice] (slot [frame]).
private fun FlowLayout.boxesOf(
    frame: Int,
    slice: FlowSlice,
    range: BlockSplice.Range,
    out: MutableList<SelectionBox>,
) {
    val index = slice.blockIndex
    val layout = layoutOf(index) ?: return
    val block = markdown.blocks[index]
    val slot = slots[frame]
    val unitPt = slot.grid.unitPt
    val first = firstLine(slice)
    val top = slot.rect.top + slice.topUnit * unitPt - layout.lineTopPt(first)
    val empty = block.content == BlockContent.Empty
    val whole = !empty && !layout.editsSource && SourceOffsets.visibleText(block.content) == null
    // A block before the last one is selected to its end and its line break.
    val from = if (index == range.first) plainOf(block, layout, range.startOffset) else 0
    val to = if (index == range.last) plainOf(block, layout, range.endOffset) else layout.plainText.length + 1
    for (line in first until first + lineCount(slice)) {
        val span =
            when {
                // An empty block shows its line break only, like an empty line inside a paragraph.
                empty -> if (index == range.last) null else layout.textLeftPt..layout.textLeftPt + unitPt / 2

                whole -> layout.textLeftPt..slot.rect.widthPt

                else -> layout.plainSpanOnLine(line, from, to, unitPt / 2)
            }
        if (span != null) {
            val lineTop = top + layout.lineTopPt(line)
            val rect = RectPt(slot.rect.left + span.start, lineTop, slot.rect.left + span.endInclusive, lineTop + layout.boxUnits * unitPt)
            out += SelectionBox(slot.pageId, rect)
        }
    }
}

// The x (from the block's left edge) of the caret before character [offset] of the block's plain text.
private fun BlockLayout.plainXPt(offset: Int): Float = textLeftPt + (text?.xPt(offset) ?: 0f)

// Left and right edge (from the block's left edge) of the part of [plainStart]..[plainEnd] on [line], null when none
// of it is there. A range that goes on past the line reaches the line's end and at least [minWidthPt] beyond its last
// character, so an empty line in it shows too.
private fun BlockLayout.plainSpanOnLine(
    line: Int,
    plainStart: Int,
    plainEnd: Int,
    minWidthPt: Float,
): ClosedFloatingPointRange<Float>? {
    val t = text ?: return null
    val from = maxOf(plainStart, t.lineStart(line))
    val lineEnd = t.lineEnd(line)
    val goesOn = plainEnd > lineEnd || (plainEnd == lineEnd && line < t.lineCount - 1)
    val to = minOf(plainEnd, lineEnd)
    if (from > to || (from == to && !goesOn)) return null
    val left = plainXPt(from)
    val right = if (goesOn) maxOf(textLeftPt + t.lineRightPt(line), plainXPt(to), left + minWidthPt) else plainXPt(to)
    return left..right
}

// Offset into the block's laid-out text (a callout's title first) of source offset [sourceOffset]; the same offset in
// the layout of an edited block, which shows the source.
private fun plainOf(
    block: MarkdownBlock,
    layout: BlockLayout,
    sourceOffset: Int,
): Int {
    if (layout.editsSource) return sourceOffset.coerceIn(0, layout.plainText.length)
    val visible = SourceOffsets.visibleText(block.content)?.length ?: 0
    return layout.plainText.length - visible + SourceOffsets.toPlain(block, sourceOffset)
}
