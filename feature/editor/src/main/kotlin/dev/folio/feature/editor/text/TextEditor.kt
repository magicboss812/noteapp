package dev.folio.feature.editor.text

import dev.folio.core.common.PerfMonitor
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.text.edit.EditResult
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.markdown.MarkdownDocument
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.consumeEach
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

/** Trace section of handing one field edit to the queue (main thread part of a keystroke). */
internal const val TRACE_EDIT = "text:edit"

/** Runs a block edit as one flow edit in the session: [FlowId], undo coalescing key, the edit of the parsed flow. */
internal typealias BlockEditRunner = suspend (FlowId, String?, (MarkdownDocument) -> EditResult?) -> EditResult?

/**
 * The [TextController] of an editor session: focus goes to [text], field edits run one after the other through
 * [runEdit] on [dispatcher] (the main thread). The live field shows what it typed at once, so the model follows
 * behind: a keystroke that waits for the one before it replaces any older keystroke of the same block (a typed edit
 * carries the whole block source), and a structural key keeps its place in the order.
 */
internal class TextEditor(
    private val text: EditorText,
    private val runEdit: BlockEditRunner,
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
) : TextController {
    private class Pending(
        val flowId: FlowId,
        val blockId: Long,
        val revision: Int,
        val edit: FieldEdit,
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

    override val field: Flow<TextFieldSpec?> get() = text.field

    init {
        scope.launch(dispatcher) { wake.consumeEach { drain() } }
    }

    override fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean = text.focusAt(pageId, xPt, yPt)

    override fun moveFocusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean = text.moveFocusAt(pageId, xPt, yPt)

    override fun blur() = text.blur()

    override fun select(
        start: Int,
        end: Int,
        cell: TableCell?,
    ) {
        // HOT PATH: per caret move; two ints and a reference, no allocation.
        selectionStart = minOf(start, end).coerceAtLeast(0)
        selectionEnd = maxOf(start, end).coerceAtLeast(0)
        selectionCell = cell
    }

    override fun format(action: FormatAction) = edit(FieldEdit.Format(action, selectionStart, selectionEnd, selectionCell))

    override fun edit(edit: FieldEdit) {
        // HOT PATH: per keystroke; the block source String is the only allocation besides the queue entry.
        PerfMonitor.trace(TRACE_EDIT) {
            val focus = text.focus.value ?: return@trace
            val next = Pending(focus.flowId, focus.blockId, focus.revision, edit)
            if (waiting.lastOrNull()?.isTypedIn(next) == true) waiting.removeLast()
            waiting.addLast(next)
            wake.trySend(Unit)
        }
    }

    private suspend fun drain() {
        while (waiting.isNotEmpty()) run(waiting.removeFirst())
    }

    // An edit always goes to the block it was typed in (it is skipped only when that block is gone). It moves the focus
    // only while the focus is still the one it was typed at: after a tap, a blur or an earlier split or merge the focus
    // stays where that put it, so a late keystroke is kept without pulling the caret back.
    private suspend fun run(item: Pending) {
        val edit = item.edit
        val keystroke = edit is FieldEdit.Typed || edit is FieldEdit.Cell
        val coalesceKey = if (keystroke) "type:${item.flowId.value}:${item.blockId}" else null
        val result = runEdit(item.flowId, coalesceKey) { doc -> edit.applyTo(doc, item.blockId) }
        if (text.focus.value?.revision != item.revision) return
        when {
            result != null -> text.focusAfter(item.flowId, result, edit)
            edit is FieldEdit.Typed -> text.resync(edit.caret)
            edit is FieldEdit.Cell -> text.focus.value?.let { text.resync(it.caret) }
        }
    }
}
