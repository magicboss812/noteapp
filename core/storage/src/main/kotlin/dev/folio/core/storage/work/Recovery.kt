package dev.folio.core.storage.work

import dev.folio.core.common.Clock
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.getOrNull
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.model.DocId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** Something start-up recovery did that the UI reports once (04-file-format.md#crash-recovery). */
sealed interface RecoveryEvent {
    /** Id of the affected document. */
    val docId: DocId

    /** Unsaved changes were packed into the document. */
    data class Recovered(
        override val docId: DocId,
        val path: String,
    ) : RecoveryEvent

    /** The document changed externally meanwhile: the changes went to a conflict copy. */
    data class Conflict(
        override val docId: DocId,
        val path: String,
    ) : RecoveryEvent

    /** The document no longer exists; its working copy is kept (7 days) and offered as "Recovered: <title>". */
    data class Orphaned(
        override val docId: DocId,
        val title: String,
    ) : RecoveryEvent
}

/** Pending recovery events for the UI; the UI consumes each after showing it. */
class RecoveryEvents {
    private val state = MutableStateFlow<List<RecoveryEvent>>(emptyList())

    /** Events not yet shown. */
    val pending: StateFlow<List<RecoveryEvent>> = state.asStateFlow()

    /** Adds events. */
    fun publish(events: List<RecoveryEvent>) = state.update { it + events }

    /** Marks [event] as shown. */
    fun consume(event: RecoveryEvent) = state.update { it - event }
}

/** Start-up recovery: packs dirty working copies, reports conflicts and orphans, drops old orphans. */
class Recovery(
    private val store: WorkingCopyStore,
    private val packer: Packer,
    private val clock: Clock,
    private val events: RecoveryEvents,
) {
    private val lock = Any()
    private var done = false

    /**
     * [run] on the first call of the process only (the library entry once access is granted, and
     * [dev.folio.core.storage.session.DocumentSessions] before its first open). Concurrent callers
     * wait until that run has finished, so no session opens a copy recovery is still packing.
     */
    fun runOnce(): List<RecoveryEvent> =
        synchronized(lock) {
            if (done) return emptyList()
            done = true
            run()
        }

    /** Packs dirty working copies; call before any document opens. Blocking: io dispatcher. */
    fun run(): List<RecoveryEvent> {
        val out = ArrayList<RecoveryEvent>()
        for (copy in store.all()) {
            if (copy.isDirty) recover(copy)?.let(out::add)
        }
        events.publish(out)
        return out
    }

    private fun recover(copy: WorkingCopy): RecoveryEvent? {
        if (!copy.hasUnsavedEdits) {
            // Only thumbnails written after the last pack: regenerable, never worth a pack, conflict or notice.
            val r = copy.updateBase(copy.base.copy(dirtyEntries = emptySet()))
            if (r is Outcome.Failure) FolioLog.w(TAG, "recovery reset of ${copy.docId.value} failed: ${r.message}", r.cause)
            return null
        }
        return when (val r = packer.pack(copy)) {
            is Outcome.Failure -> {
                FolioLog.w(TAG, "recovery pack of ${copy.docId.value} failed: ${r.message}", r.cause)
                null
            }

            is Outcome.Success -> {
                handle(copy, r.value)
            }
        }
    }

    private fun handle(
        copy: WorkingCopy,
        result: PackResult,
    ): RecoveryEvent? =
        when (result) {
            is PackResult.Packed -> RecoveryEvent.Recovered(copy.docId, result.path)
            is PackResult.ConflictCopy -> RecoveryEvent.Conflict(copy.docId, result.path)
            PackResult.Clean -> null
            PackResult.SourceMissing -> orphan(copy)
        }

    // The 7 days count from the first time the orphan was reported to the user, never from older
    // document timestamps, so unsaved work is always offered before it expires.
    private fun orphan(copy: WorkingCopy): RecoveryEvent? {
        val since = copy.base.orphanSinceMs
        if (since == null) {
            copy.updateBase(copy.base.copy(orphanSinceMs = clock.nowMs()))
        } else if (clock.nowMs() - since > ORPHAN_KEEP_MS) {
            store.discard(copy.docId)
            return null
        }
        val title =
            DocumentCodec
                .readManifest(copy)
                .getOrNull()
                ?.title
                .orEmpty()
        return RecoveryEvent.Orphaned(copy.docId, title)
    }

    private companion object {
        const val TAG = "Recovery"
        const val ORPHAN_KEEP_MS = 7L * 24 * 60 * 60 * 1000
    }
}
