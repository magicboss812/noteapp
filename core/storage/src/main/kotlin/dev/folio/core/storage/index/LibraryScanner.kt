package dev.folio.core.storage.index

import androidx.room.withTransaction
import dev.folio.core.common.Clock
import dev.folio.core.common.FolioFs
import dev.folio.core.common.FolioLog
import dev.folio.core.common.FsEntry
import dev.folio.core.common.Outcome
import dev.folio.core.common.getOrNull
import dev.folio.core.format.FormatError
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioEntries
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Counts of one scan. */
data class ScanStats(
    val files: Int,
    val indexed: Int,
    val removed: Int,
    val folders: Int,
)

/**
 * Keeps the index in sync with the library folder (09-storage-library.md#scanning): walks visible
 * folders, reads only `manifest.json` and `search/text.txt` of new or changed `.folio` files (ZipFile
 * random access) and writes in transactions of [batchSize] documents. Also deletes stale temp files of
 * interrupted packs (04-file-format.md#crash-recovery). Run on the io dispatcher.
 */
class LibraryScanner(
    private val fs: FolioFs,
    private val db: IndexDb,
    private val clock: Clock,
    private val batchSize: Int = DEFAULT_BATCH,
) {
    private val dao = db.dao()

    /** Re-reads every document. */
    suspend fun fullScan(): ScanStats = scan(force = true)

    /** Re-reads documents whose size or mtime changed; removes rows of vanished files. */
    suspend fun incrementalScan(): ScanStats = scan(force = false)

    /** Indexes one file after our own write (no walk). */
    suspend fun indexFile(path: String) {
        val stat = fs.stat(path) ?: return removeFiles(listOf(path))
        val doc = read(stat)
        db.withTransaction { write(listOf(doc)) }
    }

    /** Removes rows of files that no longer exist (our own delete or move). */
    suspend fun removeFiles(paths: List<String>) {
        db.withTransaction { delete(paths) }
    }

    private suspend fun scan(force: Boolean): ScanStats {
        val tree = Tree().also { walk("", it) }
        val stamps = dao.fileStamps().associateBy { it.path }
        val changed =
            tree.files.filter { f ->
                val s = stamps[f.path]
                force || s == null || s.fileSize != f.sizeBytes || s.fileMtime != f.modifiedMs
            }
        val present = tree.files.mapTo(HashSet()) { it.path }
        val removed = stamps.keys.filter { it !in present }
        db.withTransaction {
            delete(removed)
            dao.deleteFolders(dao.folderPaths().filter { it !in tree.folderPaths() })
            dao.upsertFolders(tree.folders.map(::folderRow))
        }
        for (batch in changed.chunked(batchSize)) {
            val docs = batch.map(::read)
            db.withTransaction { write(docs) }
        }
        return ScanStats(tree.files.size, changed.size, removed.size, tree.folders.size)
    }

    private suspend fun delete(paths: List<String>) {
        for (chunk in paths.chunked(SQL_VARS)) {
            dao.deleteFts(chunk)
            dao.deleteDocuments(chunk) // tags and links cascade
        }
    }

    private suspend fun write(docs: List<IndexedDoc>) {
        val paths = docs.map { it.row.path }
        for (chunk in paths.chunked(SQL_VARS)) {
            dao.deleteFts(chunk)
            dao.deleteTags(chunk)
            dao.deleteLinks(chunk)
        }
        dao.upsertDocuments(docs.map { it.row })
        dao.insertTags(docs.flatMap { d -> d.tags.map { TagRow(d.row.path, it) } })
        dao.insertLinks(docs.flatMap { d -> d.links.map { LinkRow(d.row.path, it) } })
        dao.insertFts(docs.map { DocFtsRow(it.row.path, it.row.title, it.body) })
    }

    private fun walk(
        dir: String,
        tree: Tree,
    ) {
        val children = (fs.list(dir) as? Outcome.Success)?.value ?: return
        for (child in children) {
            val name = child.path.substringAfterLast('/')
            when {
                name.startsWith(".") -> {
                    if (!child.isDirectory) deleteIfStaleTemp(child, name)
                }

                child.isDirectory -> {
                    tree.folders += child
                    walk(child.path, tree)
                }

                name.endsWith(FOLIO_EXT) -> {
                    tree.files += child
                }
            }
        }
    }

    private fun deleteIfStaleTemp(
        entry: FsEntry,
        name: String,
    ) {
        if (name.endsWith(".tmp") && FOLIO_EXT in name && clock.nowMs() - entry.modifiedMs > STALE_TMP_MS) {
            fs.delete(entry.path)
        }
    }

    private fun read(file: FsEntry): IndexedDoc {
        val local = requireNotNull(fs.localFile(file.path)) { "library FolioFs must be file-backed" }
        val result =
            FolioContainerReader.open(local).let { opened ->
                when (opened) {
                    is Outcome.Failure -> {
                        opened
                    }

                    is Outcome.Success -> {
                        opened.value.use { reader ->
                            when (val m = DocumentCodec.readManifest(reader)) {
                                is Outcome.Failure -> {
                                    m
                                }

                                is Outcome.Success -> {
                                    val body =
                                        reader
                                            .read(FolioEntries.SEARCH_TEXT, MAX_SEARCH_TEXT)
                                            .getOrNull()
                                            ?.decodeToString()
                                            .orEmpty()
                                    Outcome.Success(Triple(m.value, body, FolioEntries.COVER in reader.names()))
                                }
                            }
                        }
                    }
                }
            }
        return when (result) {
            is Outcome.Success -> {
                val (manifest, body, hasCover) = result.value
                IndexedDoc(okRow(file, manifest, hasCover), manifest.tags.distinct(), manifest.links.distinct(), body)
            }

            is Outcome.Failure -> {
                FolioLog.w(TAG, "cannot index ${file.path}: ${result.message}")
                val status = if (result.cause is FormatError.FormatTooNew) DocStatus.TOO_NEW else DocStatus.CORRUPT
                IndexedDoc(badRow(file, status), emptyList(), emptyList(), "")
            }
        }
    }

    private fun folderRow(dir: FsEntry): FolderRow {
        val tint =
            fs
                .readBytes("${dir.path}/$FOLDER_JSON")
                .getOrNull()
                ?.let { runCatching { FolderJson.decodeFromString(FolderSettings.serializer(), it.decodeToString()).tint }.getOrNull() }
        return FolderRow(dir.path, parentOf(dir.path), dir.path.substringAfterLast('/'), tint, dir.modifiedMs)
    }

    private class Tree {
        val files = ArrayList<FsEntry>()
        val folders = ArrayList<FsEntry>()

        fun folderPaths(): Set<String> = folders.mapTo(HashSet()) { it.path }
    }

    private class IndexedDoc(
        val row: DocumentRow,
        val tags: List<String>,
        val links: List<String>,
        val body: String,
    )

    /** Constants. */
    companion object {
        /** Documents per write transaction. */
        const val DEFAULT_BATCH = 200

        /** Optional per-folder settings file. */
        const val FOLDER_JSON = ".folder.json"
        internal const val FOLIO_EXT = ".folio"
        private const val TAG = "LibraryScanner"
        private const val SQL_VARS = 500
        private const val STALE_TMP_MS = 10L * 60 * 1000
        private const val MAX_SEARCH_TEXT = 16L * 1024 * 1024

        /** Parent folder of a library path ("" for the root). */
        fun parentOf(path: String): String = path.substringBeforeLast('/', "")
    }
}

@Serializable
private data class FolderSettings(
    val tint: String? = null,
)

private val FolderJson = Json { ignoreUnknownKeys = true }
