package dev.folio.core.text.flow

/**
 * Vertical needs of one block for distribution (07-text-engine.md#flows-and-frames), in grid units U: its height
 * and the offsets where it may continue in the next frame (line boundaries, table rows; none for math, rules and
 * headings). [keepWithNext] blocks (headings) never end a frame when another block follows.
 */
class FlowItem(
    val heightUnits: Int,
    /** Ascending offsets strictly between 0 and [heightUnits]. */
    val breakUnits: IntArray,
    val keepWithNext: Boolean = false,
) {
    init {
        require(heightUnits >= 1) { "a block is at least one line box" }
    }

    internal fun firstAtomUnits(): Int = if (breakUnits.isEmpty()) heightUnits else breakUnits[0]

    /** The largest break in (from, limit], or -1. */
    internal fun lastBreakWithin(
        from: Int,
        limit: Int,
    ): Int {
        var best = -1
        for (b in breakUnits) {
            if (b > limit) break
            if (b > from) best = b
        }
        return best
    }

    internal fun firstBreakAfter(from: Int): Int = breakUnits.firstOrNull { it > from } ?: heightUnits

    companion object {
        /** [lineCount] line boxes of [boxUnits] each, splittable after every line. */
        fun lines(
            lineCount: Int,
            boxUnits: Int,
        ): FlowItem = FlowItem(lineCount * boxUnits, IntArray(lineCount - 1) { (it + 1) * boxUnits })

        /** A block that never splits. */
        fun whole(
            heightUnits: Int,
            keepWithNext: Boolean = false,
        ): FlowItem = FlowItem(heightUnits, IntArray(0), keepWithNext)
    }
}

/** Units [fromUnit] until [toUnit] of block [blockIndex], placed [topUnit] U below the top of its frame. */
data class FlowSlice(
    val blockIndex: Int,
    val fromUnit: Int,
    val toUnit: Int,
    val topUnit: Int,
) {
    val heightUnits: Int get() = toUnit - fromUnit
}

/**
 * Distribution result: [frames] holds the slices of every frame that was reached (frames after the last used one
 * are absent or empty). When the frames ran out, the first unplaced part is unit [overflowUnit] of block
 * [overflowBlock]; otherwise [overflowBlock] is -1. [startFrames] is the frame each block's first slice is in (-1
 * for unplaced blocks): the frame whose width and grid it was laid out for.
 */
class Distribution internal constructor(
    val frames: List<List<FlowSlice>>,
    val overflowBlock: Int,
    val overflowUnit: Int,
    val startFrames: IntArray,
) {
    val overflows: Boolean get() = overflowBlock >= 0

    /** Units used in [frame] including gaps (0 for frames that were not reached). */
    fun usedUnits(frame: Int): Int = frames.getOrNull(frame)?.lastOrNull()?.let { it.topUnit + it.heightUnits } ?: 0
}

/**
 * Places block line boxes sequentially into frames (07-text-engine.md#flows-and-frames): a block that does not fit
 * continues at its last fitting break in the next frame; an unsplittable block moves to the next frame whole; a
 * heading moves along when the first line of the next block would not fit after it. [gapUnits] separate blocks
 * inside a frame, never at a frame top. A frame that is still empty always takes at least one line box (an oversize
 * block hangs out of it), so distribution always ends.
 */
object FlowDistributor {
    /** Capacity of a frame without a bottom (infinite pages, auto-growing boxes). */
    const val UNBOUNDED: Int = Int.MAX_VALUE

    /**
     * [capacity] returns the capacity in U of frame n, or null when there is no frame n (auto-continue may create
     * one on demand). [item] returns block b laid out for frame f; it may be asked for the same block more than once.
     */
    fun distribute(
        blockCount: Int,
        gapUnits: Int,
        capacity: (frame: Int) -> Int?,
        item: (block: Int, frame: Int) -> FlowItem,
    ): Distribution {
        val startFrames = IntArray(blockCount) { -1 }
        val frames = ArrayList<List<FlowSlice>>()
        var frame = 0
        var cap = capacity(0) ?: return Distribution(frames, if (blockCount > 0) 0 else -1, 0, startFrames)
        var slices = ArrayList<FlowSlice>().also { frames += it }
        var used = 0
        var block = 0
        var from = 0
        while (block < blockCount) {
            val current = item(block, frame)
            val gap = if (from == 0 && used > 0) gapUnits else 0
            val free = cap - used - gap
            val end = endInFrame(current, block, blockCount, frame, from, used, free, gapUnits, item)
            if (end > from) {
                if (from == 0) startFrames[block] = frame
                slices += FlowSlice(block, from, end, used + gap)
                used += gap + end - from
                if (end == current.heightUnits) {
                    block++
                    from = 0
                    continue
                }
                from = end
            }
            frame++
            cap = capacity(frame) ?: return Distribution(frames, block, from, startFrames)
            slices = ArrayList<FlowSlice>().also { frames += it }
            used = 0
        }
        return Distribution(frames, -1, 0, startFrames)
    }

    /** Where the part of [current] starting at [from] ends in this frame; [from] itself means "next frame". */
    @Suppress("LongParameterList") // the loop state of distribute(), kept apart to stay readable
    private fun endInFrame(
        current: FlowItem,
        block: Int,
        blockCount: Int,
        frame: Int,
        from: Int,
        used: Int,
        free: Int,
        gapUnits: Int,
        item: (block: Int, frame: Int) -> FlowItem,
    ): Int {
        val rest = current.heightUnits - from
        if (rest <= free) {
            val keep = current.keepWithNext && used > 0 && block + 1 < blockCount
            val nextFits = !keep || gapUnits + item(block + 1, frame).firstAtomUnits() <= free - rest
            return if (nextFits) current.heightUnits else from
        }
        val fitting = if (free > 0) current.lastBreakWithin(from, from + free) else -1
        return when {
            fitting > from -> fitting
            used == 0 -> current.firstBreakAfter(from)
            else -> from
        }
    }
}
