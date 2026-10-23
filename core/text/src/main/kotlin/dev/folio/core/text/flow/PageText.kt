package dev.folio.core.text.flow

import dev.folio.core.model.ObjectId
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.layout.BlockLayout

/**
 * Lines [firstLine] until [firstLine] + [lineCount] of [layout] as they sit on a page (07-text-engine.md#rendering).
 * [topPt] is the page y of the block's first line box as if the whole block were there, so line n is at
 * `topPt + layout.lineTopPt(n)`; [leftPt] and [widthPt] are the frame's. Equality compares the layout by identity:
 * the layout cache hands out the same instance for the same block, width, style and grid, so two runs are equal
 * exactly when painting them gives the same pixels.
 */
data class TextRun(
    val frameId: ObjectId,
    val layout: BlockLayout,
    val firstLine: Int,
    val lineCount: Int,
    val leftPt: Float,
    val topPt: Float,
    val widthPt: Float,
    /** Background of inline code spans and code blocks (palette color), ARGB. */
    val codeBackgroundArgb: Int,
) {
    /** Top of the first line box shown. */
    val sliceTopPt: Float get() = topPt + layout.lineTopPt(firstLine)

    /** Bottom of the last line box shown. */
    val sliceBottomPt: Float get() = topPt + layout.lineTopPt(firstLine + lineCount)

    /** True when only some lines of the block are here (the rest continue in another frame); the painter clips. */
    val partial: Boolean get() = firstLine > 0 || lineCount < layout.lineCount

    /** Descender and italic allowance around the line boxes: a share of one line box (text hangs below its rule). */
    val overhangPt: Float get() = OVERHANG_BOXES * layout.boxUnits * layout.unitPt

    /** Where the run can put ink: the line boxes, the frame width, and the overhang of descenders and slanted glyphs. */
    val bounds: RectPt = overhangPt.let { RectPt(leftPt - it, sliceTopPt - it, leftPt + widthPt + it, sliceBottomPt + it) }

    private companion object {
        const val OVERHANG_BOXES = 0.3f
    }
}

/**
 * The text a page shows, ready to paint (05-canvas-rendering.md#tiles): runs per frame in page coordinates. Built from
 * flow layouts by [PageTexts]; immutable, so render threads share it. [EMPTY] is a page without text.
 */
class PageText private constructor(
    /** All runs, frame by frame. */
    val runs: List<TextRun>,
) {
    private val byFrame: Map<ObjectId, List<TextRun>> = runs.groupBy { it.frameId }

    /** True when the page shows no text. */
    val isEmpty: Boolean get() = runs.isEmpty()

    /** Runs painted inside frame [frameId], top to bottom. */
    fun runsOf(frameId: ObjectId): List<TextRun> = byFrame[frameId].orEmpty()

    /** Union of the ink bounds of the runs in frame [frameId] (empty when there are none). */
    fun boundsOf(frameId: ObjectId): RectPt = runsOf(frameId).fold(RectPt.EMPTY) { acc, run -> acc.union(run.bounds) }

    /** True when no run can put ink inside [rect]. */
    fun isEmptyIn(rect: RectPt): Boolean {
        for (i in runs.indices) if (runs[i].bounds.intersects(rect)) return false
        return true
    }

    /** Companion. */
    companion object {
        /** A page without text. */
        val EMPTY = PageText(emptyList())

        /** Page text of [runs] (frame order is the caller's). */
        fun of(runs: List<TextRun>): PageText = if (runs.isEmpty()) EMPTY else PageText(runs)

        /**
         * Page-space rects whose pixels may differ between [old] and [new]: the bounds of every run that is not in both
         * (an edited block has a new layout, blocks below an inserted or removed line moved, a frame resize changed
         * widths). Runs that are the same layout at the same place are not listed, so an edit repaints its block and
         * the lines it shifted, nothing else.
         */
        fun changedBounds(
            old: PageText,
            new: PageText,
        ): List<RectPt> {
            if (old === new) return emptyList()
            val remaining = HashMap<TextRun, Int>(old.runs.size * 2)
            for (run in old.runs) remaining.merge(run, 1, Int::plus)
            val out = ArrayList<RectPt>()
            for (run in new.runs) {
                val left = remaining[run]
                if (left == null || left == 0) {
                    out += run.bounds
                } else {
                    remaining[run] = left - 1
                }
            }
            for (run in old.runs) {
                val left = remaining[run]
                if (left != null && left > 0) {
                    out += run.bounds
                    remaining[run] = left - 1
                }
            }
            return out
        }
    }
}
