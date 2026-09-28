package dev.folio.core.model.edit

import dev.folio.core.model.Background
import dev.folio.core.model.Document
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import kotlinx.collections.immutable.mutate
import kotlinx.collections.immutable.toPersistentList

/** A page with its position in the page list. */
data class IndexedPage(
    val index: Int,
    val page: Page,
)

/** Inserts new pages (with bodies) at [atIndex], clamped to the page count. */
data class InsertPages(
    val atIndex: Int,
    val pages: List<Page>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val start = atIndex.coerceIn(0, doc.pages.size)
        return RestorePages(pages.mapIndexed { i, p -> IndexedPage(start + i, p) }).execute(doc)
    }
}

/** Inserts pages at exact indices (ascending; each index is the final position). Inverse of removal. */
data class RestorePages(
    val entries: List<IndexedPage>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val existing = doc.pages.mapTo(HashSet()) { it.id }
        val refs = doc.pages.builder()
        val bodies = doc.pageBodies.builder()
        for (entry in entries.sortedBy { it.index }) {
            require(existing.add(entry.page.id)) { "page ${entry.page.id.value} already exists" }
            require(entry.index in 0..refs.size) { "index ${entry.index} out of range" }
            refs.add(entry.index, entry.page.toRef())
            bodies[entry.page.id] = entry.page
        }
        val newDoc = doc.copy(pages = refs.build(), pageBodies = bodies.build())
        return Applied(newDoc, RemovePages(entries.map { it.page.id }), null)
    }
}

/** Removes pages (their bodies must be loaded so undo can restore them). */
data class RemovePages(
    val ids: List<PageId>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = ids.toSet()
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val idSet = ids.toHashSet()
        val removed = ArrayList<IndexedPage>(idSet.size)
        doc.pages.forEachIndexed { i, ref -> if (ref.id in idSet) removed += IndexedPage(i, doc.requireBody(ref.id)) }
        require(removed.size == idSet.size) { "unknown page id" }
        val newDoc =
            doc.copy(
                pages = doc.pages.removeAll { it.id in idSet },
                pageBodies = doc.pageBodies.mutate { m -> idSet.forEach { m.remove(it) } },
            )
        return Applied(newDoc, RestorePages(removed), null)
    }
}

/** Moves [ids] as one block (in their current relative order) to [toIndex] of the remaining pages. */
data class MovePages(
    val ids: List<PageId>,
    val toIndex: Int,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val idSet = ids.toHashSet()
        val order = doc.pages.map { it.id }
        val moving = order.filter { it in idSet }
        require(moving.size == idSet.size) { "unknown page id" }
        val rest = order.filter { it !in idSet }.toMutableList()
        rest.addAll(toIndex.coerceIn(0, rest.size), moving)
        return SetPageOrder(rest).execute(doc)
    }
}

/** Sets the complete page order ([order] must be a permutation of the page ids). */
data class SetPageOrder(
    val order: List<PageId>,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = emptySet()
    override val coalesceKey: String? get() = null

    override fun execute(doc: Document): Applied {
        val byId = doc.pages.associateBy { it.id }
        require(order.size == byId.size && order.toHashSet() == byId.keys) { "order is not a permutation" }
        val newDoc = doc.copy(pages = order.map { byId.getValue(it) }.toPersistentList())
        return Applied(newDoc, SetPageOrder(doc.pages.map { it.id }), null)
    }
}

/** Changes the page geometry (size, orientation, infinite). */
data class UpdatePageSpec(
    val pageId: PageId,
    val spec: PageSpec,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        return Applied(doc.withBody(page.copy(spec = spec)), UpdatePageSpec(pageId, page.spec, coalesceKey), coalesceKey)
    }
}

/** Changes paper color, template or PDF background of a page. */
data class UpdateBackground(
    val pageId: PageId,
    val background: Background,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = setOf(pageId)

    override fun execute(doc: Document): Applied {
        val page = doc.requireBody(pageId)
        val inverse = UpdateBackground(pageId, page.background, coalesceKey)
        return Applied(doc.withBody(page.copy(background = background)), inverse, coalesceKey)
    }
}
