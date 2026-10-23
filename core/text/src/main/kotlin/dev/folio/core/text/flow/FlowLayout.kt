package dev.folio.core.text.flow

import androidx.compose.ui.graphics.toArgb
import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextAlign
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.fonts.TextRole
import dev.folio.core.text.fonts.TextSizing
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.layout.FlowAlignment
import dev.folio.core.text.layout.FlowTextStyle
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument

/**
 * A frame a flow is laid out into: [rect] in the coordinates its text is drawn in (page pt; sticky-local for sticky
 * notes), [rect] top on a rule of [grid]. [capacityUnits] is the number of whole line boxes it holds
 * ([FlowDistributor.UNBOUNDED] on infinite pages and for auto-growing boxes). [frameId] is the `FlowFrame` or
 * `StickyNote` the slot belongs to.
 */
data class FrameSlot(
    val pageId: PageId,
    val frameId: ObjectId,
    val rect: RectPt,
    val grid: LayoutGrid,
    val capacityUnits: Int,
)

/**
 * A flow distributed over its frames (07-text-engine.md#rendering): per slot the [FlowSlice]s, top offsets in U of
 * that slot's grid. Blocks are laid out for the frame they start in; a continuation in a narrower frame keeps those
 * line breaks.
 */
class FlowLayout internal constructor(
    /** The slots used, given ones first, then any continuation slots auto-continue asked for. */
    val slots: List<FrameSlot>,
    val markdown: MarkdownDocument,
    private val layouts: Array<BlockLayout?>,
    val distribution: Distribution,
    /** Number of slots that came from auto-continue (the last ones in [slots]). */
    val continuationCount: Int,
    private val style: FlowTextStyle,
) {
    /** Slices of slot [frame], top to bottom. */
    fun slices(frame: Int): List<FlowSlice> = distribution.frames.getOrNull(frame).orEmpty()

    /**
     * Runs to paint for slot [frame] (07-text-engine.md#rendering): one per slice, positioned from the slot's top at
     * `topUnit` x U, minus the top of the slice's first line so a block continued from another frame starts mid-block.
     * The slices of block [skipBlock] are left out (the focused block, which its live field draws); when it is a table
     * and [skipCell] is a cell index, only that cell is left out of its runs.
     */
    fun runs(
        frame: Int,
        skipBlock: Int = NO_BLOCK,
        skipCell: Int = NO_CELL,
    ): List<TextRun> {
        val slot = slots.getOrNull(frame) ?: return emptyList()
        val slices = slices(frame)
        if (slices.isEmpty()) return emptyList()
        val codeBackground = style.palette.codeBackground.toArgb()
        val out = ArrayList<TextRun>(slices.size)
        for (slice in slices) {
            val layout = layouts[slice.blockIndex]
            val skipped = slice.blockIndex == skipBlock
            val hiddenCell = if (skipped && layout?.table != null) skipCell else NO_CELL
            if (layout != null && (!skipped || hiddenCell != NO_CELL)) {
                val first = firstLine(slice)
                val top = slot.rect.top + slice.topUnit * slot.grid.unitPt - layout.lineTopPt(first)
                out +=
                    TextRun(
                        slot.frameId,
                        layout,
                        first,
                        lineCount(slice),
                        slot.rect.left,
                        top,
                        slot.rect.widthPt,
                        codeBackground,
                        hiddenCell,
                    )
            }
        }
        return out
    }

    /** Layout of block [index], null when the block was not placed (overflow) or does not exist. */
    fun layoutOf(index: Int): BlockLayout? = layouts.getOrNull(index)

    /** First line of [slice] in its block's layout. */
    fun firstLine(slice: FlowSlice): Int = slice.fromUnit / boxUnits(slice)

    /** Number of lines of [slice]. */
    fun lineCount(slice: FlowSlice): Int = slice.heightUnits / boxUnits(slice)

    /** True when text is left over after the last slot. */
    val overflows: Boolean get() = distribution.overflows

    /** Indices of [slots] on page [pageId]. */
    fun slotsOn(pageId: PageId): List<Int> = slots.indices.filter { slots[it].pageId == pageId }

    /**
     * The Markdown of the blocks with at least one line on [pageId] (byte for byte as in the flow, separators
     * between them kept, none after the last), or null when the flow shows nothing there. Page duplicates use it.
     */
    fun markdownOn(pageId: PageId): String? {
        val indices =
            slotsOn(pageId)
                .flatMap { slices(it) }
                .map { it.blockIndex }
                .distinct()
                .sorted()
        if (indices.isEmpty()) return null
        val blocks = markdown.blocks
        return buildString {
            for ((i, index) in indices.withIndex()) {
                append(blocks[index].source)
                if (i < indices.lastIndex) append(blocks[index].gap)
            }
        }
    }

    private fun boxUnits(slice: FlowSlice): Int = layouts[slice.blockIndex]?.boxUnits ?: 1

    /** Companion. */
    companion object {
        /** [runs] argument for "skip nothing". */
        const val NO_BLOCK = -1

        /** [runs] argument for "no table cell". */
        const val NO_CELL = -1
    }
}

/**
 * Lays a flow's blocks out with [blockLayouter] and distributes them over frame slots. Runs on the `text`
 * dispatcher (or the caller's thread for the focused flow); traced as `text:flow`.
 */
class FlowLayouter(
    private val blockLayouter: BlockLayouter,
) {
    /**
     * [markdown] distributed over [slots] in order. When they run out, [continuation] is asked for one more slot
     * after the given last one (auto-continue); null ends the flow with an overflow. At most [maxContinuations]
     * slots are added.
     */
    fun layout(
        markdown: MarkdownDocument,
        style: FlowTextStyle,
        paragraphGapLines: Int,
        slots: List<FrameSlot>,
        continuation: ((last: FrameSlot) -> FrameSlot?)? = null,
        maxContinuations: Int = MAX_CONTINUATIONS,
    ): FlowLayout =
        PerfMonitor.trace(TRACE_FLOW) {
            val all = ArrayList(slots)
            var added = 0
            val blocks = markdown.blocks
            val layouts = arrayOfNulls<BlockLayout>(blocks.size)
            val gapUnits = paragraphGapLines.coerceIn(0, 1) * bodyBoxUnits(style)

            fun layoutFor(
                block: Int,
                frame: Int,
            ): BlockLayout {
                val slot = all[frame]
                return blockLayouter.layout(blocks[block], slot.rect.widthPt, style, slot.grid)
            }
            val distribution =
                FlowDistributor.distribute(
                    blocks.size,
                    gapUnits,
                    capacity = { frame ->
                        if (frame >= all.size && added < maxContinuations) {
                            all.lastOrNull()?.let { continuation?.invoke(it) }?.let {
                                all += it
                                added++
                            }
                        }
                        all.getOrNull(frame)?.capacityUnits
                    },
                    item = { block, frame -> itemOf(blocks[block].content, layoutFor(block, frame)) },
                )
            for (block in blocks.indices) {
                val start = distribution.startFrames[block]
                if (start >= 0) layouts[block] = layoutFor(block, start)
            }
            FlowLayout(all, markdown, layouts, distribution, added, style)
        }

    companion object {
        /** Safety limit of continuation pages per layout pass. */
        const val MAX_CONTINUATIONS = 2000

        private const val TRACE_FLOW = "text:flow"

        // Header and two body rows: the least a table needs to break (never right after the header).
        private const val MIN_SPLIT_TABLE_ROWS = 3

        /**
         * The block's distribution needs: headings stay whole and keep with the next block, math and rules stay whole,
         * tables split between body rows.
         */
        fun itemOf(
            content: BlockContent,
            layout: BlockLayout,
        ): FlowItem =
            when (content) {
                is BlockContent.Heading -> {
                    FlowItem.whole(layout.heightUnits, keepWithNext = true)
                }

                is BlockContent.Math, BlockContent.Rule, BlockContent.Empty -> {
                    FlowItem.whole(layout.heightUnits)
                }

                // A table breaks between body rows only; the header stays with the first row.
                is BlockContent.Table -> {
                    val table = layout.table
                    if (table == null || table.rows < MIN_SPLIT_TABLE_ROWS) {
                        FlowItem.whole(layout.heightUnits)
                    } else {
                        FlowItem(layout.heightUnits, IntArray(table.rows - 2) { table.rowFirstLine(it + 2) * layout.boxUnits })
                    }
                }

                is BlockContent.Paragraph, is BlockContent.ListItem, is BlockContent.Quote, is BlockContent.Code -> {
                    FlowItem.lines(layout.lineCount, layout.boxUnits)
                }
            }

        /** Height in U of one empty body line (the paragraph gap). */
        fun bodyBoxUnits(style: FlowTextStyle): Int =
            TextSizing.boxUnits(TextRole.BODY, TextSizing.capFraction(TextRole.BODY, style.bodyCap))
    }
}

/** The layout style of a stored flow style. */
fun FlowStyle.toTextStyle(): FlowTextStyle =
    FlowTextStyle(
        familyName = fontFamily,
        bodyCap = sizeRatio,
        alignment =
            when (align) {
                TextAlign.START -> FlowAlignment.START
                TextAlign.CENTER -> FlowAlignment.CENTER
                TextAlign.JUSTIFIED -> FlowAlignment.JUSTIFIED
            },
    )
