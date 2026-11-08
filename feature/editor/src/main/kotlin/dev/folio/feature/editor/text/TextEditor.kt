package dev.folio.feature.editor.text

import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.FlowId
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextAlign
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.edit.BlockEditing
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.EditResult
import dev.folio.core.text.edit.LinkEdits
import dev.folio.core.text.edit.SelectionEditing
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.edit.WordRanges
import dev.folio.core.text.flow.EditedBlock
import dev.folio.core.text.flow.TapTarget
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Trace section of handing one field edit to the queue (main thread part of a keystroke). */
internal const val TRACE_EDIT = "text:edit"

// A Text tool tap that creates text: the command, then the wait for the new text's layout (P06-T18).
internal const val TRACE_APPEND_COMMAND = "text:append-command"
internal const val TRACE_APPEND_LAYOUT = "text:append-layout"

/** Runs a block edit as one flow edit in the session: [FlowId], undo coalescing key, the edit of the parsed flow. */
internal typealias BlockEditRunner = suspend (FlowId, String?, (MarkdownDocument) -> EditResult?) -> EditResult?

/**
 * The [TextController] of an editor session: focus goes to [text], field edits run one after the other through
 * [runEdit] on [dispatcher] (the main thread). The live field shows what it typed at once, so the model follows
 * behind: a keystroke that waits for the one before it replaces any older keystroke of the same block (a typed edit
 * carries the whole block source), and a structural key keeps its place in the order.
 */
@Suppress("TooManyFunctions") // the TextController API plus the edit queue and the `[[` query it keeps
internal class TextEditor(
    private val text: EditorText,
    private val runEdit: BlockEditRunner,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    /** Executes a command in the session (a new flow or frame of a Text tool tap); false if it failed. */
    private val execute: suspend (EditCommand) -> Boolean = { false },
    /** The style of a flow a tap or a keystroke creates. */
    private val newFlowStyle: () -> FlowStyle = { DEFAULT_FLOW_STYLE },
) : TextController {
    // [edit] null: a tapped checkbox, toggled without moving the focus, or with a [plan] a caret placement and the
    // keyboard text [typed] while it runs. With a [selection] the edit applies to it (it may span blocks).
    private class Pending(
        val flowId: FlowId,
        val blockId: Long,
        val revision: Int,
        val edit: FieldEdit?,
        val plan: CaretPlan? = null,
        val typed: StringBuilder? = null,
        val selection: TextSelection? = null,
    ) {
        // A keystroke carries the whole block source (or cell text), so it replaces the one before it in the same place.
        fun isTypedIn(other: Pending): Boolean {
            if (flowId != other.flowId || blockId != other.blockId) return false
            val a = edit
            val b = other.edit
            return (a is FieldEdit.Typed && b is FieldEdit.Typed) || (a is FieldEdit.Cell && b is FieldEdit.Cell && a.cell == b.cell)
        }
    }

    // Touched on the main thread only: `edit` adds, the worker below takes.
    private val waiting = ArrayDeque<Pending>()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var selectionStart = 0
    private var selectionEnd = 0
    private var selectionCell: TableCell? = null

    // The field's selection as it reported it (anchor, caret), for the focus of [selectionRevision].
    private var fieldAnchor = 0
    private var fieldCaret = 0
    private var selectionRevision = NO_REVISION
    private val taps = MutableSharedFlow<TapTarget>(extraBufferCapacity = TAP_BUFFER)
    private val query = MutableStateFlow<String?>(null)

    // The word a long-press selected, start to end in one block: a drag after it extends from there by words.
    private var wordOrigin: TextSelection? = null

    // The open `[[` of [query] and the caret after its text, source offsets of the focused block; -1 without one.
    private var queryStart = -1
    private var queryCaret = -1

    // The placement for keyboard text that waits or runs; keys typed meanwhile join its text.
    private var placing: Pending? = null

    override val field: Flow<TextFieldSpec?> get() = text.field
    override val linkTaps: Flow<TapTarget> get() = taps
    override val noteQuery: StateFlow<String?> = query.asStateFlow()

    init {
        scope.launch(dispatcher) { wake.consumeEach { drain() } }
    }

    override fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean {
        clearQuery()
        return text.focusAt(pageId, xPt, yPt)
    }

    override fun moveFocusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean {
        clearQuery()
        return text.moveFocusAt(pageId, xPt, yPt)
    }

    override fun blur() {
        clearQuery()
        text.blur()
    }

    override fun select(
        start: Int,
        end: Int,
        cell: TableCell?,
        extending: Boolean,
    ) {
        // HOT PATH: per caret move; ints and a reference, no allocation while the caret moves without a selection (a
        // selection, and its collapse, copy the focus so the canvas draws it).
        selectionStart = minOf(start, end).coerceAtLeast(0)
        selectionEnd = maxOf(start, end).coerceAtLeast(0)
        selectionCell = cell
        fieldAnchor = start.coerceAtLeast(0)
        fieldCaret = end.coerceAtLeast(0)
        val focus = text.focus.value
        selectionRevision = focus?.revision ?: NO_REVISION
        if (cell == null && (start != end || focus?.anchor != null)) text.track(fieldAnchor, fieldCaret, extending)
        val caretStays = start == end && start == queryCaret && cell == null
        if (queryStart >= 0 && !caretStays) clearQuery()
    }

    // The selection of the flow: the field's own while it showed the current focus, else the focus as it was given; null
    // while nothing has focus or the field edits a table cell.
    private fun flowSelection(): TextSelection? {
        val focus = text.focus.value ?: return null
        val reported = selectionRevision == focus.revision
        if (reported && selectionCell != null) return null
        val caret = Caret(focus.blockId, if (reported) fieldCaret else focus.caret)
        val anchor =
            focus.anchor?.takeIf { it.blockId != focus.blockId || !reported }
                ?: Caret(focus.blockId, if (reported) fieldAnchor else focus.caret)
        return TextSelection(anchor, caret)
    }

    override fun extendHorizontally(forward: Boolean) {
        val focus = text.focus.value ?: return
        val current = flowSelection() ?: return
        val doc = text.laidOut(focus.flowId)?.takeIf { it.indexOf(current.focus.blockId) >= 0 } ?: return
        val next = (if (forward) BlockEditing.right(doc, current.focus) else BlockEditing.left(doc, current.focus)) ?: return
        moveEnd(focus.flowId, doc, current, next, anchorEnd = false)
    }

    override fun extendTo(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) = dragSelection(anchor = false, pageId, xPt, yPt)

    override fun dragSelection(
        anchor: Boolean,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) {
        val focus = text.focus.value ?: return
        val current = flowSelection() ?: return
        val hit = text.caretAt(pageId, xPt, yPt)?.takeIf { it.flowId == focus.flowId } ?: return
        val doc = text.laidOut(focus.flowId) ?: return
        moveEnd(focus.flowId, doc, current, Caret(hit.blockId, hit.caret), anchor)
    }

    override fun selectWordAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean {
        val focus = text.focus.value ?: return false
        val hit = text.caretAt(pageId, xPt, yPt)?.takeIf { it.flowId == focus.flowId && it.blockId == focus.blockId } ?: return false
        val block = text.laidOut(focus.flowId)?.let { it.blocks.getOrNull(it.indexOf(hit.blockId)) } ?: return false
        // A table is edited one cell at a time and a selection never ends inside it (A-053).
        if (block.content is BlockContent.Table) return false
        val word = WordRanges.at(block, hit.caret)
        val origin = TextSelection(Caret(block.id, word.start), Caret(block.id, word.end))
        wordOrigin = origin
        clearQuery()
        text.select(focus.flowId, origin)
        return true
    }

    override fun extendWordsTo(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) {
        val origin = wordOrigin ?: return
        val focus = text.focus.value ?: return
        val current = flowSelection() ?: return
        val hit = text.caretAt(pageId, xPt, yPt)?.takeIf { it.flowId == focus.flowId } ?: return
        val doc = text.laidOut(focus.flowId) ?: return
        val next = wordsTo(doc, origin, Caret(hit.blockId, hit.caret)) ?: return
        if (next == current) return
        clearQuery()
        text.select(focus.flowId, next)
    }

    // The word selection [origin] grown by whole words to the word at [target]; null if [target] is not in [doc].
    private fun wordsTo(
        doc: MarkdownDocument,
        origin: TextSelection,
        target: Caret,
    ): TextSelection? {
        val index = doc.indexOf(target.blockId)
        val start = doc.indexOf(origin.anchor.blockId)
        val block = doc.blocks.getOrNull(index)
        if (block == null || start < 0) return null
        val word = WordRanges.at(block, target.offset)
        val before = index < start || (index == start && word.start < origin.anchor.offset)
        val after = index > start || (index == start && word.end > origin.focus.offset)
        return when {
            before -> SelectionEditing.outsideTables(doc, Caret(block.id, word.start), false)?.let { TextSelection(origin.focus, it) }
            after -> SelectionEditing.outsideTables(doc, Caret(block.id, word.end), true)?.let { TextSelection(origin.anchor, it) }
            else -> origin
        }
    }

    // One end of [current] goes to [target], past any table there (the field edits tables one cell at a time).
    private fun moveEnd(
        flowId: FlowId,
        doc: MarkdownDocument,
        current: TextSelection,
        target: Caret,
        anchorEnd: Boolean,
    ) {
        val from = if (anchorEnd) current.anchor else current.focus
        val forward = doc.indexOf(target.blockId) >= doc.indexOf(from.blockId)
        val landed = SelectionEditing.outsideTables(doc, target, forward) ?: return
        val next = if (anchorEnd) TextSelection(landed, current.focus) else TextSelection(current.anchor, landed)
        if (next == current) return
        clearQuery()
        text.select(flowId, next)
    }

    override fun retype(source: String): EditedBlock? = text.retype(source)

    override fun selectAll() {
        val focus = text.focus.value ?: return
        val current = flowSelection() ?: return
        val doc = text.laidOut(focus.flowId) ?: return
        if (doc.indexOf(current.anchor.blockId) < 0 || doc.indexOf(current.focus.blockId) < 0) return
        clearQuery()
        text.select(focus.flowId, SelectionEditing.selectAll(doc, current))
    }

    override fun copySelection(): String? {
        val focus = text.focus.value ?: return null
        val current = flowSelection()?.takeIf { !it.isCollapsed } ?: return null
        val doc = text.parsed(focus.flowId) ?: return null
        if (doc.indexOf(current.anchor.blockId) < 0 || doc.indexOf(current.focus.blockId) < 0) return null
        return BlockEditing.copy(doc, current)
    }

    override fun cutSelection(): String? = copySelection()?.also { edit(FieldEdit.Replace("")) }

    override fun format(action: FormatAction) = edit(FieldEdit.Format(action, selectionStart, selectionEnd, selectionCell))

    override fun edit(edit: FieldEdit) {
        // HOT PATH: per keystroke; the block source String is the only allocation besides the queue entry (and the
        // query text while the caret is in a `[[` link).
        PerfMonitor.trace(TRACE_EDIT) {
            val focus = text.focus.value ?: return@trace
            if (edit is FieldEdit.Typed) trackQuery(edit.source, edit.caret) else clearQuery()
            // A replacement takes the selection as it is now; keys and formatting too when it spans blocks.
            val across = edit is FieldEdit.Replace || (focus.spansBlocks && (edit is FieldEdit.Key || edit is FieldEdit.Format))
            val selection = if (across) flowSelection() ?: return@trace else null
            enqueue(Pending(focus.flowId, focus.blockId, focus.revision, edit, selection = selection))
        }
    }

    override fun tapAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean {
        val tap = text.tapAt(pageId, xPt, yPt) ?: return false
        if (tap.target is TapTarget.Checkbox) {
            enqueue(Pending(tap.flowId, tap.blockId, NO_REVISION, null))
        } else {
            taps.tryEmit(tap.target)
        }
        return true
    }

    override fun insertNoteLink(
        title: String,
        docId: String,
    ) {
        if (queryStart < 0) return
        edit(FieldEdit.NoteLink(queryStart, queryCaret, title, docId))
    }

    override fun placeCaret(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) {
        clearQuery()
        when (val plan = text.planTap(pageId, xPt, yPt, newFlowStyle())) {
            null -> {
                Unit
            }

            CaretPlan.Hit -> {
                text.focusAt(pageId, xPt, yPt)
            }

            is CaretPlan.End -> {
                text.focusEnd(plan.flowId, plan.blockIndex)
            }

            is CaretPlan.Append -> {
                text.blur()
                enqueue(Pending(plan.flowId, NO_BLOCK, NO_REVISION, null, plan))
            }
        }
    }

    override fun placeBox(
        frameId: ObjectId,
        fromPage: PageId,
        toPage: PageId,
        rect: RectPt,
    ) {
        scope.launch(dispatcher) { text.boxCommand(frameId, fromPage, toPage, rect)?.let { execute(it) } }
    }

    override fun typeIntoPage(
        pageId: PageId,
        text: String,
    ): Boolean {
        if (this.text.focus.value != null) return false
        val joining = placing?.typed
        joining?.append(text)
        return joining != null || startTyping(pageId, text)
    }

    // Plans where typing on [pageId] goes and queues the placement with [text]; false if the page takes no text.
    private fun startTyping(
        pageId: PageId,
        text: String,
    ): Boolean {
        val plan = this.text.planTyping(pageId, newFlowStyle())
        val flowId =
            when (plan) {
                is CaretPlan.End -> plan.flowId
                is CaretPlan.Append -> plan.flowId
                CaretPlan.Hit, null -> null
            }
        if (flowId != null) {
            val item = Pending(flowId, NO_BLOCK, NO_REVISION, null, plan, StringBuilder(text))
            placing = item
            enqueue(item)
        }
        return flowId != null
    }

    private fun enqueue(next: Pending) {
        if (waiting.lastOrNull()?.isTypedIn(next) == true) waiting.removeLast()
        waiting.addLast(next)
        wake.trySend(Unit)
    }

    private fun trackQuery(
        source: String,
        caret: Int,
    ) {
        val start = LinkEdits.noteLinkQueryStart(source, caret)
        if (start == null) {
            clearQuery()
            return
        }
        queryStart = start
        queryCaret = caret
        query.value = source.substring(start + 2, caret)
    }

    private fun clearQuery() {
        if (queryStart < 0) return
        queryStart = -1
        queryCaret = -1
        query.value = null
    }

    private suspend fun drain() {
        while (waiting.isNotEmpty()) run(waiting.removeFirst())
    }

    // An edit always goes to the block it was typed in (it is skipped only when that block is gone). It moves the focus
    // only while the focus is still the one it was typed at: after a tap, a blur or an earlier split or merge the focus
    // stays where that put it, so a late keystroke is kept without pulling the caret back.
    private suspend fun run(item: Pending) {
        val edit = item.edit
        val plan = item.plan
        if (plan != null) {
            try {
                place(plan, item.typed)
            } finally {
                if (placing === item) placing = null
            }
            return
        }
        if (edit == null) {
            runEdit(item.flowId, null) { doc -> LinkEdits.toggleTask(doc, item.blockId) }
            return
        }
        val keystroke = edit is FieldEdit.Typed || edit is FieldEdit.Cell
        val coalesceKey = if (keystroke) "type:${item.flowId.value}:${item.blockId}" else null
        val across = item.selection
        val result =
            runEdit(item.flowId, coalesceKey) { doc ->
                if (across !=
                    null
                ) {
                    edit.applyAcross(doc, across)
                } else {
                    edit.applyTo(doc, item.blockId)
                }
            }
        if (!text.isFocusAt(item.revision)) return
        when {
            result != null -> text.focusAfter(item.flowId, result, edit)
            edit is FieldEdit.Typed -> text.resync(edit.caret)
            edit is FieldEdit.Cell -> text.focus.value?.let { text.resync(it.caret) }
        }
    }

    // Carries out [plan], then types [typed] (and what joins it meanwhile) at the caret before the field shows, so the
    // field starts with the typed text. A focus that some tap set meanwhile stays.
    private suspend fun place(
        plan: CaretPlan,
        typed: StringBuilder?,
    ) {
        val (flowId, index) = target(plan) ?: return
        val result = typed?.let { typeAt(flowId, index, it) }
        if (text.focus.value == null) {
            if (result == null) text.focusEnd(flowId, index) else text.focusAfter(flowId, result)
        }
    }

    // The flow and block (null: its last) the caret of [plan] goes to, once the plan's command ran; null if it failed.
    private suspend fun target(plan: CaretPlan): Pair<FlowId, Int?>? =
        when (plan) {
            is CaretPlan.End -> {
                plan.flowId to plan.blockIndex
            }

            is CaretPlan.Append -> {
                // Timed apart (P06-T18): the command until the session ran it, then the wait for the new flow's layout.
                val clock = PerfMonitor.clock
                val startNs = clock.monotonicNs()
                val command = text.appendCommand(plan)
                val executed = command == null || execute(command)
                val executedNs = clock.monotonicNs()
                PerfMonitor.record(TRACE_APPEND_COMMAND, executedNs - startNs)
                val done = executed && text.awaitLayout(plan.flowId)
                PerfMonitor.record(TRACE_APPEND_LAYOUT, clock.monotonicNs() - executedNs)
                if (done) plan.flowId to null else null
            }

            CaretPlan.Hit -> {
                null
            }
        }

    // Types [typed] at the end of block [index] (null: the last) of [flowId], including text that joins it meanwhile.
    private suspend fun typeAt(
        flowId: FlowId,
        index: Int?,
        typed: StringBuilder,
    ): EditResult? {
        var result: EditResult? = null
        var caret =
            text.parsed(flowId)?.let(BlockEditing::ensureBlock)?.let { doc ->
                doc.blocks.getOrNull(index ?: doc.blocks.lastIndex)?.let { Caret(it.id, it.source.length) }
            }
        while (caret != null && typed.isNotEmpty()) {
            val chunk = typed.toString()
            typed.clear()
            val at = caret
            result = runEdit(flowId, "type:${flowId.value}:${at.blockId}") { doc -> BlockEditing.insert(doc, TextSelection.at(at), chunk) }
            caret = result?.selection?.focus
        }
        return result
    }

    private companion object {
        const val TAP_BUFFER = 4
        const val NO_REVISION = -1
        const val NO_BLOCK = -1L

        // Body text M (07-text-engine.md#font-normalization) until the text tool options choose the style.
        val DEFAULT_FLOW_STYLE = FlowStyle("Inter", 0.45f, 0, TextAlign.START)
    }
}
