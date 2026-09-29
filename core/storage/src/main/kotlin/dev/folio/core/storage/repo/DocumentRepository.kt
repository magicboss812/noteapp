package dev.folio.core.storage.repo

import dev.folio.core.common.Clock
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.map
import dev.folio.core.format.container.DocumentEntries
import dev.folio.core.format.container.FolioContainerWriter
import dev.folio.core.format.manifest.Manifest
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.Background
import dev.folio.core.model.DocId
import dev.folio.core.model.Document
import dev.folio.core.model.DocumentMeta
import dev.folio.core.model.Page
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.storage.index.IndexDao
import dev.folio.core.storage.index.LibraryScanner
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentMapOf
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toPersistentList
import kotlinx.collections.immutable.toPersistentMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/** What a new document looks like (new-note flow). */
data class NewDocumentSpec(
    val folder: String,
    val title: String,
    val pageSpec: PageSpec,
    val background: Background,
    val pageCount: Int = 1,
)

/** A document file created or changed by the repository. */
data class DocumentRef(
    val docId: DocId,
    val path: String,
)

/**
 * File-level document operations (09-storage-library.md#repositories). Paths are library-relative.
 * Operations act on closed documents; an open document changes through its session. Every operation
 * updates the index directly (no rescan).
 */
@Suppress("TooManyFunctions") // One method per operation listed in 09-storage-library.md#repositories.
class DocumentRepository(
    private val fs: FolioFs,
    private val scanner: LibraryScanner,
    private val dao: IndexDao,
    private val clock: Clock,
    private val dispatchers: FolioDispatchers,
    private val app: ManifestApp,
) {
    private val rewriter = ContainerRewriter(fs)
    private val trash = Bin(fs, clock)
    private val binState = MutableStateFlow<List<BinEntry>>(emptyList())

    /** Bin contents, newest first; refreshed by every bin operation and [refreshBin]. */
    val bin: StateFlow<List<BinEntry>> = binState.asStateFlow()

    /** Creates a `.folio` in [spec]'s folder named after its title. */
    suspend fun create(spec: NewDocumentSpec): Outcome<DocumentRef> =
        io {
            val now = clock.nowMs()
            val pages = List(spec.pageCount.coerceAtLeast(1)) { Page(PageId.random(), spec.pageSpec, spec.background, persistentListOf()) }
            val doc =
                Document(
                    meta =
                        DocumentMeta(
                            DocId.random(),
                            spec.title,
                            now,
                            now,
                            persistentSetOf(),
                            false,
                            Manifest.CURRENT_VERSION,
                            spec.pageSpec,
                            spec.background,
                        ),
                    pages = pages.map { it.toRef() }.toPersistentList(),
                    flows = persistentMapOf(),
                    assets = persistentMapOf(),
                    pageBodies = pages.associateBy { it.id }.toPersistentMap(),
                )
            val path = FileNames.unique(fs, spec.folder, FileNames.stemOf(spec.title), FileNames.FOLIO_EXT)
            fs
                .mkdirs(spec.folder)
                .flatMap { fs.writeAtomic(path) { out -> FolioContainerWriter.write(out, DocumentEntries.all(doc, app, emptyMap())) } }
                .map { DocumentRef(doc.meta.id, path) }
        }.also { indexed(it) }

    /** Copies [path] beside it with a new document id; the title follows the new file name ("X (2)"). */
    suspend fun duplicate(path: String): Outcome<DocumentRef> =
        io {
            val stem = FileNames.nameOf(path).removeSuffix(FileNames.FOLIO_EXT)
            val target = FileNames.unique(fs, FileNames.parentOf(path), stem, FileNames.FOLIO_EXT)
            val id = DocId.random()
            val title = FileNames.nameOf(target).removeSuffix(FileNames.FOLIO_EXT)
            val now = clock.nowMs()
            rewriter
                .rewrite(path, target, ManifestPatch(id = id.value, title = title, createdMs = now, modifiedMs = now))
                .map { DocumentRef(id, target) }
        }.also { indexed(it) }

    /** Sets the title and renames the file to match (collision -> " (2)"). */
    suspend fun rename(
        path: String,
        title: String,
    ): Outcome<String> =
        io {
            val stem = FileNames.stemOf(title)
            val newName = stem + FileNames.FOLIO_EXT
            val oldName = FileNames.nameOf(path)
            val parent = FileNames.parentOf(path)
            // Rewrite in place, then rename: there is never a second file with the same docId.
            rewriter.rewrite(path, path, ManifestPatch(title = title)).flatMap {
                when {
                    newName == oldName -> {
                        Outcome.Success(path)
                    }

                    // Case-only change on the case-insensitive shared storage: go through a temp name.
                    newName.equals(oldName, ignoreCase = true) -> {
                        val temp = FileNames.join(parent, ".rename-${clock.nowMs()}.tmp")
                        val target = FileNames.join(parent, newName)
                        fs.move(path, temp).flatMap { fs.move(temp, target) }.map { target }
                    }

                    else -> {
                        val target = FileNames.unique(fs, parent, stem, FileNames.FOLIO_EXT)
                        fs.move(path, target).map { target }
                    }
                }
            }
        }.also { result -> if (result is Outcome.Success) reindex(removed = listOf(path), added = result.value) }

    /** Moves the file into [folder] (collision -> " (2)"). */
    suspend fun move(
        path: String,
        folder: String,
    ): Outcome<String> =
        io {
            val stem = FileNames.nameOf(path).removeSuffix(FileNames.FOLIO_EXT)
            val target = FileNames.unique(fs, folder, stem, FileNames.FOLIO_EXT)
            fs.move(path, target).map { target }
        }.also { result -> if (result is Outcome.Success) reindex(removed = listOf(path), added = result.value) }

    /** Moves the file to the bin. */
    suspend fun delete(path: String): Outcome<BinEntry> {
        val docId = dao.document(path)?.docId
        return io { trash.moveToBin(path, docId) }
            .also { if (it is Outcome.Success) scanner.removeFiles(listOf(path)) }
            .also { refreshBin() }
    }

    /** Moves a bin entry back to its original path; returns where it landed. */
    suspend fun restore(entry: BinEntry): Outcome<String> =
        io { trash.restore(entry) }
            .also { result ->
                if (result is Outcome.Success) {
                    if (entry.isFolder) scanner.incrementalScan() else scanner.indexFile(result.value)
                }
            }.also { refreshBin() }

    /** Permanently deletes one bin entry (user confirmed). */
    suspend fun deleteForever(entry: BinEntry): Outcome<Unit> = io { trash.deleteForever(entry) }.also { refreshBin() }

    /** Permanently deletes everything in the bin (user confirmed). Returns the count. */
    suspend fun emptyBin(): Outcome<Int> = io { trash.empty() }.also { refreshBin() }

    /** Deletes bin entries older than [retentionDays]; null = keep forever. Returns the count. */
    suspend fun purgeBin(retentionDays: Int?): Outcome<Int> =
        if (retentionDays == null) Outcome.Success(0) else io { trash.purgeOlderThan(retentionDays) }.also { refreshBin() }

    /** Re-reads the bin folder. */
    suspend fun refreshBin() {
        binState.value = withContext(dispatchers.io) { trash.entries() }
    }

    internal suspend fun moveToBin(
        path: String,
        docId: String?,
    ): Outcome<BinEntry> = io { trash.moveToBin(path, docId) }.also { refreshBin() }

    private suspend fun <T> io(block: () -> Outcome<T>): Outcome<T> = withContext(dispatchers.io) { block() }

    private suspend fun indexed(result: Outcome<DocumentRef>) {
        if (result is Outcome.Success) scanner.indexFile(result.value.path)
    }

    private suspend fun reindex(
        removed: List<String>,
        added: String,
    ) {
        scanner.removeFiles(removed.filter { it != added })
        scanner.indexFile(added)
    }
}
