package dev.folio.core.model.edit

import dev.folio.core.model.Attachment
import dev.folio.core.model.Background
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
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
import dev.folio.core.model.TextFlow
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

    /** Copied pages inserted at [atIndex] and the flows cloned for their text; [command] adds both as one undo step. */
    data class Duplicate(
        val atIndex: Int,
        val pages: List<Page>,
        val flows: List<TextFlow>,
    ) {
        /** Flows first, so no copied frame ever points at a missing flow. */
        val command: EditCommand
            get() = if (flows.isEmpty()) InsertPages(atIndex, pages) else Batch(listOf(UpdateFlows(flows), InsertPages(atIndex, pages)))
    }

    /**
     * Copies of [ids] (bodies loaded), each inserted right after the last selected page, in document order. Text
     * frames, boxes and sticky notes get new independent flows (07-text-engine.md#flows-and-frames): a box or sticky
     * flow is copied whole; a BODY frame becomes a fixed BOX frame with the same rect holding [pageText] (the body
     * flow's Markdown shown on that page, from the flow layout; the whole flow when null), so the document keeps one
     * body flow.
     */
    fun duplicate(
        doc: Document,
        ids: Collection<PageId>,
        newPageId: () -> PageId = PageId::random,
        newObjectId: () -> ObjectId = ObjectId::random,
        newFlowId: () -> FlowId = FlowId::random,
        pageText: (FlowId, PageId) -> String? = { _, _ -> null },
    ): Duplicate {
        val wanted = ids.toHashSet()
        val sources = doc.pages.filter { it.id in wanted }
        require(sources.isNotEmpty() && sources.size == wanted.size) { "unknown page id" }
        val flows = ArrayList<TextFlow>()
        val copies =
            sources.map {
                val copy = copyOf(doc, doc.requireBody(it.id), newPageId(), newObjectId, newFlowId, pageText)
                flows += copy.flows
                copy.page
            }
        return Duplicate(doc.indexOfPage(sources.last().id) + 1, copies, flows)
    }

    /** One copied page and the flows its text objects now use. */
    data class PageCopy(
        val page: Page,
        val flows: List<TextFlow>,
    )

    /**
     * [page] of [doc] under [id] with fresh object ids; text objects get cloned flows (see [duplicate]). Objects whose
     * flow is missing from [doc] are dropped.
     */
    fun copyOf(
        doc: Document,
        page: Page,
        id: PageId,
        newObjectId: () -> ObjectId = ObjectId::random,
        newFlowId: () -> FlowId = FlowId::random,
        pageText: (FlowId, PageId) -> String? = { _, _ -> null },
    ): PageCopy {
        val cloner = FlowCloner(doc, page.id, newFlowId, pageText)
        val objects = page.objects.mapNotNull { copyObject(it, newObjectId, cloner) }
        return PageCopy(page.copy(id = id, objects = objects.toPersistentList()), cloner.clones.values.toList())
    }

    private fun copyObject(
        obj: PageObject,
        newObjectId: () -> ObjectId,
        cloner: FlowCloner,
    ): PageObject? =
        when (obj) {
            is InkStroke -> {
                obj.copy(id = newObjectId())
            }

            is Shape -> {
                obj.copy(id = newObjectId())
            }

            is ImageObject -> {
                obj.copy(id = newObjectId())
            }

            is Attachment -> {
                obj.copy(id = newObjectId())
            }

            is FlowFrame -> {
                val body = obj.role == FrameRole.BODY
                cloner.clone(obj.flow, body)?.let { flow ->
                    if (body) {
                        obj.copy(id = newObjectId(), flow = flow, order = 0, role = FrameRole.BOX, autoGrow = false)
                    } else {
                        obj.copy(id = newObjectId(), flow = flow)
                    }
                }
            }

            is StickyNote -> {
                cloner.clone(obj.flow, body = false)?.let { flow -> obj.copy(id = newObjectId(), flow = flow) }
            }
        }

    /** Clones each flow of one page once; [clones] maps source flow to clone in first-use order. */
    private class FlowCloner(
        private val doc: Document,
        private val pageId: PageId,
        private val newFlowId: () -> FlowId,
        private val pageText: (FlowId, PageId) -> String?,
    ) {
        val clones = LinkedHashMap<FlowId, TextFlow>()

        /** Id of the clone of [flowId] (the page's own text for a [body] flow), or null when the flow is missing. */
        fun clone(
            flowId: FlowId,
            body: Boolean,
        ): FlowId? {
            val flow = doc.flows[flowId] ?: return null
            return clones
                .getOrPut(flowId) {
                    val markdown = (if (body) pageText(flowId, pageId) else null) ?: flow.markdown
                    flow.copy(id = newFlowId(), markdown = markdown, autoContinue = false)
                }.id
        }
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
