package dev.folio.core.storage.repo

import dev.folio.core.common.FolioDispatchers
import dev.folio.core.storage.index.DocumentRow
import dev.folio.core.storage.index.IndexDao
import kotlinx.coroutines.withContext

/** A document a note link can point to: its id, library path and title. */
data class LinkTarget(
    val docId: String,
    val path: String,
    val title: String,
)

/**
 * Note links through the index (09-storage-library.md#links): targets resolve by document id, so renames and moves
 * keep them working; documents in the bin are not indexed and resolve to nothing (a broken link). Backlinks come from
 * the `links` table that each manifest's `links` fill.
 */
class NoteLinks(
    private val dao: IndexDao,
    private val dispatchers: FolioDispatchers,
) {
    /** The document [docId] points to, null when no indexed document has that id. */
    suspend fun resolve(docId: String): LinkTarget? = withContext(dispatchers.io) { dao.documentsById(docId).firstOrNull()?.target() }

    /** The ids of [docIds] that no indexed document has. */
    suspend fun missing(docIds: Collection<String>): Set<String> {
        if (docIds.isEmpty()) return emptySet()
        val found = withContext(dispatchers.io) { docIds.chunked(MAX_ARGS).flatMap { dao.documentsByIds(it) } }.map { it.docId }
        return docIds.toSet() - found.toSet()
    }

    /** Documents that link to [docId], by title; a document that links to itself is left out. */
    suspend fun backlinks(docId: String): List<LinkTarget> =
        withContext(dispatchers.io) { dao.backlinks(docId) }.filter { it.docId != docId }.map { it.target() }

    /** Up to [limit] documents whose title contains [query] (any case), newest first; all for a blank query. */
    suspend fun titled(
        query: String,
        limit: Int = DEFAULT_LIMIT,
    ): List<LinkTarget> = withContext(dispatchers.io) { dao.documentsTitled(likePattern(query.trim()), limit) }.map { it.target() }

    /** Constants. */
    companion object {
        /** Picker rows by default. */
        const val DEFAULT_LIMIT = 20

        // SQLite's default host parameter limit is 999.
        private const val MAX_ARGS = 500

        /** `%query%` with LIKE wildcards in [query] escaped. */
        fun likePattern(query: String): String =
            buildString {
                append('%')
                for (c in query) {
                    if (c == '%' || c == '_' || c == '\\') append('\\')
                    append(c)
                }
                append('%')
            }

        private fun DocumentRow.target() = LinkTarget(docId, path, title)
    }
}
