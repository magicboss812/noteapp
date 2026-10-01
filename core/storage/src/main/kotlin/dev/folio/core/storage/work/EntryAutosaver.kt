package dev.folio.core.storage.work

import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.outcomeOf
import dev.folio.core.storage.session.SaveState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Debounced entry autosave (04-file-format.md#write-protocol step 2): changed entries are written
 * after [debounceMs] without further changes. Providers are evaluated at write time so a burst of
 * edits encodes each entry once. A provider returning null deletes the entry.
 */
class EntryAutosaver(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val write: (Map<String, ByteArray?>) -> Outcome<Unit>,
) {
    private val lock = Mutex()
    private val pending = LinkedHashMap<String, () -> ByteArray?>()
    private var timer: Job? = null
    private val mutableState = MutableStateFlow(SaveState.Saved)

    /** Saved when nothing waits, Saving while changes wait or are written, Error after a failed write. */
    val state: StateFlow<SaveState> = mutableState.asStateFlow()

    /** Marks [name] changed; restarts the idle timer. */
    fun schedule(
        name: String,
        provider: () -> ByteArray?,
    ) {
        // Same lock as the state update in flush: a flush finishing now cannot report Saved over this change.
        synchronized(pending) {
            pending[name] = provider
            mutableState.value = SaveState.Saving
        }
        timer?.cancel()
        timer =
            scope.launch {
                delay(debounceMs)
                flush()
            }
    }

    /** Writes everything pending now (before pack, close, onStop). Returns the write result. */
    suspend fun flush(): Outcome<Unit> =
        lock.withLock {
            val batch = synchronized(pending) { LinkedHashMap(pending).also { pending.clear() } }
            if (batch.isEmpty()) return@withLock Outcome.Success(Unit)
            val result =
                withContext(io) {
                    // A throwing provider (encoder bug) must not lose the batch or crash the scope.
                    outcomeOf("encode autosave entries") { batch.mapValues { (_, provider) -> provider() } }.flatMap(write)
                }
            if (result is Outcome.Failure) {
                FolioLog.w(TAG, "autosave failed: ${result.message}", result.cause)
                // Keep the entries so the next flush retries them (newer providers win).
                synchronized(pending) {
                    batch.forEach { (k, v) -> pending.putIfAbsent(k, v) }
                    mutableState.value = SaveState.Error
                }
            } else {
                synchronized(pending) { if (pending.isEmpty()) mutableState.value = SaveState.Saved }
            }
            result
        }

    /** Reports the outcome of a pack: a failure shows as [SaveState.Error], a success clears it unless changes wait. */
    fun packFinished(success: Boolean) {
        synchronized(pending) {
            mutableState.value =
                when {
                    !success -> SaveState.Error
                    pending.isEmpty() -> SaveState.Saved
                    else -> SaveState.Saving
                }
        }
    }

    /** True while changes wait for the timer. */
    val hasPending: Boolean get() = synchronized(pending) { pending.isNotEmpty() }

    /** Defaults. */
    companion object {
        /** Idle time before entries are written. */
        const val DEFAULT_DEBOUNCE_MS = 1000L
        private const val TAG = "Autosave"
    }
}
