package dev.folio.core.text.flow

import dev.folio.core.model.PageId
import dev.folio.core.text.edit.SourceOffsets
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.edit.TableEditing
import dev.folio.core.text.layout.BlockDecoration
import dev.folio.core.text.markdown.InlineSpan
import dev.folio.core.text.markdown.SpanKind
import dev.folio.core.text.markdown.inlineTexts

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

/**
 * Offset into the source of the hit block of the caret for a tap at [xPt] on the hit line: the character nearest the
 * tap, with markers and link targets that the rendered text leaves out skipped (`SourceOffsets`). The end of the
 * source for a tap past the last block and for blocks that show no text of their own.
 */
fun FlowLayout.sourceOffsetAt(
    hit: BlockHit,
    xPt: Float,
): Int {
    val block = markdown.blocks.getOrNull(hit.blockIndex) ?: return 0
    val layout = layoutOf(hit.blockIndex)
    val visible = SourceOffsets.visibleText(block.content)
    val table = layout?.table
    return if (table != null && !hit.pastEnd) {
        // A table: the cell under the tap, then the character in its text.
        val x = xPt - slots[hit.slot].rect.left
        val row = table.rowOfLine(hit.line)
        val column = table.columnAt(x)
        val index = table.cellIndex(row, column)
        val span = TableEditing.spanOf(block.source, TableCell(row, column))
        val plain = table.cellOffsetAt(index, x, hit.line)
        SourceOffsets.fromCellPlain(block.source, span.start, span.end, table.cellText(index), plain)
    } else if (layout == null || visible == null || hit.pastEnd) {
        block.source.length
    } else {
        // A callout lays out its title and a line break before the text.
        val title = layout.plainText.length - visible.length
        val plain = layout.plainOffsetAt(xPt - slots[hit.slot].rect.left, hit.line) - title
        SourceOffsets.fromPlain(block, plain.coerceAtLeast(0))
    }
}

/** Something on the page that a tap acts on instead of placing the caret (07-text-engine.md#block-styles). */
sealed interface TapTarget {
    /** The checkbox of the task item at [blockIndex]. */
    data class Checkbox(
        val blockIndex: Int,
    ) : TapTarget

    /** A web or other URL link to [url]. */
    data class Url(
        val url: String,
    ) : TapTarget

    /** A note link to document [docId] (`folio://doc/`), or by [title] only for `[[Title]]` (then [docId] is null). */
    data class Note(
        val docId: String?,
        val title: String,
    ) : TapTarget
}

/**
 * What a tap at [xPt] on the line of [hit] acts on: the checkbox of a task item (anywhere in its marker gutter on the
 * first line) or the link drawn under the point; null for plain text, tables and taps past the end.
 */
fun FlowLayout.tapTargetAt(
    hit: BlockHit,
    xPt: Float,
): TapTarget? {
    val block = markdown.blocks.getOrNull(hit.blockIndex)
    val layout = layoutOf(hit.blockIndex)?.takeIf { it.table == null && !hit.pastEnd }
    if (block == null || layout == null) return null
    val x = xPt - slots[hit.slot].rect.left
    val inGutter = hit.line == 0 && x >= layout.markerLeftPt && x < layout.textLeftPt
    if (inGutter && layout.decoration is BlockDecoration.Checkbox) return TapTarget.Checkbox(hit.blockIndex)
    val inline = block.content.inlineTexts().singleOrNull() ?: return null
    // A callout lays out its title and a line break before the text.
    val at = layout.plainCharAt(x, hit.line) - (layout.plainText.length - inline.plain.length)
    val span = inline.spans.lastOrNull { it.isLink && at in it.plainStart until it.plainEnd }
    return when {
        span == null -> null
        span.kind == SpanKind.LINK -> span.target?.takeIf { it.isNotBlank() }?.let(TapTarget::Url)
        else -> TapTarget.Note(span.docId, inline.plain.substring(span.plainStart, span.plainEnd))
    }
}

private val InlineSpan.isLink: Boolean get() = kind == SpanKind.LINK || kind == SpanKind.DOC_LINK

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
