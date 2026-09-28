package dev.folio.core.model.geometry

import kotlin.math.floor

/**
 * Spatial index over axis-aligned bounds, one per page: a uniform grid of [cellPt] cells.
 * Items whose bounds cover more than [maxCellsPerItem] cells (page-sized frames, huge shapes on
 * infinite pages) go to an overflow list that every query checks. Not thread-safe: owned by one
 * session/render thread. Used for hit testing, lasso candidates and tile invalidation.
 */
class UniformGridIndex<T : Any>(
    private val cellPt: Float = DEFAULT_CELL_PT,
    private val maxCellsPerItem: Int = DEFAULT_MAX_CELLS,
) {
    private val boundsById = HashMap<T, RectPt>()
    private val cells = HashMap<Long, MutableSet<T>>()
    private val oversized = LinkedHashSet<T>()

    /** Number of indexed items. */
    val size: Int get() = boundsById.size

    /** Bounds stored for [id], or null. */
    operator fun get(id: T): RectPt? = boundsById[id]

    /** Adds [id]; replaces its bounds if it is already present. Empty bounds are stored but never match. */
    fun insert(
        id: T,
        bounds: RectPt,
    ) {
        if (boundsById.containsKey(id)) remove(id)
        boundsById[id] = bounds
        if (bounds.isEmpty) return
        val c0 = cell(bounds.left)
        val c1 = cell(bounds.right)
        val r0 = cell(bounds.top)
        val r1 = cell(bounds.bottom)
        if (cellCount(c0, c1, r0, r1) > maxCellsPerItem) {
            oversized += id
            return
        }
        for (r in r0..r1) {
            for (c in c0..c1) {
                cells.getOrPut(key(c, r)) { HashSet() } += id
            }
        }
    }

    /** Removes [id]; returns false if it was not indexed. */
    fun remove(id: T): Boolean {
        val bounds = boundsById.remove(id) ?: return false
        if (!bounds.isEmpty && !oversized.remove(id)) removeFromCells(id, bounds)
        return true
    }

    private fun removeFromCells(
        id: T,
        bounds: RectPt,
    ) {
        for (r in cell(bounds.top)..cell(bounds.bottom)) {
            for (c in cell(bounds.left)..cell(bounds.right)) {
                val k = key(c, r)
                val set = cells[k] ?: continue
                set -= id
                if (set.isEmpty()) cells.remove(k)
            }
        }
    }

    /** Moves [id] to new [bounds] (inserts it if absent). */
    fun update(
        id: T,
        bounds: RectPt,
    ) = insert(id, bounds)

    /** Removes everything. */
    fun clear() {
        boundsById.clear()
        cells.clear()
        oversized.clear()
    }

    /** Items whose bounds intersect [rect] (touching counts), each once, in no particular order. */
    fun query(rect: RectPt): List<T> {
        if (rect.isEmpty) return emptyList()
        val out = LinkedHashSet<T>()
        for (id in oversized) if (boundsById.getValue(id).intersects(rect)) out += id
        val c0 = cell(rect.left)
        val c1 = cell(rect.right)
        val r0 = cell(rect.top)
        val r1 = cell(rect.bottom)
        if (cellCount(c0, c1, r0, r1) > cells.size) {
            // The query spans more cells than are occupied: walk the occupied cells instead.
            for (set in cells.values) collect(set, rect, out)
        } else {
            for (r in r0..r1) {
                for (c in c0..c1) collect(cells[key(c, r)] ?: continue, rect, out)
            }
        }
        return out.toList()
    }

    /** Items whose bounds lie within [radiusPt] of [center] (distance to the nearest bounds point). */
    fun queryRadius(
        center: PointPt,
        radiusPt: Float,
    ): List<T> {
        val box = RectPt(center.x - radiusPt, center.y - radiusPt, center.x + radiusPt, center.y + radiusPt)
        return query(box).filter { boundsById.getValue(it).distanceTo(center.x, center.y) <= radiusPt }
    }

    private fun collect(
        set: Set<T>,
        rect: RectPt,
        out: MutableSet<T>,
    ) {
        for (id in set) if (id !in out && boundsById.getValue(id).intersects(rect)) out += id
    }

    private fun cell(vPt: Float): Int = floor(vPt / cellPt).toInt()

    /** Defaults from 03-document-model.md#geometry. */
    companion object {
        /** Cell size in pt. */
        const val DEFAULT_CELL_PT = 128f

        /** Items covering more cells than this go to the overflow list. */
        const val DEFAULT_MAX_CELLS = 256
    }
}

private const val LOW_32_BITS = 0xFFFF_FFFFL

// Double math: bounds far out on infinite pages clamp to Int.MIN/MAX cells and would overflow Int.
private fun cellCount(
    c0: Int,
    c1: Int,
    r0: Int,
    r1: Int,
): Double = (c1.toDouble() - c0 + 1) * (r1.toDouble() - r0 + 1)

private fun key(
    c: Int,
    r: Int,
): Long = (c.toLong() shl Int.SIZE_BITS) or (r.toLong() and LOW_32_BITS)
