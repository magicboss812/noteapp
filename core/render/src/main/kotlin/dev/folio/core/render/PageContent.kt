package dev.folio.core.render

import dev.folio.core.model.Page
import dev.folio.core.model.PageObject
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.geometry.UniformGridIndex
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Immutable render snapshot of one decoded page body: the objects in z-order plus a spatial index of
 * their bounds, so a tile render touches only the objects under its rect (05-canvas-rendering.md#tiles).
 * Safe to share with render threads: the index is built once and only queried afterwards. Each object
 * has a [meshSlot] where painters cache derived data (ink meshes); [next] carries the slots of
 * unchanged objects over to the snapshot of an edited page.
 */
class PageContent private constructor(
    /** The page body this snapshot was built from. */
    val page: Page,
    private val slots: AtomicReferenceArray<Any?>,
) {
    private val index = UniformGridIndex<Int>()

    init {
        page.objects.forEachIndexed { i, o -> index.insert(i, o.bounds) }
    }

    /** Number of objects. */
    val size: Int get() = page.objects.size

    /** Object at z-index [i]. */
    operator fun get(i: Int): PageObject = page.objects[i]

    /** Z-indices (ascending, so bottom first) of the objects whose bounds intersect [rect]. */
    fun objectsIn(rect: RectPt): IntArray = index.query(rect).toIntArray().apply { sort() }

    /** True when no object's bounds intersect [rect]. */
    fun isEmptyIn(rect: RectPt): Boolean = index.query(rect).isEmpty()

    /** Painter cache of object [i] (null until a painter stores one). */
    fun meshSlot(i: Int): Any? = slots.get(i)

    /** Stores painter data for object [i]; racing writers store equivalent values, the last one wins. */
    fun setMeshSlot(
        i: Int,
        value: Any?,
    ) = slots.set(i, value)

    /** Snapshot of [next], keeping cached slots of objects that are the same instances as here. */
    fun next(next: Page): PageContent {
        if (next === page) return this
        val old = HashMap<String, Int>(size * 2)
        page.objects.forEachIndexed { i, o -> old[o.id.value] = i }
        val carried = AtomicReferenceArray<Any?>(next.objects.size)
        next.objects.forEachIndexed { i, o ->
            val j = old[o.id.value] ?: return@forEachIndexed
            if (page.objects[j] === o) carried.set(i, slots.get(j))
        }
        return PageContent(next, carried)
    }

    /** Factories and diffing. */
    companion object {
        /** Snapshot of [page] with empty painter caches. */
        fun of(page: Page): PageContent = PageContent(page, AtomicReferenceArray(page.objects.size))

        /**
         * Page-space rects whose pixels may differ between [old] and [new]: bounds of removed, added and
         * replaced objects, plus both positions of unchanged objects whose relative z-order changed.
         */
        fun changedBounds(
            old: Page,
            new: Page,
        ): List<RectPt> {
            if (old.objects === new.objects) return emptyList()
            val oldById = old.objects.associateBy { it.id.value }
            val newById = new.objects.associateBy { it.id.value }
            val out = ArrayList<RectPt>()
            for (o in old.objects) if (newById[o.id.value] !== o) out += o.bounds
            for (o in new.objects) if (oldById[o.id.value] !== o) out += o.bounds
            // Same instances in both lists: a z-order change repaints where the order differs.
            val keptOld = old.objects.filter { newById[it.id.value] === it }
            val keptNew = new.objects.filter { oldById[it.id.value] === it }
            for (i in keptOld.indices) {
                if (keptOld[i] !== keptNew[i]) {
                    out += keptOld[i].bounds
                    out += keptNew[i].bounds
                }
            }
            return out
        }
    }
}
