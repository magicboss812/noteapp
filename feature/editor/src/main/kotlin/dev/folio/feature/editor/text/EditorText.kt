package dev.folio.feature.editor.text

import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.PerfMonitor
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
import dev.folio.core.text.edit.FlowTextDiff
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.FlowSearch
import dev.folio.core.text.flow.FocusedBlock
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.markdown.MarkdownDocument
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flow layouts of one document version and what each page paints from them. */
class TextSnapshot(
    /** Layout of every flow that has frames, by flow. */
    val layouts: Map<FlowId, FlowLayout>,
    /** Page text of every page that shows text; other pages are absent. Thumbnails paint these. */
    val pages: Map<PageId, PageText>,
    /** [pages] as the canvas paints them: without the focused block, which its live field draws. */
    val canvas: Map<PageId, PageText> = pages,
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
) : TextController {
    private val parses = FlowParses()
    private val mutableSnapshot = MutableStateFlow(TextSnapshot.EMPTY)
    private val mutableFocus = MutableStateFlow<TextFocus?>(null)
    private val ready = CompletableDeferred<Unit>()
    private var discovered = false
    private var inputs: LayoutInputs? = null
    private var shown: FocusedBlock? = null

    /** The latest layouts and page texts; [TextSnapshot.EMPTY] until [awaitReady]. */
    val snapshot: StateFlow<TextSnapshot> = mutableSnapshot.asStateFlow()

    /** The block being edited, null while the live field is not shown. */
    val focus: StateFlow<TextFocus?> = mutableFocus.asStateFlow()

    override val field: Flow<TextFieldSpec?> =
        combine(mutableFocus, mutableSnapshot) { focus, snapshot -> snapshot.fieldOf(focus) }
            .flowOn(dispatchers.text)

    init {
        scope.launch(dispatchers.text) {
            // Sequential: a focus change redraws the pages without the block, a document change lays out again.
            combine(documentSession.document, mutableFocus, ::Pair).collect { (seen, _) -> refresh(seen) }
        }
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
        val relaid = next != inputs
        val current = mutableSnapshot.value
        val layouts = if (relaid) PageTexts.layouts(doc, engine.layouter, parses::of) else current.layouts
        inputs = next
        // The focused block's id changes when undo or redo parses the text again: the block at the same place gets the focus.
        mutableFocus.update { it?.resolvedIn(layouts) }
        val focused = mutableFocus.value?.let { FocusedBlock(it.flowId, it.blockId) }
        if (relaid || focused != shown) {
            shown = focused
            val pages = keepUnchanged(current.pages, PageTexts.of(doc, layouts))
            val canvas = if (focused == null) pages else keepUnchanged(current.canvas, PageTexts.of(doc, layouts, focused))
            mutableSnapshot.value = TextSnapshot(layouts, pages, canvas)
        }
        ready.complete(Unit)
    }

    override fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean =
        PerfMonitor.trace(TRACE_FOCUS) {
            val focus = mutableSnapshot.value.focusAt(pageId, xPt, yPt, mutableFocus.value?.revision ?: 0)
            mutableFocus.value = focus
            focus != null
        }

    override fun blur() {
        mutableFocus.value = null
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

    /**
     * The parse of flow [flowId] block editing starts from; its block ids stay the same until undo or redo changes the
     * text. Null for an unknown flow.
     */
    fun parsed(flowId: FlowId): MarkdownDocument? =
        documentSession.document.value.flows[flowId]
            ?.let(parses::of)

    /**
     * [editCommand] for the flow to become `after.toMarkdown()`, where [after] was derived from [parsed] by block
     * editing: one replacement of the changed middle of the text, and [after] is adopted so the block ids (the
     * focused block among them) survive the edit. Null if the text does not change or the edit does not fit.
     */
    suspend fun editCommand(
        flowId: FlowId,
        after: MarkdownDocument,
        coalesceKey: String? = null,
    ): EditCommand? {
        val flow = documentSession.document.value.flows[flowId] ?: return null
        val edit = FlowTextDiff.between(flow.markdown, after.toMarkdown()) ?: return null
        parses.adopt(flowId, after)
        return editCommand(flowId, listOf(edit), coalesceKey)
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
        const val TRACE_FOCUS = "text:focus"
        const val DISCOVER_CHUNK = 8

        // A page whose runs did not change keeps its instance, so the tiles and the thumbnails see no change.
        fun keepUnchanged(
            old: Map<PageId, PageText>,
            next: Map<PageId, PageText>,
        ): Map<PageId, PageText> = next.mapValues { (id, text) -> old[id]?.takeIf { PageText.changedBounds(it, text).isEmpty() } ?: text }
    }
}
