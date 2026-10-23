package dev.folio.core.text.flow

import dev.folio.core.model.PageId

/** The block under a point: [slot] of the layout, the block's [blockIndex], the [line] of that block, [pastEnd] below the last block. */
data class BlockHit(
    val slot: Int,
    val blockIndex: Int,
    val line: Int,
    val pastEnd: Boolean,
)

/** Where a block's first line box is: [slot] of the layout, its page, the frame's left edge and width, [topPt] of the box. */
data class BlockPlacement(
    val slot: Int,
    val pageId: PageId,
    val leftPt: Float,
    val topPt: Float,
    val widthPt: Float,
)

/**
 * The block shown at ([xPt], [yPt]) on page [pageId], null outside every frame of this flow on the page. Below the
 * last block of a frame the hit is that block with [BlockHit.pastEnd] set (a tap in the free space puts the caret at the end).
 */
fun FlowLayout.hit(
    pageId: PageId,
    xPt: Float,
    yPt: Float,
): BlockHit? {
    val frame = slots.indices.firstOrNull { slots[it].pageId == pageId && slots[it].rect.contains(xPt, yPt) }
    return frame?.let { hitIn(it, yPt) }
}

private fun FlowLayout.hitIn(
    frame: Int,
    yPt: Float,
): BlockHit? {
    val slot = slots[frame]
    val slices = slices(frame)
    val unitPt = slot.grid.unitPt
    val slice = slices.firstOrNull { yPt < slot.rect.top + (it.topUnit + it.heightUnits) * unitPt } ?: slices.lastOrNull()
    val layout = slice?.let { layoutOf(it.blockIndex) }
    return when {
        slice == null -> {
            if (markdown.blocks.isEmpty()) BlockHit(frame, 0, 0, pastEnd = true) else null
        }

        layout == null -> {
            null
        }

        else -> {
            val top = slot.rect.top + slice.topUnit * unitPt
            val pastEnd = yPt >= top + slice.heightUnits * unitPt
            val line = if (pastEnd) layout.lineCount - 1 else firstLine(slice) + ((yPt - top) / (layout.boxUnits * unitPt)).toInt()
            BlockHit(frame, slice.blockIndex, line.coerceAtLeast(0), pastEnd)
        }
    }
}

/** Where block [index] starts: its first slice's slot and the page y of its first line box, null when it was not placed. */
fun FlowLayout.placementOf(index: Int): BlockPlacement? {
    val layout = layoutOf(index)
    val frame = slots.indices.firstOrNull { f -> slices(f).any { it.blockIndex == index } }
    if (layout == null || frame == null) return null
    val slice = slices(frame).first { it.blockIndex == index }
    val slot = slots[frame]
    val top = slot.rect.top + slice.topUnit * slot.grid.unitPt - layout.lineTopPt(firstLine(slice))
    return BlockPlacement(frame, slot.pageId, slot.rect.left, top, slot.rect.widthPt)
}
