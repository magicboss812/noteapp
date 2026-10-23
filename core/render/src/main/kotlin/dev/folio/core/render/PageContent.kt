package dev.folio.core.render

import dev.folio.core.model.FlowFrame
import dev.folio.core.model.Page
import dev.folio.core.model.PageObject
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.geometry.UniformGridIndex
import dev.folio.core.text.flow.PageText
import java.util.concurrent.atomic.AtomicReferenceArray

/**
 * Immutable render snapshot of one decoded page body: the objects in z-order plus a spatial index of
 * their bounds, so a tile render touches only the objects under its rect (05-canvas-rendering.md#tiles).
 * Safe to share with render threads: the index is built once and only queried afterwards. Each object
 * has a [meshSlot] where painters cache derived data (ink meshes); [next] carries the slots of
 * unchanged objects over to the snapshot of an edited page. [text] is what the page's text frames show; a
 * frame is indexed by its bounds plus the ink bounds of its text, so text that hangs out of the frame is drawn.
 */
class PageContent private constructor(
    /** The page body this snapshot was built from. */
    val page: Page,
    private val slots: AtomicReferenceArray<Any?>,
    /** The text the page's [FlowFrame]s show. */
    val text: PageText,
) {
    private val index = UniformGridIndex<Int>()

    init {
        page.objects.forEachIndexed { i, o -> index.insert(i, indexedBounds(o)) }
    }

    private fun indexedBounds(o: PageObject): RectPt = if (o is FlowFrame) o.bounds.union(text.boundsOf(o.id)) else o.bounds

    /** Number of objects. */
    val size: Int get() = page.objects.size

    /** Object at z-index [i]. */
    operator fun get(i: Int): PageObject = page.objects[i]

    /** Z-indices (ascending, so bottom first) of the objects whose bounds intersect [rect]. */
    fun objectsIn(rect: RectPt): IntArray = index.query(rect).toIntArray().apply { sort() }

    /** True when no object's bounds (and no text of a frame) intersect [rect]. */
    fun isEmptyIn(rect: RectPt): Boolean = index.query(rect).isEmpty()

    /** Painter cache of object [i] (null until a painter stores one). */
    fun meshSlot(i: Int): Any? = slots.get(i)

    /** Stores painter data for object [i]; racing writers store equivalent values, the last one wins. */
    fun setMeshSlot(
        i: Int,
        value: Any?,
    ) = slots.set(i, value)

    /** Snapshot of [next] showing [nextText], keeping cached slots of objects that are the same instances as here. */
    fun next(
        next: Page,
        nextText: PageText = text,
    ): PageContent {
        if (next === page && nextText === text) return this
        val old = HashMap<String, Int>(size * 2)
        page.objects.forEachIndexed { i, o -> old[o.id.value] = i }
        val carried = AtomicReferenceArray<Any?>(next.objects.size)
        next.objects.forEachIndexed { i, o ->
            val j = old[o.id.value] ?: return@forEachIndexed
            if (page.objects[j] === o) carried.set(i, slots.get(j))
        }
        return PageContent(next, carried, nextText)
    }

    /** Factories and diffing. */
    companion object {
        /** Snapshot of [page] with empty painter caches, showing [text]. */
        fun of(
            page: Page,
            text: PageText = PageText.EMPTY,
        ): PageContent = PageContent(page, AtomicReferenceArray(page.objects.size), text)

        /** Page-space rects whose pixels may differ between [before] and [after]: changed objects and changed text runs. */
        fun changedBounds(
            before: PageContent,
            after: PageContent,
        ): List<RectPt> = changedBounds(before.page, after.page) + PageText.changedBounds(before.text, after.text)

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
