package dev.folio.core.storage.index

import android.os.FileObserver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

/**
 * Live updates for folders on screen (09-storage-library.md#scanning): one FileObserver per watched
 * folder; relevant events are debounced into one [onChanged] call (the caller runs an incremental scan).
 * Main-thread API; events arrive on the observer thread and only schedule work on [scope].
 */
class LibraryWatcher(
    private val root: File,
    private val scope: CoroutineScope,
    private val debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    private val onChanged: suspend () -> Unit,
) {
    private val observers = HashMap<String, FileObserver>()
    private var pending: Job? = null

    /** Watches exactly [folders] (library-relative, "" = root). */
    fun watch(folders: Set<String>) {
        (observers.keys - folders).forEach { observers.remove(it)?.stopWatching() }
        (folders - observers.keys).forEach { rel ->
            val dir = if (rel.isEmpty()) root else File(root, rel)
            val observer =
                object : FileObserver(dir, MASK) {
                    override fun onEvent(
                        event: Int,
                        path: String?,
                    ) {
                        if (isRelevant(path)) schedule()
                    }
                }
            observer.startWatching()
            observers[rel] = observer
        }
    }

    /** Stops all observers. */
    fun stop() = watch(emptySet())

    @Synchronized
    private fun schedule() {
        pending?.cancel()
        pending =
            scope.launch {
                delay(debounceMs)
                onChanged()
            }
    }

    /** Constants and the event filter. */
    companion object {
        /** Quiet time before a rescan. */
        const val DEFAULT_DEBOUNCE_MS = 300L
        private const val MASK =
            FileObserver.CREATE or FileObserver.CLOSE_WRITE or FileObserver.DELETE or FileObserver.MOVED_FROM or FileObserver.MOVED_TO

        /** Hidden names (our temp files, `.trash`, `.folder.json` edits aside) never trigger a rescan. */
        fun isRelevant(name: String?): Boolean = !name.isNullOrEmpty() && !name.startsWith(".")
    }
}
