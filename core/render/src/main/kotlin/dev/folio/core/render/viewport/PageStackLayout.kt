package dev.folio.core.render.viewport

import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.geometry.RectPt

/**
 * Pages in one vertical column (05-canvas-rendering.md#viewport). Coordinates are stack space: pt at
 * zoom 1, origin at the top-left of the column. Gap and side padding are 16 dp / 24 dp at fit-width
 * scale and zoom with the pages. An infinite page is a card sized to its content bounds united with its
 * origin frame. Immutable; rebuild when pages or the view width change.
 */
class PageStackLayout(
    /** Pages in stack order. */
    val pages: List<PageRef>,
    viewWidthPx: Float,
    density: Float,
) {
    /** Scale (px per pt) at which the widest card plus side padding fills the view width. */
    val fitWidthScale: Float

    /** Gap between cards (and above the first / below the last), stack pt. */
    val gapPt: Float

    /** Width of the column including side padding, stack pt. */
    val contentWidthPt: Float

    /** Height of the column including the outer gaps, stack pt. */
    val contentHeightPt: Float

    private val frames: List<RectPt> = pages.map { it.cardFramePt() }
    private val cardTopsPt = FloatArray(pages.size)
    private val indexById: Map<PageId, Int> = pages.withIndex().associate { (i, p) -> p.id to i }

    init {
        require(pages.isNotEmpty()) { "stack needs at least one page" }
        val widestPt = frames.maxOf { it.widthPt }
        val usablePx = viewWidthPx - 2 * SIDE_PADDING_DP * density
        require(usablePx > 0f && widestPt > 0f) { "view too narrow ($viewWidthPx px) or empty page" }
        fitWidthScale = usablePx / widestPt
        gapPt = GAP_DP * density / fitWidthScale
        contentWidthPt = viewWidthPx / fitWidthScale
        var y = gapPt
        frames.forEachIndexed { i, frame ->
            cardTopsPt[i] = y
            y += frame.heightPt + gapPt
        }
        contentHeightPt = y
    }

    /** Number of pages. */
    val size: Int get() = pages.size

    /** Index of [id], or -1 if the page is not in the stack. */
    fun indexOf(id: PageId): Int = indexById[id] ?: -1

    /** Card rect of page [index] in stack space. */
    fun cardRectPt(index: Int): RectPt {
        val frame = frames[index]
        return RectPt.ofSize(cardLeftPt(index), cardTopsPt[index], frame.widthPt, frame.heightPt)
    }

    /** Stack-space left of card [index] (no allocation, unlike [cardRectPt]). */
    fun cardLeftPt(index: Int): Float = (contentWidthPt - frames[index].widthPt) / 2f

    /** Stack-space top of card [index]. */
    fun cardTopPt(index: Int): Float = cardTopsPt[index]

    /** Stack-space x of page [index]'s page-space origin. */
    fun originXPt(index: Int): Float = cardLeftPt(index) - frames[index].left

    /** Stack-space y of page [index]'s page-space origin. */
    fun originYPt(index: Int): Float = cardTopsPt[index] - frames[index].top

    /** Card frame of page [index] in its own page space. */
    fun framePt(index: Int): RectPt = frames[index]

    /** Indices of cards overlapping the stack-space band [topPt, bottomPt]; empty if none. */
    fun visibleRange(
        topPt: Float,
        bottomPt: Float,
    ): IntRange {
        val first = firstIndex { cardTopsPt[it] + frames[it].heightPt >= topPt }
        val last = firstIndex { cardTopsPt[it] > bottomPt } - 1
        return first..last
    }

    /** Smallest index where [predicate] (monotone false..true) holds, or [size] if none. */
    private inline fun firstIndex(predicate: (Int) -> Boolean): Int {
        var lo = 0
        var hi = pages.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (predicate(mid)) hi = mid else lo = mid + 1
        }
        return lo
    }

    private companion object {
        const val GAP_DP = 16f
        const val SIDE_PADDING_DP = 24f
    }
}

/** Card frame in page space: the page frame, grown by the content bounds for infinite pages. */
internal fun PageRef.cardFramePt(): RectPt {
    val origin = RectPt(0f, 0f, spec.widthPt, spec.heightPt)
    return if (spec is PageSpec.Infinite) origin.union(contentBounds ?: RectPt.EMPTY) else origin
}
