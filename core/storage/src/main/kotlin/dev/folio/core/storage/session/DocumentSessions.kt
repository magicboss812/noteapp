package dev.folio.core.storage.session

import dev.folio.core.common.Clock
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.map
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.DocId
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.work.PackResult
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopyStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Opens and tracks document sessions. One session per document (a second pane shows the same session
 * read-only). Packs every open session when the app goes to the background ([onAppStop], called by
 * :app from ProcessLifecycleOwner) and keeps the index current after packs.
 */
class DocumentSessions(
    private val store: WorkingCopyStore,
    private val packer: Packer,
    private val scanner: LibraryScanner,
    private val clock: Clock,
    private val dispatchers: FolioDispatchers,
    private val scope: CoroutineScope,
    private val app: ManifestApp,
    private val thumbnails: ThumbnailHook = ThumbnailHook { _, _ -> },
) {
    private val lock = Mutex()
    private val open = LinkedHashMap<DocId, DocumentSession>()

    /** Opens the `.folio` at library [path], reusing a valid working copy, or returns its open session. */
    suspend fun open(path: String): Outcome<DocumentSession> =
        lock.withLock {
            open.values.firstOrNull { it.path == path }?.let { return@withLock Outcome.Success(it) }
            withContext(dispatchers.io) {
                store.open(path).flatMap { copy ->
                    if (open.containsKey(copy.docId)) {
                        return@flatMap Outcome.Failure("document ${copy.docId.value} is already open from another path")
                    }
                    // Each session backs up the file as it was opened (A-012), even when the copy is reused.
                    copy.updateBase(copy.base.copy(backupDone = false)).flatMap {
                        DocumentCodec.readDocument(copy).map { doc ->
                            DocumentSession(copy, doc, packer, clock, dispatchers, scope, app, thumbnails, ::indexAfterPack)
                        }
                    }
                }
            }.also { if (it is Outcome.Success) open[it.value.docId] = it.value }
        }

    /** Final pack of [session], then forgets it. Not cancellable: leaving a screen must not skip the pack. */
    suspend fun close(session: DocumentSession): Outcome<PackResult> =
        withContext(NonCancellable) {
            val result = session.close()
            lock.withLock { open.remove(session.docId) }
            result
        }

    /** Packs every open session (app `onStop`). */
    suspend fun packAll() {
        val sessions = lock.withLock { open.values.toList() }
        for (s in sessions) {
            val r = s.pack()
            if (r is Outcome.Failure) FolioLog.w(TAG, "pack of ${s.path} failed: ${r.message}", r.cause)
        }
    }

    /** App went to the background (ProcessLifecycleOwner onStop): packs all sessions without blocking. */
    fun onAppStop() {
        scope.launch { packAll() }
    }

    private suspend fun indexAfterPack(result: PackResult) {
        when (result) {
            is PackResult.Packed -> scanner.indexFile(result.path)
            is PackResult.ConflictCopy -> scanner.indexFile(result.path)
            PackResult.Clean, PackResult.SourceMissing -> Unit
        }
    }

    private companion object {
        const val TAG = "DocumentSessions"
    }
}
