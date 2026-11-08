package dev.folio.feature.editor.text

import androidx.annotation.MainThread
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextFlow
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.EditFlow
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.storage.session.DocumentSession
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.EditResult
import dev.folio.core.text.edit.FlowTextDiff
import dev.folio.core.text.edit.TableEditing
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.flow.EditedBlock
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
    /** The selection of [field]'s focus as the canvas draws it, null while it is collapsed. */
    val selection: SelectionHighlight? = null,
) {
    /** Constants. */
    companion object {
        /** No flow shows text. */
        val EMPTY = TextSnapshot(emptyMap(), emptyMap())
    }
}

/** A block edit's [result] (its selection for the field) and the [command] that stores it; null if the text stays. */
class BlockEdit(
    val result: EditResult,
    val command: EditCommand?,
)

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

    // The layouts without an edited block (thumbnails) and the edited (flow, block) of the snapshot's layouts.
    private var plainLayouts: Map<FlowId, FlowLayout> = emptyMap()
    private var edited: Pair<FlowId, Long>? = null

    // The latest focus revision a relayout replaced (its block was gone) and the revision it gave the new focus.
    @Volatile private var resolved: Pair<Int, Int>? = null

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
        val base = if (relaid) PageTexts.layouts(doc, engine.layouter, parses::of, broken) else plainLayouts
        plainLayouts = base
        inputs = next
        val focus = resolveFocus(base)
        val layouts = if (relaid) editLayouts(doc, base, focus, null) else editLayouts(doc, base, focus, current.layouts)
        // The canvas leaves out the block the overlay draws, so tiles and overlay come from one layout (P06-T18).
        val field = if (focus == null) null else layouts.fieldOf(focus) ?: current.field
        val focused = field?.focus?.shownIn(layouts)
        // A relayout always gives new layouts.
        if (layouts !== current.layouts || focused != shown || field?.focus != current.field?.focus) {
            shown = focused
            val pages = if (relaid) keepUnchanged(current.pages, PageTexts.of(doc, base)) else current.pages
            val canvas = if (focused == null) pages else keepUnchanged(current.canvas, PageTexts.of(doc, layouts, focused))
            mutableSnapshot.value = TextSnapshot(layouts, pages, canvas, field, layouts.highlightOf(field?.focus))
        }
        PerfMonitor.record(TRACE_REFRESH, PerfMonitor.clock.monotonicNs() - startNs)
        ready.complete(Unit)
    }

    // The focused flow laid out with the edited block as its source and paginated with the rest (A-058): hits, caret and
    // selection use that layout, so they match what the canvas overlay draws. [kept] are the layouts to keep while the
    // edited block is the same one (null after a relayout).
    private fun editLayouts(
        doc: Document,
        base: Map<FlowId, FlowLayout>,
        focus: TextFocus?,
        kept: Map<FlowId, FlowLayout>?,
    ): Map<FlowId, FlowLayout> {
        val edit = focus?.let { it.flowId to it.blockId }
        val same = edit == edited
        edited = edit
        return when {
            focus == null -> {
                base
            }

            kept != null && same -> {
                kept
            }

            else -> {
                PageTexts.flowLayout(doc, focus.flowId, engine.layouter, parses::of, broken, focus.blockId)?.let {
                    base +
                        (focus.flowId to it)
                }
                    ?: base
            }
        }
    }

    // The focused block's id changes when undo or redo parses the text again, and an edit may merge it away: the block
    // at the same place gets the focus, and [isFocusAt] still knows it as the old revision's.
    private fun resolveFocus(layouts: Map<FlowId, FlowLayout>): TextFocus? {
        var moved: Pair<Int, Int>? = null
        mutableFocus.update { before ->
            before?.resolvedIn(layouts).also { after ->
                moved =
                    if (before != null && after != null && after.revision != before.revision) before.revision to after.revision else null
            }
        }
        moved?.let { resolved = it }
        return mutableFocus.value
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

    /**
     * Focuses the block at ([xPt], [yPt]) of page [pageId] (a box takes taps up to [slopPt] beside its lines); no block
     * there ends editing. True if a block got focus.
     */
    fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        slopPt: Float = 0f,
    ): Boolean =
        PerfMonitor.trace(TRACE_FOCUS) {
            val focus = mutableSnapshot.value.focusAt(pageId, xPt, yPt, mutableFocus.value?.revision ?: 0, slopPt)
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

    /**
     * The field's selection changed to [anchorOffset]..[caret] (offsets of the focused block) without a new focus: the
     * focus follows it so the canvas draws the selection, keeping the revision (the field owns its caret). An end in
     * another block stays only while the change [extending]s the selection (Shift held).
     */
    internal fun track(
        anchorOffset: Int,
        caret: Int,
        extending: Boolean,
    ) {
        mutableFocus.update { focus ->
            val other = focus?.anchor?.takeIf { extending && it.blockId != focus.blockId }
            val anchor = other ?: focus?.let { Caret(it.blockId, anchorOffset) }?.takeIf { anchorOffset != caret }
            if (focus == null || (focus.anchor == anchor && focus.caret == caret)) focus else focus.copy(caret = caret, anchor = anchor)
        }
    }

    /**
     * True while the focus is still the one of [revision]: unchanged, or only moved by a relayout because its block
     * went away (an edit that merged it away still places the caret). A tap, a blur or another edit's focus is not.
     */
    internal fun isFocusAt(revision: Int): Boolean {
        val current = mutableFocus.value?.revision ?: return false
        return current == revision || resolved?.let { it.first == revision && it.second == current } == true
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
     * The focused block laid out as [source] in place of its current text and paginated from where it starts, for the
     * overlay to draw a keystroke before the reflow lands (A-058); null without focus.
     */
    @MainThread
    fun retype(source: String): EditedBlock? {
        val focus = mutableFocus.value ?: return null
        val flow = mutableSnapshot.value.layouts[focus.flowId] ?: return null
        val index = flow.markdown.indexOf(focus.blockId)
        return if (index < 0) null else engine.layouter.retype(flow, index, source)
    }

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

    /**
     * Where a Text tool tap at ([xPt], [yPt]) on page [pageId] puts the caret, a box taking taps up to [slopPt] beside
     * its lines; new flows get [style]. Main thread.
     */
    internal fun planTap(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        style: FlowStyle,
        slopPt: Float = 0f,
    ): CaretPlan? = CaretPlans.forTap(documentSession.document.value, mutableSnapshot.value.layouts, pageId, xPt, yPt, style, slopPt)

    /** Where typing with nothing focused on page [pageId] puts the caret; new flows get [style]. Main thread. */
    internal fun planTyping(
        pageId: PageId,
        style: FlowStyle,
    ): CaretPlan? = CaretPlans.forTyping(documentSession.document.value, mutableSnapshot.value.layouts, pageId, style)

    /**
     * [plan] as one command: the new box's flow and frame and the reflow they need execute and undo together. Null
     * when the plan no longer fits the document.
     */
    internal suspend fun newBoxCommand(plan: CaretPlan.NewBox): EditCommand? {
        val doc = documentSession.document.value
        return withContext(dispatchers.text) {
            try {
                val commands = ArrayList<EditCommand>(plan.setup)
                var after = doc
                for (command in plan.setup) after = command.execute(after).doc
                val reflow = engine.reflow.plan(after, plan.flowId, parses.of(checkNotNull(after.flows[plan.flowId])))
                reflow.command?.let { commands += it }
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

    /**
     * The command that removes flow [flowId] with its frames when it is an empty text box (blank text, box frames
     * only, A-059); null for any other flow. Main thread.
     */
    internal fun emptyBoxRemoval(flowId: FlowId): EditCommand? {
        val doc = documentSession.document.value
        val flow = doc.flows[flowId]?.takeIf { it.markdown.isBlank() } ?: return null
        val removals = ArrayList<EditCommand>()
        for (ref in doc.pages) {
            val objects = doc.page(ref.id)?.objects ?: continue
            val own = objects.filter { (it is FlowFrame && it.flow == flowId) || (it is StickyNote && it.flow == flowId) }
            if (own.any { it !is FlowFrame || it.role != FrameRole.BOX }) return null
            if (own.isNotEmpty()) removals += RemoveObjects(ref.id, own.map { it.id })
        }
        return Batch(removals + UpdateFlows(emptyList(), remove = listOf(flow.id)))
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
     * left empty, grown boxes) execute and undo together. Null if the edit does not fit the flow. With a [base] (the
     * flow text [edits] were computed on) the command is refused, now or when it executes, once the flow holds other
     * text, e.g. after an undo that ran while the edit was planned off the main thread.
     */
    suspend fun editCommand(
        flowId: FlowId,
        edits: List<TextEdit>,
        coalesceKey: String? = null,
        base: String? = null,
    ): EditCommand? {
        val edit = EditFlow(flowId, edits, coalesceKey, base)
        val doc = documentSession.document.value
        val focused = mutableFocus.value?.takeIf { it.flowId == flowId }?.blockId
        return withContext(dispatchers.text) {
            try {
                val after = edit.execute(doc).doc
                val parsed = parses.of(checkNotNull(after.flows[flowId]))
                // The focused block shows its source: the pages and the auto width follow that (A-059).
                val editBlock = focused?.let { parsed.indexOf(it) } ?: FlowLayout.NO_BLOCK
                val reflow = engine.reflow.plan(after, flowId, parsed, editBlock = editBlock)
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
     * Free text box [frameId] of page [fromPage] placed at [rect] of page [toPage] (P06-T17) as one command: the frame
     * moves or resizes in place, or leaves its page for [toPage] (on top there), and its flow reflows (an auto-grow
     * box takes the height its text needs). Null if the frame is not a box there or nothing changes.
     */
    suspend fun boxCommand(
        frameId: ObjectId,
        fromPage: PageId,
        toPage: PageId,
        rect: RectPt,
    ): EditCommand? {
        val doc = documentSession.document.value
        val frame = doc.page(fromPage)?.objects?.firstOrNull { it.id == frameId } as? FlowFrame ?: return null
        if (frame.role != FrameRole.BOX || doc.page(toPage) == null) return null
        if (fromPage == toPage && frame.rect == rect) return null
        val moved = frame.copy(rect = rect)
        val place =
            if (fromPage == toPage) {
                UpdateObjects(fromPage, listOf(moved))
            } else {
                Batch(listOf(RemoveObjects(fromPage, listOf(frameId)), AddObjects(toPage, listOf(moved))))
            }
        return withContext(dispatchers.text) {
            try {
                val after = place.execute(doc).doc
                val flow = checkNotNull(after.flows[frame.flow])
                val reflow = engine.reflow.plan(after, frame.flow, parses.of(flow))
                reflow.command?.let { Batch(listOf(place, it)) } ?: place
            } catch (e: IllegalArgumentException) {
                FolioLog.w(TAG, "box move rejected: ${e.message}")
                null
            } catch (e: IllegalStateException) {
                FolioLog.w(TAG, "box move rejected: ${e.message}")
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
     * Runs the block edit [op] on [parsed] of flow [flowId] and builds the [editCommand] for the flow to become its
     * outcome: one replacement of the changed middle of the text, and the outcome is adopted so the block ids (the
     * focused block among them) survive the edit. All on the `text` dispatcher: parse, edit, diff and reflow walk the
     * whole flow (11 ms of main thread per keystroke on a 20k-word flow before P06-T11). Null if [op] declined or the
     * edit does not fit; a null command if the text stays as it is (only the selection moved).
     */
    suspend fun blockEdit(
        flowId: FlowId,
        coalesceKey: String?,
        op: (MarkdownDocument) -> EditResult?,
    ): BlockEdit? =
        withContext(dispatchers.text) {
            val flow = documentSession.document.value.flows[flowId] ?: return@withContext null
            val result = op(BlockEditing.ensureBlock(parses.of(flow))) ?: return@withContext null
            val markdown = result.document.toMarkdown()
            if (markdown == flow.markdown) return@withContext BlockEdit(result, null)
            val edit = FlowTextDiff.between(flow.markdown, markdown) ?: return@withContext null
            val command = editCommand(flowId, listOf(edit), coalesceKey, base = flow.markdown) ?: return@withContext null
            parses.adopt(flowId, result.document, markdown)
            BlockEdit(result, command)
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
