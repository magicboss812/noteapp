package dev.folio.feature.editor.text

import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.TextFlow
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.EditFlow
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.storage.session.DocumentSession
import dev.folio.core.text.edit.EditResult
import dev.folio.core.text.edit.FlowTextDiff
import dev.folio.core.text.edit.TableEditing
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.flow.FlowLayout
import dev.folio.core.text.flow.FlowSearch
import dev.folio.core.text.flow.FocusedBlock
import dev.folio.core.text.flow.PageText
import dev.folio.core.text.flow.PageTexts
import dev.folio.core.text.markdown.MarkdownDocument
import dev.folio.core.text.markdown.linkedDocIds
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Flow layouts of one document version and what each page paints from them. */
class TextSnapshot(
    /** Layout of every flow that has frames, by flow. */
    val layouts: Map<FlowId, FlowLayout>,
    /** Page text of every page that shows text; other pages are absent. Thumbnails paint these. */
    val pages: Map<PageId, PageText>,
    /** [pages] as the canvas paints them: without the block of [field], which the live field draws. */
    val canvas: Map<PageId, PageText> = pages,
    /** The live field of the focused block in these layouts, null while nothing has focus. */
    val field: TextFieldSpec? = null,
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
@Suppress("TooManyFunctions") // focus, layout refresh and flow edits of one document share the parses and the snapshot
class EditorText internal constructor(
    private val documentSession: DocumentSession,
    private val engine: TextEngine,
    private val dispatchers: FolioDispatchers,
    scope: CoroutineScope,
    private val missingLinks: suspend (Set<String>) -> Set<String> = { emptySet() },
) {
    private val parses = FlowParses()
    private val mutableSnapshot = MutableStateFlow(TextSnapshot.EMPTY)
    private val mutableFocus = MutableStateFlow<TextFocus?>(null)
    private val ready = CompletableDeferred<Unit>()
    private var discovered = false
    private var inputs: LayoutInputs? = null
    private var shown: FocusedBlock? = null

    // The note ids the flows link to, and those of them no note in the library has (drawn broken).
    private var linked: Set<String> = emptySet()
    private var broken: Set<String> = emptySet()

    /** The latest layouts and page texts; [TextSnapshot.EMPTY] until [awaitReady]. */
    val snapshot: StateFlow<TextSnapshot> = mutableSnapshot.asStateFlow()

    /** The block being edited, null while the live field is not shown. */
    val focus: StateFlow<TextFocus?> = mutableFocus.asStateFlow()

    /**
     * The live field of the current focus (chrome state): null while no block has focus, otherwise [TextSnapshot.field]
     * once a refresh laid it out for that focus; until then nothing is emitted, so the field stays. The canvas takes
     * [TextSnapshot.field] with the page text instead (P06-T18).
     */
    val field: Flow<TextFieldSpec?> =
        combine(mutableFocus, mutableSnapshot, ::Pair)
            .transform { (focus, snapshot) ->
                val spec = snapshot.field
                if (focus == null) {
                    emit(null)
                } else if (spec != null && spec.focus == focus) {
                    emit(spec)
                }
            }.distinctUntilChanged { a, b -> a === b }

    init {
        scope.launch(dispatchers.text) {
            // Sequential: a focus change redraws the pages without the block, a document change lays out again.
            combine(documentSession.document, mutableFocus, ::Pair).collect { (seen, _) -> refresh(seen) }
        }
    }

    /** Returns once every page with a frame is decoded and [snapshot] shows the first layout. */
    suspend fun awaitReady() = ready.await()

    private suspend fun refresh(seen: Document) {
        val startNs = PerfMonitor.clock.monotonicNs()
        if (!discovered) {
            discovered = true
            discover(seen)
        }
        val doc = documentSession.document.value
        val next = LayoutInputs.of(doc)
        val rebroken = next.flows != inputs?.flows && checkLinks(doc)
        val relaid = next != inputs || rebroken
        val current = mutableSnapshot.value
        val layouts = if (relaid) PageTexts.layouts(doc, engine.layouter, parses::of, broken) else current.layouts
        inputs = next
        // The focused block's id changes when undo or redo parses the text again: the block at the same place gets the focus.
        mutableFocus.update { it?.resolvedIn(layouts) }
        val focus = mutableFocus.value
        // The canvas leaves out the block the field shows, so tiles and field come from one layout (P06-T18).
        val field = if (focus == null) null else layouts.fieldOf(focus) ?: current.field
        val focused = field?.focus?.shownIn(layouts)
        if (relaid || focused != shown || field?.focus != current.field?.focus) {
            shown = focused
            val pages = if (relaid) keepUnchanged(current.pages, PageTexts.of(doc, layouts)) else current.pages
            val canvas = if (focused == null) pages else keepUnchanged(current.canvas, PageTexts.of(doc, layouts, focused))
            mutableSnapshot.value = TextSnapshot(layouts, pages, canvas, field)
        }
        PerfMonitor.record(TRACE_REFRESH, PerfMonitor.clock.monotonicNs() - startNs)
        ready.complete(Unit)
    }

    // Asks the library which linked notes are missing when the set of linked ids changed; true if [broken] changed.
    private suspend fun checkLinks(doc: Document): Boolean {
        val ids = HashSet<String>()
        for (flow in doc.flows.values) {
            if (!flow.markdown.contains(NOTE_LINK)) continue
            for (block in parses.of(flow).blocks) ids += block.content.linkedDocIds()
        }
        if (ids == linked) return false
        linked = ids
        val missing = if (ids.isEmpty()) emptySet() else withContext(dispatchers.io) { missingLinks(ids) }
        val changed = missing != broken
        broken = missing
        return changed
    }

    /** The checkbox or link at ([xPt], [yPt]) of page [pageId] outside the focused block, null for none. Main thread. */
    internal fun tapAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): LinkTap? = mutableSnapshot.value.tapAt(pageId, xPt, yPt, mutableFocus.value)

    /** Focuses the block at ([xPt], [yPt]) of page [pageId]; no block there ends editing. True if a block got focus. */
    fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean =
        PerfMonitor.trace(TRACE_FOCUS) {
            val focus = mutableSnapshot.value.focusAt(pageId, xPt, yPt, mutableFocus.value?.revision ?: 0)
            mutableFocus.value = focus
            focus != null
        }

    /** Like [focusAt], but a point on no text frame leaves the focus alone. */
    fun moveFocusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean {
        val focus = mutableSnapshot.value.focusAt(pageId, xPt, yPt, mutableFocus.value?.revision ?: 0) ?: return false
        mutableFocus.value = focus
        return true
    }

    /** Ends editing. */
    fun blur() {
        mutableFocus.value = null
    }

    /**
     * Selects [selection] of flow [flowId] (07-text-engine.md#selection-as-built): the field goes to the block of its
     * focus end, the anchor end may lie in another block. False when the flow or the block is not laid out.
     */
    internal fun select(
        flowId: FlowId,
        selection: TextSelection,
    ): Boolean {
        val caret = selection.focus
        val index =
            mutableSnapshot.value.layouts[flowId]
                ?.markdown
                ?.indexOf(caret.blockId) ?: -1
        if (index < 0) return false
        val anchor = selection.anchor.takeIf { it != caret }
        mutableFocus.update { TextFocus(flowId, caret.blockId, index, caret.offset, (it?.revision ?: 0) + 1, anchor) }
        return true
    }

    /** The selection collapsed in the field (a plain arrow key): its end in another block is let go, the field keeps its caret. */
    internal fun dropAnchor() {
        mutableFocus.update { if (it?.anchor == null) it else it.copy(anchor = null) }
    }

    /** The flow and caret under ([xPt], [yPt]) of page [pageId] (as a tap would focus it), null on no text frame. */
    internal fun caretAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): TextFocus? = mutableSnapshot.value.focusAt(pageId, xPt, yPt, 0)

    /** The parse of flow [flowId] its current layout shows, null while it has none. */
    internal fun laidOut(flowId: FlowId): MarkdownDocument? = mutableSnapshot.value.layouts[flowId]?.markdown

    /**
     * Puts the focus where block editing left the caret ([result] of flow [flowId]). A keystroke ([edit]) whose text is
     * what the block source (or the table cell) became keeps the focus revision: the field owns its caret. Anything
     * else (a split, a merge, a shortcut, a cell text that got escaped) gives the field the new block and caret.
     */
    internal fun focusAfter(
        flowId: FlowId,
        result: EditResult,
        edit: FieldEdit? = null,
    ) {
        val caret = result.selection.focus
        val index = result.document.indexOf(caret.blockId)
        if (index < 0) return
        val source = result.document.blocks[index].source
        // The echo of a keystroke: the block source, or the cell's text, is what the field typed. GFM pads cells with
        // spaces, so a space at either end of a cell lives only in the field until the next character follows it.
        val echo =
            when (edit) {
                is FieldEdit.Typed -> {
                    source == edit.source
                }

                is FieldEdit.Cell -> {
                    TableEditing.spanOf(source, edit.cell).let { source.substring(it.start, it.end) } ==
                        edit.text.trim(' ')
                }

                else -> {
                    false
                }
            }
        // A selection the edit leaves (formatting marks the selected text) stays selected.
        val anchor = result.selection.anchor.takeIf { it != caret && result.document.indexOf(it.blockId) >= 0 }
        mutableFocus.update { before ->
            val kept = before?.takeIf { echo && it.blockId == caret.blockId }
            when {
                kept == null -> TextFocus(flowId, caret.blockId, index, caret.offset, (before?.revision ?: 0) + 1, anchor)

                // Writing a cell back pads the table: the caret offset follows, so the field stays on its cell.
                edit is FieldEdit.Cell -> kept.copy(blockIndex = index, caret = caret.offset)

                else -> kept.copy(blockIndex = index)
            }
        }
    }

    /** Where a Text tool tap at ([xPt], [yPt]) on page [pageId] puts the caret; new flows get [style]. Main thread. */
    internal fun planTap(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        style: FlowStyle,
    ): CaretPlan? = CaretPlans.forTap(documentSession.document.value, mutableSnapshot.value.layouts, pageId, xPt, yPt, style)

    /** Where typing with nothing focused on page [pageId] puts the caret; new flows get [style]. Main thread. */
    internal fun planTyping(
        pageId: PageId,
        style: FlowStyle,
    ): CaretPlan? = CaretPlans.forTyping(documentSession.document.value, mutableSnapshot.value.layouts, pageId, style)

    /**
     * [plan] as one command: its setup (a new flow and frame), the empty paragraphs down to the tapped line and the
     * reflow they need execute and undo together. Null when nothing changes or the plan no longer fits the document.
     */
    internal suspend fun appendCommand(plan: CaretPlan.Append): EditCommand? {
        val doc = documentSession.document.value
        return withContext(dispatchers.text) {
            try {
                val commands = ArrayList<EditCommand>(plan.setup)
                var after = doc
                for (command in plan.setup) after = command.execute(after).doc
                val flow = checkNotNull(after.flows[plan.flowId])
                CaretPlans.emptyParagraphs(flow.markdown, parses.of(flow), plan.lines)?.let {
                    val edit = EditFlow(plan.flowId, listOf(it))
                    after = edit.execute(after).doc
                    commands += edit
                }
                if (commands.isNotEmpty()) {
                    val reflow = engine.reflow.plan(after, plan.flowId, parses.of(checkNotNull(after.flows[plan.flowId])))
                    reflow.command?.let { commands += it }
                }
                if (commands.size > 1) Batch(commands) else commands.singleOrNull()
            } catch (e: IllegalArgumentException) {
                FolioLog.w(TAG, "caret plan rejected: ${e.message}")
                null
            } catch (e: IllegalStateException) {
                FolioLog.w(TAG, "caret plan rejected: ${e.message}")
                null
            }
        }
    }

    /** Returns once [snapshot] shows the current text of flow [flowId]; false if the flow is gone (an undo came first). */
    internal suspend fun awaitLayout(flowId: FlowId): Boolean {
        var shown = false
        mutableSnapshot.first { snapshot ->
            val markdown =
                documentSession.document.value.flows[flowId]
                    ?.markdown
            shown = markdown != null && snapshot.layouts[flowId]?.markdown?.toMarkdown() == markdown
            markdown == null || shown
        }
        return shown
    }

    /** Focuses block [blockIndex] of flow [flowId] (its last block for null) with the caret at its end. */
    internal fun focusEnd(
        flowId: FlowId,
        blockIndex: Int? = null,
    ): TextFocus? {
        val blocks =
            mutableSnapshot.value.layouts[flowId]
                ?.markdown
                ?.blocks ?: return null
        val index = blockIndex ?: blocks.lastIndex
        val block = blocks.getOrNull(index) ?: return null
        mutableFocus.update { TextFocus(flowId, block.id, index, block.source.length, (it?.revision ?: 0) + 1) }
        return mutableFocus.value
    }

    /** Makes the field show the model's text again, with the caret at [caret]: an edit of the focused block was rejected. */
    internal fun resync(caret: Int) {
        mutableFocus.update { it?.copy(caret = caret, revision = it.revision + 1) }
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
        const val TRACE_REFRESH = "text:refresh"
        const val DISCOVER_CHUNK = 8
        const val NOTE_LINK = "folio://doc/"

        // A page whose runs did not change keeps its instance, so the tiles and the thumbnails see no change.
        fun keepUnchanged(
            old: Map<PageId, PageText>,
            next: Map<PageId, PageText>,
        ): Map<PageId, PageText> = next.mapValues { (id, text) -> old[id]?.takeIf { PageText.changedBounds(it, text).isEmpty() } ?: text }
    }
}
