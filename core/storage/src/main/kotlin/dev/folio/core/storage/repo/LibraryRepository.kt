package dev.folio.core.storage.repo

import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.common.flatMap
import dev.folio.core.common.map
import dev.folio.core.storage.index.DocumentRow
import dev.folio.core.storage.index.FolderRow
import dev.folio.core.storage.index.FtsQuery
import dev.folio.core.storage.index.IndexDao
import dev.folio.core.storage.index.LibraryScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Which documents a list shows (the bin comes from [DocumentRepository.bin]). */
sealed interface DocumentFilter {
    /** Documents directly in [folder] ("" = root). */
    data class InFolder(
        val folder: String,
    ) : DocumentFilter

    /** Favorites anywhere. */
    data object Favorites : DocumentFilter

    /** Documents with [tag]. */
    data class Tagged(
        val tag: String,
    ) : DocumentFilter
}

/** List order. */
enum class DocumentSort { RECENT, NAME, CREATED }

/**
 * Library views and organization (09-storage-library.md#repositories): folders, lists, search, tags,
 * favorites. Reads come from the index; writes go to the files and then update the index.
 */
@Suppress("TooManyFunctions") // One method per operation listed in 09-storage-library.md#repositories.
class LibraryRepository(
    private val fs: FolioFs,
    private val scanner: LibraryScanner,
    private val dao: IndexDao,
    private val dispatchers: FolioDispatchers,
    private val documents: DocumentRepository,
) {
    private val rewriter = ContainerRewriter(fs)

    /** All visible folders ordered by path (the UI builds the tree from `parentPath`). */
    fun folderTree(): Flow<List<FolderRow>> = dao.folders()

    /** Documents for [filter] in [sort] order. */
    fun documents(
        filter: DocumentFilter,
        sort: DocumentSort = DocumentSort.RECENT,
    ): Flow<List<DocumentRow>> {
        val source =
            when (filter) {
                is DocumentFilter.InFolder -> dao.documentsIn(filter.folder)
                DocumentFilter.Favorites -> dao.favorites()
                is DocumentFilter.Tagged -> dao.documentsWithTag(filter.tag)
            }
        return source.map { rows -> sorted(rows, sort) }
    }

    /** Prefix search over title and text; title matches first, then newest. Blank queries give nothing. */
    fun search(query: String): Flow<List<DocumentRow>> =
        flow {
            val match = FtsQuery.of(query)
            if (match == null) {
                emit(emptyList())
                return@flow
            }
            val tokens = match.split(' ').map { it.removeSuffix("*") }
            val rows = withContext(dispatchers.io) { dao.search(match) }
            emit(rows.sortedByDescending { row -> titleHit(row.title, tokens) })
        }

    /** All tags in use. */
    fun tags(): Flow<List<String>> = dao.allTags()

    /** Marks a closed document as favorite (manifest rewrite). */
    suspend fun setFavorite(
        path: String,
        favorite: Boolean,
    ): Outcome<Unit> = patch(path, ManifestPatch(favorite = favorite))

    /** Replaces the tags of a closed document. */
    suspend fun setTags(
        path: String,
        tags: Set<String>,
    ): Outcome<Unit> =
        patch(
            path,
            ManifestPatch(
                tags =
                    tags
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                        .sorted(),
            ),
        )

    /** Creates folder [name] in [parent]; returns its path (collision -> " (2)"). */
    suspend fun createFolder(
        parent: String,
        name: String,
    ): Outcome<String> =
        folderOp {
            val path = FileNames.unique(fs, parent, FileNames.stemOf(name), "")
            fs.mkdirs(path).map { path }
        }

    /** Renames a folder; returns the new path. */
    suspend fun renameFolder(
        path: String,
        name: String,
    ): Outcome<String> =
        folderOp {
            val target = FileNames.unique(fs, FileNames.parentOf(path), FileNames.stemOf(name), "")
            fs.move(path, target).map { target }
        }

    /** Moves a folder into [parent]; returns the new path. */
    suspend fun moveFolder(
        path: String,
        parent: String,
    ): Outcome<String> =
        folderOp {
            require(parent != path && !parent.startsWith("$path/")) { "cannot move a folder into itself" }
            val target = FileNames.unique(fs, parent, FileNames.nameOf(path), "")
            fs.move(path, target).map { target }
        }

    /** Sets the folder tint (`.folder.json`); null removes it. */
    suspend fun setFolderTint(
        path: String,
        tint: String?,
    ): Outcome<Unit> =
        folderOp {
            val json = JsonObject(if (tint == null) emptyMap() else mapOf("tint" to JsonPrimitive(tint)))
            fs.writeBytesAtomic("$path/${LibraryScanner.FOLDER_JSON}", json.toString().encodeToByteArray())
        }

    /** Moves a folder with its contents to the bin. */
    suspend fun deleteFolder(path: String): Outcome<BinEntry> = documents.moveToBin(path, null).also { scanner.incrementalScan() }

    private suspend fun patch(
        path: String,
        patch: ManifestPatch,
    ): Outcome<Unit> =
        withContext(dispatchers.io) { rewriter.rewrite(path, path, patch) }
            .flatMap { Outcome.Success(Unit) }
            .also { if (it is Outcome.Success) scanner.indexFile(path) }

    private suspend fun <T> folderOp(block: () -> Outcome<T>): Outcome<T> =
        withContext(dispatchers.io) { block() }.also { if (it is Outcome.Success) scanner.incrementalScan() }

    private companion object {
        fun sorted(
            rows: List<DocumentRow>,
            sort: DocumentSort,
        ): List<DocumentRow> =
            when (sort) {
                DocumentSort.RECENT -> rows.sortedByDescending { it.modifiedMs }
                DocumentSort.NAME -> rows.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.title })
                DocumentSort.CREATED -> rows.sortedByDescending { it.createdMs }
            }

        fun titleHit(
            title: String,
            tokens: List<String>,
        ): Boolean {
            val words = title.lowercase().split(Regex("[^\\p{L}\\p{N}]+"))
            return tokens.all { t -> words.any { it.startsWith(t) } }
        }
    }
}
