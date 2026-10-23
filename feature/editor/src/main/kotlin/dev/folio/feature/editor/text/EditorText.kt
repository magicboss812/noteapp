package dev.folio.feature.editor.text

import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.TextFlow
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.EditFlow
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.storage.session.DocumentSession
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.FlowSearch
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.PageTexts
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flow layouts of one document version and what each page paints from them. */
class TextSnapshot(
    /** Layout of every flow that has frames, by flow. */
    val layouts: Map<FlowId, FlowLayout>,
    /** Page text of every page that shows text; other pages are absent. */
    val pages: Map<PageId, PageText>,
) {
    /** Constants. */
    companion object {
        /** No flow shows text. */
        val EMPTY = TextSnapshot(emptyMap(), emptyMap())
    }
}

/**
 * The text engine of one open document (07-text-engine.md#editing, D-032): keeps the layout of every flow current on
 * the `text` dispatcher, publishes it as [snapshot] for the canvas and the thumbnails, and turns flow edits into one
 * command with the page changes their reflow needs.
 *
 * Pages with a text frame stay decoded in the session, but which pages those are is only known once they are
 * decoded: the first pass loads every page of a document that has flows (a note with text is small), and nothing is
 * published before it finishes ([awaitReady]).
 */
class EditorText internal constructor(
    private val documentSession: DocumentSession,
    private val engine: TextEngine,
    private val dispatchers: FolioDispatchers,
    scope: CoroutineScope,
) {
    private val parses = FlowParses()
    private val mutableSnapshot = MutableStateFlow(TextSnapshot.EMPTY)
    private val ready = CompletableDeferred<Unit>()
    private var discovered = false
    private var inputs: LayoutInputs? = null

    /** The latest layouts and page texts; [TextSnapshot.EMPTY] until [awaitReady]. */
    val snapshot: StateFlow<TextSnapshot> = mutableSnapshot.asStateFlow()

    init {
        scope.launch(dispatchers.text) { documentSession.document.collect { refresh(it) } }
    }

    /** Returns once every page with a frame is decoded and [snapshot] shows the first layout. */
    suspend fun awaitReady() = ready.await()

    private suspend fun refresh(seen: Document) {
        if (!discovered) {
            discovered = true
            discover(seen)
        }
        val doc = documentSession.document.value
        val next = LayoutInputs.of(doc)
        if (next != inputs) {
            inputs = next
            val layouts = PageTexts.layouts(doc, engine.layouter, parses::of)
            val old = mutableSnapshot.value.pages
            // A page whose runs did not change keeps its instance, so the tiles and the thumbnails see no change.
            val pages =
                PageTexts.of(doc, layouts).mapValues { (id, text) ->
                    old[id]?.takeIf { PageText.changedBounds(it, text).isEmpty() }
                        ?: text
                }
            mutableSnapshot.value = TextSnapshot(layouts, pages)
        }
        ready.complete(Unit)
    }

    // Loads the pages that are not decoded yet, a few at a time, so a frame on any page takes part in the layout.
    private suspend fun discover(doc: Document) {
        if (doc.flows.isEmpty()) return
        val missing = doc.pages.map { it.id }.filter { doc.page(it) == null }
        for (chunk in missing.chunked(DISCOVER_CHUNK)) {
            val result = withContext(dispatchers.io) { documentSession.loadPages(chunk) }
            if (result is Outcome.Failure) FolioLog.w(TAG, "loadPages: ${result.message}", result.cause)
        }
    }

    /**
     * [edits] to flow [flowId] as one command: the text edit and the reflow of its pages (continuation pages, pages
     * left empty, grown boxes) execute and undo together. Null if the edit does not fit the flow.
     */
    suspend fun editCommand(
        flowId: FlowId,
        edits: List<TextEdit>,
        coalesceKey: String? = null,
    ): EditCommand? {
        val edit = EditFlow(flowId, edits, coalesceKey)
        val doc = documentSession.document.value
        return withContext(dispatchers.text) {
            try {
                val after = edit.execute(doc).doc
                val flow = checkNotNull(after.flows[flowId])
                val reflow = engine.reflow.plan(after, flowId, parses.of(flow))
                reflow.command?.let { Batch(listOf(edit, it), coalesceKey) } ?: edit
            } catch (e: IllegalArgumentException) {
                FolioLog.w(TAG, "flow edit rejected: ${e.message}")
                null
            } catch (e: IllegalStateException) {
                FolioLog.w(TAG, "flow edit rejected: ${e.message}")
                null
            }
        }
    }

    /** Markdown of the flow text shown on a page of [doc] (page duplicates copy it), laid out fresh for [doc]. */
    suspend fun pageMarkdown(doc: Document): (FlowId, PageId) -> String? {
        val layouts = withContext(dispatchers.text) { PageTexts.layouts(doc, engine.layouter, parses::of) }
        return { flowId, pageId -> layouts[flowId]?.markdownOn(pageId) }
    }

    /** The page of [doc] with the first match of the library search [query], or null for a miss. */
    suspend fun firstMatchPage(query: String): PageId? {
        awaitReady()
        val layouts =
            snapshot.value.layouts.values
                .toList()
        return FlowSearch.firstPage(documentSession.document.value, layouts, query)
    }

    // What decides the layout: the flows, the page list's specs and backgrounds, and the frames. Ink commits change
    // none of it, so a stroke never lays the text out again.
    private class LayoutInputs(
        val flows: Map<FlowId, TextFlow>,
        val pages: List<PageRef>,
        val frames: Map<PageId, List<FlowFrame>>,
    ) {
        override fun equals(other: Any?): Boolean =
            other is LayoutInputs && flows == other.flows && samePages(pages, other.pages) && frames == other.frames

        override fun hashCode(): Int = flows.hashCode()

        companion object {
            fun of(doc: Document): LayoutInputs {
                val frames = HashMap<PageId, List<FlowFrame>>()
                for ((id, page) in doc.pageBodies) {
                    val list = page.objects.filterIsInstance<FlowFrame>()
                    if (list.isNotEmpty()) frames[id] = list
                }
                return LayoutInputs(doc.flows, doc.pages, frames)
            }

            // Not PageRef equality: on infinite pages the content bounds follow the ink.
            private fun samePages(
                a: List<PageRef>,
                b: List<PageRef>,
            ): Boolean {
                if (a.size != b.size) return false
                for (i in a.indices) {
                    if (a[i].id != b[i].id || a[i].spec != b[i].spec || a[i].background != b[i].background) return false
                }
                return true
            }
        }
    }

    private companion object {
        const val TAG = "EditorText"
        const val DISCOVER_CHUNK = 8
    }
}
