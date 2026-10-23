package dev.folio.feature.library.state

import androidx.compose.runtime.Immutable
import dev.folio.core.storage.repo.DocumentSort
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentSetOf
import kotlinx.collections.immutable.toImmutableList
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The chip row of the library (11-design-system.md#library-screen). */
enum class LibraryTab(
    val label: String,
) {
    ALL("All"),
    FAVORITES("Favorites"),
    TAGS("Tags"),
    BIN("Bin"),
}

/** Cards in a grid or rows in a two-column list. */
enum class LibraryViewMode { GRID, LIST }

/** Menu label of a sort order. */
val DocumentSort.label: String
    get() =
        when (this) {
            DocumentSort.RECENT -> "Recent"
            DocumentSort.NAME -> "Name"
            DocumentSort.CREATED -> "Created"
        }

/** One folder of the sidebar tree; [depth] 0 = directly in the library root. */
@Immutable
data class LibraryFolderItem(
    val path: String,
    val name: String,
    val depth: Int,
)

/** What the library screen shows besides the documents: the stored view choices plus transient selection. */
@Immutable
data class LibraryBrowseState(
    val tab: LibraryTab = LibraryTab.ALL,
    val sort: DocumentSort = DocumentSort.RECENT,
    val viewMode: LibraryViewMode = LibraryViewMode.GRID,
    val selectMode: Boolean = false,
    val sidebarCollapsed: Boolean = false,
    /** Blank = not set; the greeting then reads "Your notes". */
    val userName: String = "",
    /** Library paths ticked in select mode; always empty outside it. */
    val selected: ImmutableSet<String> = persistentSetOf(),
)

/** Greeting line above the library: "Hello, <name>", or "Your notes" while no name is set. */
fun greeting(userName: String): String = userName.trim().let { if (it.isEmpty()) "Your notes" else "Hello, $it" }

/** Date line under the greeting, like "Friday, 23 October". */
fun dateSubtitle(
    nowMs: Long,
    zone: ZoneId,
    locale: Locale = Locale.ENGLISH,
): String = DATE_FORMAT.withLocale(locale).withZone(zone).format(Instant.ofEpochMilli(nowMs))

private val DATE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("EEEE, d MMMM")
private val SAME_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH)
private val OTHER_YEAR_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH)

/** Timestamp on a card: "23 Oct, 14:05" within the current year of [nowMs], "23 Oct 2025" before. */
fun cardTimestamp(
    modifiedMs: Long,
    nowMs: Long,
    zone: ZoneId,
): String {
    val modified = Instant.ofEpochMilli(modifiedMs).atZone(zone)
    val sameYear = modified.year == Instant.ofEpochMilli(nowMs).atZone(zone).year
    return (if (sameYear) SAME_YEAR_FORMAT else OTHER_YEAR_FORMAT).format(modified)
}

/**
 * Documents of [tab] in [sort] order. Tags and Bin have their own content (P05-T04, P05-T06), so they list
 * nothing here. Name sorts case-insensitively; ties keep the newest first.
 */
fun visibleDocuments(
    all: List<LibraryDocItem>,
    tab: LibraryTab,
    sort: DocumentSort,
): ImmutableList<LibraryDocItem> {
    val pool =
        when (tab) {
            LibraryTab.ALL -> all
            LibraryTab.FAVORITES -> all.filter { it.favorite }
            LibraryTab.TAGS, LibraryTab.BIN -> emptyList()
        }
    val order =
        when (sort) {
            DocumentSort.RECENT -> {
                compareByDescending<LibraryDocItem> { it.modifiedMs }
            }

            DocumentSort.CREATED -> {
                compareByDescending<LibraryDocItem> { it.createdMs }
            }

            DocumentSort.NAME -> {
                compareBy<LibraryDocItem, String>(
                    String.CASE_INSENSITIVE_ORDER,
                ) { it.title }.thenByDescending { it.modifiedMs }
            }
        }
    return pool.sortedWith(order).toImmutableList()
}

/** Folders as a depth-annotated pre-order list (parents before children, siblings by name) for the sidebar. */
fun folderItems(paths: List<String>): ImmutableList<LibraryFolderItem> {
    val byParent = paths.groupBy { it.substringBeforeLast('/', missingDelimiterValue = "") }
    val out = ArrayList<LibraryFolderItem>(paths.size)

    fun visit(
        parent: String,
        depth: Int,
    ) {
        byParent[parent].orEmpty().sortedBy { it.lowercase() }.forEach { path ->
            out += LibraryFolderItem(path, path.substringAfterLast('/'), depth)
            visit(path, depth + 1)
        }
    }
    visit("", 0)
    return out.toImmutableList()
}
