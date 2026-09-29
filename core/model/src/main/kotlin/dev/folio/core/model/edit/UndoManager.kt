package dev.folio.core.model.edit

import dev.folio.core.common.Clock
import dev.folio.core.model.Document
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Undo/redo history of inverse commands (03-document-model.md#commands-and-undo). Keeps at most
 * [capacity] undo entries (oldest dropped). A push whose coalesce key equals the top entry's key within
 * [coalesceWindowMs] of that entry's last push merges into it. Undo and redo end a coalescing run.
 * Not thread-safe: the owning session calls it from one thread.
 */
class UndoManager(
    private val clock: Clock,
    val capacity: Int = DEFAULT_CAPACITY,
    private val coalesceWindowMs: Long = DEFAULT_COALESCE_WINDOW_MS,
) {
    private class Entry(
        val command: EditCommand,
        val key: String?,
        val lastPushNs: Long,
    )

    private val undoStack = ArrayDeque<Entry>()
    private val redoStack = ArrayDeque<Entry>()
    private var coalesceOpen = false
    private val canUndoState = MutableStateFlow(false)
    private val canRedoState = MutableStateFlow(false)

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    /** True while an undo entry exists. */
    val canUndo: StateFlow<Boolean> = canUndoState.asStateFlow()

    /** True while a redo entry exists. */
    val canRedo: StateFlow<Boolean> = canRedoState.asStateFlow()

    /** Number of undo entries. */
    val undoDepth: Int get() = undoStack.size

    /** Number of redo entries. */
    val redoDepth: Int get() = redoStack.size

    /** Command [undo] will execute next (so the session can load its [EditCommand.requiredPages]). */
    val nextUndo: EditCommand? get() = undoStack.lastOrNull()?.command

    /** Command [redo] will execute next. */
    val nextRedo: EditCommand? get() = redoStack.lastOrNull()?.command

    /** Records an executed command. Clears the redo stack. */
    fun push(applied: Applied) {
        redoStack.clear()
        val now = clock.monotonicNs()
        val top = undoStack.lastOrNull()
        val key = applied.coalesceKey
        if (top != null && canMerge(top, key, now)) {
            undoStack.removeLast()
            undoStack.addLast(Entry(merge(applied.inverse, top.command), key, now))
        } else {
            undoStack.addLast(Entry(applied.inverse, key, now))
            if (undoStack.size > capacity) undoStack.removeFirst()
        }
        coalesceOpen = true
        publish()
    }

    /**
     * Reverts the newest entry on [doc]; returns the result, or null if there is nothing to undo. If the
     * inverse throws, the history is unchanged.
     */
    fun undo(doc: Document): Applied? {
        val entry = undoStack.lastOrNull() ?: return null
        val applied = entry.command.execute(doc)
        undoStack.removeLast()
        redoStack.addLast(Entry(applied.inverse, entry.key, 0L))
        coalesceOpen = false
        publish()
        return applied
    }

    /** Re-applies the newest undone entry on [doc]; returns the result, or null if there is nothing to redo. */
    fun redo(doc: Document): Applied? {
        val entry = redoStack.lastOrNull() ?: return null
        val applied = entry.command.execute(doc)
        redoStack.removeLast()
        undoStack.addLast(Entry(applied.inverse, entry.key, clock.monotonicNs()))
        coalesceOpen = false
        publish()
        return applied
    }

    /** Drops all history (document reloaded). */
    fun clear() {
        undoStack.clear()
        redoStack.clear()
        coalesceOpen = false
        publish()
    }

    private fun canMerge(
        top: Entry,
        key: String?,
        nowNs: Long,
    ): Boolean {
        val sameRun = coalesceOpen && key != null && top.key == key
        return sameRun && nowNs - top.lastPushNs <= coalesceWindowMs * NS_PER_MS
    }

    private fun publish() {
        canUndoState.value = undoStack.isNotEmpty()
        canRedoState.value = redoStack.isNotEmpty()
    }

    // Newest inverse runs first. Flattened so a long typing burst stays one flat batch.
    private fun merge(
        newer: EditCommand,
        older: EditCommand,
    ): EditCommand {
        val olderList = if (older is Batch && older.coalesceKey == null) older.commands else listOf(older)
        return Batch(listOf(newer) + olderList)
    }

    /** Defaults from 03-document-model.md#commands-and-undo. */
    companion object {
        /** Maximum undo entries. */
        const val DEFAULT_CAPACITY = 50

        /** Coalescing window in ms. */
        const val DEFAULT_COALESCE_WINDOW_MS = 1000L

        private const val NS_PER_MS = 1_000_000L
    }
}
