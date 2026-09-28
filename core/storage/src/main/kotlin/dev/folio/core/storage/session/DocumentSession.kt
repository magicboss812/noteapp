package dev.folio.core.storage.session

import dev.folio.core.common.Clock
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.format.codec.PageCodec
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.format.container.FolioEntries
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.Applied
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.UndoManager
import dev.folio.core.storage.work.EntryAutosaver
import dev.folio.core.storage.work.PackResult
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.WorkingCopy
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

/** Called after edits with the pages whose content changed (thumbnail generation, P04). */
fun interface ThumbnailHook {
    /** [pageIds] changed in [doc]. */
    fun pagesChanged(
        doc: Document,
        pageIds: Set<PageId>,
    )
}

/**
 * One open document (03-document-model.md#sessions): working copy, `StateFlow<Document>`, lazily
 * decoded pages (LRU of [maxDecodedPages]; pages with unsaved edits are pinned), undo/redo, entry
 * autosave after 1 s idle, pack on [close], on app stop (via [DocumentSessions]) and every
 * [packIntervalMs] while dirty. All mutations are serialized by one mutex.
 */
@Suppress("TooManyFunctions") // Session API (open/load/execute/undo/redo/save/pack/close) plus its private bookkeeping.
class DocumentSession internal constructor(
    private val copy: WorkingCopy,
    initial: Document,
    private val packer: Packer,
    private val clock: Clock,
    private val dispatchers: FolioDispatchers,
    private val scope: CoroutineScope,
    private val app: ManifestApp,
    private val thumbnails: ThumbnailHook,
    private val onPacked: suspend (PackResult) -> Unit,
    private val maxDecodedPages: Int = DEFAULT_MAX_DECODED_PAGES,
    private val packIntervalMs: Long = DEFAULT_PACK_INTERVAL_MS,
    autosaveDebounceMs: Long = EntryAutosaver.DEFAULT_DEBOUNCE_MS,
) {
    private val mutex = Mutex()
    private val copyLock = Any()
    private val state = MutableStateFlow(initial)
    private val lru = LinkedHashMap<PageId, Unit>(INITIAL_LRU, LOAD_FACTOR, true)
    private val pinned = HashSet<PageId>()
    private val undoManager = UndoManager(clock)
    private var lastEditMs = initial.meta.modifiedMs
    private var packTimer: Job? = null
    private var closed = false
    private val autosaver = EntryAutosaver(scope, dispatchers.io, autosaveDebounceMs, ::writeEntries)

    /** The document; page bodies present in `pageBodies` are the decoded ones. */
    val document: StateFlow<Document> = state.asStateFlow()

    /** Undo availability. */
    val canUndo: StateFlow<Boolean> = undoManager.canUndo

    /** Redo availability. */
    val canRedo: StateFlow<Boolean> = undoManager.canRedo

    /** Document id. */
    val docId get() = copy.docId

    /** Library path of the `.folio` this session saves to (changes after a conflict copy). */
    val path: String get() = copy.base.sourcePath

    /** Number of decoded page bodies held. */
    val decodedPageCount: Int get() = state.value.pageBodies.size

    /** Decodes [ids] (visible range + prefetch) if not loaded. */
    suspend fun loadPages(ids: Collection<PageId>): Outcome<Unit> = mutex.withLock { ensureLoaded(ids.toSet()) }

    /** Applies [command] as one undo step. Invalid commands fail without changing the document. */
    suspend fun execute(command: EditCommand): Outcome<Unit> =
        mutex.withLock {
            check(!closed) { "session is closed" }
            ensureLoaded(command.requiredPages).let { if (it is Outcome.Failure) return@withLock it }
            val applied = apply { command.execute(state.value) } ?: return@withLock Outcome.Failure("invalid command $command")
            undoManager.push(applied)
            commit(applied.doc, command.requiredPages)
            Outcome.Success(Unit)
        }

    /** Reverts the last step (no-op if none). */
    suspend fun undo(): Outcome<Unit> = history { undoManager.nextUndo to { doc -> undoManager.undo(doc) } }

    /** Re-applies the last undone step (no-op if none). */
    suspend fun redo(): Outcome<Unit> = history { undoManager.nextRedo to { doc -> undoManager.redo(doc) } }

    /** Writes pending entries to the working copy now. */
    suspend fun save(): Outcome<Unit> = autosaver.flush()

    /** Saves and packs the working copy into the `.folio`. */
    suspend fun pack(): Outcome<PackResult> {
        val saved = autosaver.flush()
        if (saved is Outcome.Failure) return saved
        val result = withContext(dispatchers.io) { synchronized(copyLock) { packer.pack(copy) } }
        if (result is Outcome.Success) onPacked(result.value)
        return result
    }

    /** Final pack; the session cannot be used afterwards. */
    suspend fun close(): Outcome<PackResult> {
        mutex.withLock { closed = true }
        packTimer?.cancel()
        return pack()
    }

    private suspend fun history(next: () -> Pair<EditCommand?, (Document) -> Applied?>): Outcome<Unit> =
        mutex.withLock {
            check(!closed) { "session is closed" }
            val (command, run) = next()
            if (command == null) return@withLock Outcome.Success(Unit)
            ensureLoaded(command.requiredPages).let { if (it is Outcome.Failure) return@withLock it }
            val applied = run(state.value) ?: return@withLock Outcome.Success(Unit)
            commit(applied.doc, command.requiredPages)
            Outcome.Success(Unit)
        }

    // Commands signal invalid input with IllegalArgument/IllegalState; the session is the UI boundary.
    private inline fun apply(block: () -> Applied): Applied? =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            FolioLog.w(TAG, "command rejected: ${e.message}")
            null
        } catch (e: IllegalStateException) {
            FolioLog.w(TAG, "command rejected: ${e.message}")
            null
        }

    private suspend fun ensureLoaded(ids: Set<PageId>): Outcome<Unit> {
        var doc = state.value
        for (id in ids) {
            if (doc.pageRef(id) == null || doc.pageBodies.containsKey(id)) continue
            when (val r = withContext(dispatchers.io) { synchronized(copyLock) { DocumentCodec.readPage(copy, id) } }) {
                is Outcome.Failure -> return r
                is Outcome.Success -> doc = doc.copy(pageBodies = doc.pageBodies.put(id, r.value))
            }
        }
        ids.forEach { if (doc.pageBodies.containsKey(it)) lru[it] = Unit }
        state.value = evict(doc, protect = ids)
        return Outcome.Success(Unit)
    }

    private fun commit(
        after: Document,
        touched: Set<PageId>,
    ) {
        val before = state.value
        lastEditMs = clock.nowMs()
        val changedPages = HashSet<PageId>()
        for ((id, body) in after.pageBodies) {
            if (before.pageBodies[id] !== body) {
                changedPages += id
                lru[id] = Unit
            }
        }
        val removedPages = before.pages.map { it.id }.toSet() - after.pages.map { it.id }.toSet()
        removedPages.forEach { lru.remove(it) }
        (changedPages + removedPages).forEach { id ->
            synchronized(pinned) { pinned += id }
            autosaver.schedule(FolioEntries.page(id)) { pageBytes(id) }
        }
        scheduleFlows(before, after)
        autosaver.schedule(FolioEntries.MANIFEST) { manifestBytes() }
        autosaver.schedule(FolioEntries.SEARCH_TEXT) { DocumentEntries.searchText(state.value).encodeToByteArray() }
        state.value = evict(after, protect = touched + changedPages)
        if (changedPages.isNotEmpty()) thumbnails.pagesChanged(state.value, changedPages)
        startPackTimer()
    }

    private fun scheduleFlows(
        before: Document,
        after: Document,
    ) {
        val changed = after.flows.filter { (id, flow) -> before.flows[id] !== flow }.keys
        val removed = before.flows.keys - after.flows.keys
        for (id in changed + removed) {
            autosaver.schedule(FolioEntries.flowText(id)) { flowEntry(id, 0) }
            autosaver.schedule(FolioEntries.flowStyle(id)) { flowEntry(id, 1) }
        }
    }

    private fun flowEntry(
        id: FlowId,
        index: Int,
    ): ByteArray? = state.value.flows[id]?.let { DocumentEntries.encodeFlow(it)[index].second }

    private fun pageBytes(id: PageId): ByteArray? {
        val doc = state.value
        val body = doc.pageBodies[id] ?: return if (doc.pageRef(id) == null) null else unchangedPage(id)
        return PageCodec.encode(body)
    }

    // A pinned page is never evicted; this only covers a page that was removed and restored meanwhile.
    private fun unchangedPage(id: PageId): ByteArray? =
        (copy.read(FolioEntries.page(id), FolioEntries.MAX_PAYLOAD_BYTES) as? Outcome.Success)?.value

    private fun manifestBytes(): ByteArray {
        val doc = state.value
        return DocumentEntries.encodeManifest(doc.copy(meta = doc.meta.copy(modifiedMs = lastEditMs)), app)
    }

    private fun writeEntries(entries: Map<String, ByteArray?>): Outcome<Unit> {
        val result = synchronized(copyLock) { copy.writeEntries(entries) }
        if (result is Outcome.Success) {
            val written = entries.keys.filter { it.startsWith("pages/") }.map { PageId(it.removePrefix("pages/").removeSuffix(".pb")) }
            synchronized(pinned) { pinned.removeAll(written.toSet()) }
        }
        return result
    }

    private fun evict(
        doc: Document,
        protect: Set<PageId>,
    ): Document {
        if (doc.pageBodies.size <= maxDecodedPages) return doc
        val bodies = doc.pageBodies.builder()
        val keep = synchronized(pinned) { pinned + protect }
        val iterator = lru.keys.iterator()
        while (bodies.size > maxDecodedPages && iterator.hasNext()) {
            val id = iterator.next()
            if (id in keep) continue
            bodies.remove(id)
            iterator.remove()
        }
        // Bodies loaded without LRU tracking (initial document) go first.
        bodies.keys.filter { it !in lru && it !in keep }.forEach { if (bodies.size > maxDecodedPages) bodies.remove(it) }
        return doc.copy(pageBodies = bodies.build())
    }

    private fun startPackTimer() {
        if (packTimer?.isActive == true) return
        packTimer =
            scope.launch {
                delay(packIntervalMs)
                val r = pack()
                if (r is Outcome.Failure) FolioLog.w(TAG, "timed pack failed: ${r.message}", r.cause)
            }
    }

    /** Defaults from 03-document-model.md#sessions and 04-file-format.md#write-protocol. */
    companion object {
        /** Decoded pages kept in memory. */
        const val DEFAULT_MAX_DECODED_PAGES = 30

        /** Pack interval while dirty. */
        const val DEFAULT_PACK_INTERVAL_MS = 30_000L
        private const val TAG = "DocumentSession"
        private const val INITIAL_LRU = 64
        private const val LOAD_FACTOR = 0.75f
    }
}
