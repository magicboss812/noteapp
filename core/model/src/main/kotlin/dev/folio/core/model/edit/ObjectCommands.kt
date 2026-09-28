package dev.folio.core.model.edit

import dev.folio.core.model.Document
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.geometry.Affine
import kotlinx.collections.immutable.toPersistentList

/** An object with its z-index (0 = bottom). */
data class IndexedObject(
    val index: Int,
    val obj: PageObject,
)

/** Adds [objects] at [atIndex] (null = on top), in the given order. */
data class AddObjects(
    val pageId: PageId,
    val objects: List<PageObject>,
    val atIndex: Int? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val existing = page.objects.mapTo(HashSet()) { it.id }
        require(objects.none { it.id in existing }) { "object id already on page ${pageId.value}" }
        require(objects.distinctBy { it.id }.size == objects.size) { "duplicate object ids" }
        val index = (atIndex ?: page.objects.size).coerceIn(0, page.objects.size)
        val newPage = page.copy(objects = page.objects.addAll(index, objects))
        return Applied(doc.withBody(newPage), RemoveObjects(pageId, objects.map { it.id }), null)
    }
}

/** Removes objects by id; the inverse restores them at their z-indices. */
data class RemoveObjects(
    val pageId: PageId,
    val ids: List<ObjectId>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val removed = indexedObjects(page.objects, ids)
        val idSet = ids.toHashSet()
        val newPage = page.copy(objects = page.objects.removeAll { it.id in idSet })
        return Applied(doc.withBody(newPage), InsertObjectsAt(pageId, removed), null)
    }
}

/** Inserts objects at exact z-indices (ascending; each index is the final position). Inverse of removal. */
data class InsertObjectsAt(
    val pageId: PageId,
    val entries: List<IndexedObject>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val builder = page.objects.builder()
        for (entry in entries.sortedBy { it.index }) {
            require(entry.index in 0..builder.size) { "index ${entry.index} out of range" }
            builder.add(entry.index, entry.obj)
        }
        val newPage = page.copy(objects = builder.build())
        return Applied(doc.withBody(newPage), RemoveObjects(pageId, entries.map { it.obj.id }), null)
    }
}

/**
 * Removes [removed] and inserts [added] where the lowest removed object was (on top if nothing is
 * removed). Eraser splits and shape recognition use it.
 */
data class ReplaceObjects(
    val pageId: PageId,
    val removed: List<ObjectId>,
    val added: List<PageObject>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val entries = indexedObjects(page.objects, removed)
        val idSet = removed.toHashSet()
        val remaining = page.objects.removeAll { it.id in idSet }
        val remainingIds = remaining.mapTo(HashSet()) { it.id }
        require(added.none { it.id in remainingIds }) { "added object id already on page" }
        val at = entries.minOfOrNull { it.index } ?: remaining.size
        val newPage = page.copy(objects = remaining.addAll(at, added))
        val inverse = Batch(listOf(RemoveObjects(pageId, added.map { it.id }), InsertObjectsAt(pageId, entries)))
        return Applied(doc.withBody(newPage), inverse, null)
    }
}

/** Replaces objects in place (same id, same z-index). */
data class UpdateObjects(
    val pageId: PageId,
    val objects: List<PageObject>,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val byId = objects.associateBy { it.id }
        val old = ArrayList<PageObject>(objects.size)
        val newObjects =
            page.objects.map { o ->
                val replacement = byId[o.id]
                if (replacement == null) {
                    o
                } else {
                    old += o
                    replacement
                }
            }
        require(old.size == byId.size) { "unknown object id on page ${pageId.value}" }
        val newPage = page.copy(objects = newObjects.toPersistentList())
        return Applied(doc.withBody(newPage), UpdateObjects(pageId, old), coalesceKey)
    }
}

/** Moves, scales or rotates objects (lasso transform). The inverse restores the exact originals. */
data class TransformObjects(
    val pageId: PageId,
    val ids: List<ObjectId>,
    val affine: Affine,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val moved = indexedObjects(page.objects, ids).map { it.obj.transformed(affine) }
        return UpdateObjects(pageId, moved).execute(doc)
    }
}

/** Sets the color of objects that have one (strokes, shapes, sticky notes). */
data class RecolorObjects(
    val pageId: PageId,
    val ids: List<ObjectId>,
    val argb: Int,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val recolored = indexedObjects(page.objects, ids).map { it.obj.recolored(argb) }
        return UpdateObjects(pageId, recolored, coalesceKey).execute(doc)
    }
}

/** Z-order operations on a selection. */
enum class ReorderOp { BRING_TO_FRONT, SEND_TO_BACK, FORWARD, BACKWARD }

/** Changes the z-order of [ids] by [op]; relative order inside the selection is kept. */
data class ReorderObjects(
    val pageId: PageId,
    val ids: List<ObjectId>,
    val op: ReorderOp,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        indexedObjects(page.objects, ids)
        val order = reorder(page.objects.map { it.id }, ids.toHashSet(), op)
        return SetObjectOrder(pageId, order).execute(doc)
    }
}

/** Sets the complete z-order of a page ([order] must be a permutation of its object ids). */
data class SetObjectOrder(
    val pageId: PageId,
    val order: List<ObjectId>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val byId = page.objects.associateBy { it.id }
        require(order.size == byId.size && order.toHashSet() == byId.keys) { "order is not a permutation" }
        val newPage = page.copy(objects = order.map { byId.getValue(it) }.toPersistentList())
        return Applied(doc.withBody(newPage), SetObjectOrder(pageId, page.objects.map { it.id }), null)
    }
}

/** Objects with the given ids and their indices, ascending by index; every id must exist. */
private fun indexedObjects(
    objects: List<PageObject>,
    ids: List<ObjectId>,
): List<IndexedObject> {
    val idSet = ids.toHashSet()
    val out = ArrayList<IndexedObject>(idSet.size)
    objects.forEachIndexed { i, o -> if (o.id in idSet) out += IndexedObject(i, o) }
    require(out.size == idSet.size) { "unknown object id" }
    return out
}

private fun reorder(
    current: List<ObjectId>,
    selected: Set<ObjectId>,
    op: ReorderOp,
): List<ObjectId> =
    when (op) {
        ReorderOp.BRING_TO_FRONT -> {
            current.filter { it !in selected } + current.filter { it in selected }
        }

        ReorderOp.SEND_TO_BACK -> {
            current.filter { it in selected } + current.filter { it !in selected }
        }

        ReorderOp.FORWARD -> {
            val list = current.toMutableList()
            // From the top down: swap each selected object with the unselected one above it.
            for (i in list.size - 2 downTo 0) {
                if (list[i] in selected && list[i + 1] !in selected) {
                    list[i] = list[i + 1].also { list[i + 1] = list[i] }
                }
            }
            list
        }

        ReorderOp.BACKWARD -> {
            val list = current.toMutableList()
            for (i in 1 until list.size) {
                if (list[i] in selected && list[i - 1] !in selected) {
                    list[i] = list[i - 1].also { list[i - 1] = list[i] }
                }
            }
            list
        }
    }
