package dev.folio.core.model.edit

import dev.folio.core.model.Attachment
import dev.folio.core.model.Background
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.ImageObject
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Orientation
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.Shape
import dev.folio.core.model.StickyNote
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toPersistentList

/**
 * Builders of the page-level commands behind the editor's page panel (10-editor-ui.md#pages). Each result is one
 * undo step. Ids come from [newId] so tests can pin them.
 */
object PageOps {
    /** Where an inserted page goes relative to its anchor page. */
    enum class Side { BEFORE, AFTER }

    /**
     * An empty page next to [anchor] with the anchor's spec and background. A PDF page has neither a template
     * nor a fixed size worth copying: the new page takes the document's new-page defaults (page settings "Apply
     * to: new pages"), or A4 portrait on [fallback] (blank lined by default) where those are infinite or PDF-backed.
     */
    fun blankNear(
        doc: Document,
        anchor: PageId,
        side: Side,
        fallback: Background,
        newId: () -> PageId = PageId::random,
    ): InsertPages {
        val index = doc.indexOfPage(anchor)
        require(index >= 0) { "unknown page ${anchor.value}" }
        val ref = doc.pages[index]
        val page =
            if (ref.background.pdf == null) {
                Page(newId(), ref.spec, ref.background, persistentListOf())
            } else {
                val spec =
                    doc.meta.defaultPageSpec.takeIf { it !is PageSpec.Infinite } ?: PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)
                val background = doc.meta.defaultBackground.takeIf { it.pdf == null } ?: fallback
                Page(newId(), spec, background, persistentListOf())
            }
        return InsertPages(if (side == Side.BEFORE) index else index + 1, listOf(page))
    }

    /** Copies of [ids] (bodies loaded), each inserted right after the last selected page, in document order. */
    fun duplicate(
        doc: Document,
        ids: Collection<PageId>,
        newPageId: () -> PageId = PageId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): InsertPages {
        val wanted = ids.toHashSet()
        val sources = doc.pages.filter { it.id in wanted }
        require(sources.isNotEmpty() && sources.size == wanted.size) { "unknown page id" }
        val copies = sources.map { copyOf(doc.requireBody(it.id), newPageId(), newObjectId) }
        return InsertPages(doc.indexOfPage(sources.last().id) + 1, copies)
    }

    /** Removes [ids] (bodies loaded); null if that would leave the document without pages. */
    fun delete(
        doc: Document,
        ids: Collection<PageId>,
    ): RemovePages? {
        val wanted = ids.toHashSet()
        val known = doc.pages.count { it.id in wanted }
        require(known == wanted.size) { "unknown page id" }
        return if (known == 0 || known == doc.pages.size) null else RemovePages(doc.pages.filter { it.id in wanted }.map { it.id })
    }

    /**
     * [page] under [id] with fresh object ids. Text frames and sticky notes stay behind: their flows are shared
     * document state and cloning them arrives with the text engine (P06).
     */
    fun copyOf(
        page: Page,
        id: PageId,
        newObjectId: () -> ObjectId = ObjectId::random,
    ): Page {
        val objects: List<PageObject> =
            page.objects.mapNotNull {
                when (it) {
                    is InkStroke -> it.copy(id = newObjectId())
                    is Shape -> it.copy(id = newObjectId())
                    is ImageObject -> it.copy(id = newObjectId())
                    is Attachment -> it.copy(id = newObjectId())
                    is FlowFrame, is StickyNote -> null
                }
            }
        return page.copy(id = id, objects = objects.toPersistentList())
    }

    /**
     * Gives every page of [ids] (bodies loaded) the spec and background returned by [spec] and [background];
     * null if nothing would change.
     */
    fun restyle(
        doc: Document,
        ids: Collection<PageId>,
        spec: (PageRef) -> PageSpec,
        background: (PageRef) -> Background,
    ): EditCommand? {
        val commands = ArrayList<EditCommand>(ids.size * 2)
        for (id in ids) {
            val ref = checkNotNull(doc.pageRef(id)) { "unknown page ${id.value}" }
            val newSpec = spec(ref)
            val newBackground = background(ref)
            if (newSpec != ref.spec) commands += UpdatePageSpec(id, newSpec)
            if (newBackground != ref.background) commands += UpdateBackground(id, newBackground)
        }
        return if (commands.isEmpty()) null else Batch(commands)
    }
}
