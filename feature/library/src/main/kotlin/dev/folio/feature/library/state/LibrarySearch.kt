package dev.folio.feature.library.state

import androidx.compose.runtime.Immutable
import dev.folio.core.common.FolioLog
import dev.folio.core.storage.repo.HighlightedText
import dev.folio.core.storage.repo.SearchHit
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

/** One search result row. */
@Immutable
data class SearchResultItem(
    /** Library-relative path; opens the editor. */
    val path: String,
    val title: HighlightedText,
    /** Containing folder ("" = root). */
    val folder: String,
    /** Body excerpt around the first match; empty when only the title matched. */
    val snippet: HighlightedText,
    val modifiedMs: Long,
)

/** Everything the search overlay shows. */
@Immutable
data class SearchUiState(
    val open: Boolean = false,
    val query: String = "",
    /** Limit to this folder and its subfolders; null searches everywhere. */
    val folder: String? = null,
    val tag: String? = null,
    val results: ImmutableList<SearchResultItem> = persistentListOf(),
    /** Last searches, newest first (local only). */
    val recent: ImmutableList<String> = persistentListOf(),
    /** Tags in use, for the tag filter. */
    val tags: ImmutableList<String> = persistentListOf(),
) {
    /** True once the query has something searchable; before that the overlay shows recent searches. */
    val searching: Boolean get() = isSearchable(query)
}

private val SEARCHABLE = Regex("[\\p{L}\\p{N}]")

/** True when [query] has a letter or digit (the FTS query needs at least one token). */
internal fun isSearchable(query: String): Boolean = SEARCHABLE.containsMatchIn(query)

internal fun SearchHit.toItem() =
    SearchResultItem(
        path = doc.path,
        title = title,
        folder = doc.folderPath,
        snippet = if (snippet.text.isBlank()) HighlightedText("") else snippet,
        modifiedMs = doc.modifiedMs,
    )

/** The recent searches list (09-storage-library.md#search): newest first, no duplicates, at most [MAX]. */
internal object RecentSearches {
    const val MAX = 10
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(String.serializer())

    /** [query] (trimmed, whitespace collapsed) moved to the front; blank queries change nothing. */
    fun push(
        recent: List<String>,
        query: String,
    ): List<String> {
        val clean = query.trim().replace(Regex("\\s+"), " ")
        if (clean.isEmpty()) return recent
        return (listOf(clean) + recent.filterNot { it.equals(clean, ignoreCase = true) }).take(MAX)
    }

    fun encode(recent: List<String>): String = json.encodeToString(serializer, recent)

    /** Unreadable or missing storage gives an empty list. */
    fun decode(text: String?): List<String> =
        if (text == null) {
            emptyList()
        } else {
            try {
                json.decodeFromString(serializer, text).take(MAX)
            } catch (e: SerializationException) {
                FolioLog.w(TAG, "stored recent searches unreadable, starting empty", e)
                emptyList()
            }
        }

    private const val TAG = "RecentSearches"
}
