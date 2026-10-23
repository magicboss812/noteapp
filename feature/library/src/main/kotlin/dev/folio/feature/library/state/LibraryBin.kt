package dev.folio.feature.library.state

import androidx.compose.runtime.Immutable
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.storage.repo.BinEntry
import dev.folio.core.storage.repo.BinRetention
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.daysLeft
import dev.folio.core.storage.repo.daysSinceDeleted
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList

/** One deleted note or folder in the Bin tab. */
@Immutable
data class LibraryBinItem(
    /** Identifies the entry inside the bin. */
    val trashPath: String,
    /** File name without `.folio`, or the folder name. */
    val name: String,
    val isFolder: Boolean,
    val daysAgo: Int,
    /** Whole days before the entry is purged; null when the retention is "never". */
    val daysLeft: Int?,
)

/** What the Bin tab shows. */
@Immutable
data class LibraryBinState(
    val items: ImmutableList<LibraryBinItem> = persistentListOf(),
    val retention: BinRetention = BinRetention.Default,
)

internal fun binItems(
    entries: List<BinEntry>,
    retention: BinRetention,
    nowMs: Long,
): ImmutableList<LibraryBinItem> =
    entries
        .map {
            LibraryBinItem(
                trashPath = it.trashPath,
                name = it.name.removeSuffix(".folio"),
                isFolder = it.isFolder,
                daysAgo = daysSinceDeleted(it, nowMs),
                daysLeft = daysLeft(it, retention, nowMs),
            )
        }.toImmutableList()

/** "Deleted today", "Deleted yesterday", "Deleted 5 days ago". */
fun deletedText(daysAgo: Int): String =
    when (daysAgo) {
        0 -> "Deleted today"
        1 -> "Deleted yesterday"
        else -> "Deleted $daysAgo days ago"
    }

/** "Last day", "1 day left", "12 days left". */
fun daysLeftText(daysLeft: Int): String =
    when (daysLeft) {
        0 -> "Last day"
        1 -> "1 day left"
        else -> "$daysLeft days left"
    }

/** Label of a retention choice in the picker. */
val BinRetention.label: String
    get() = days?.let { "$it days" } ?: "Never"

/**
 * Bin actions on the repository. Every function returns the snackbar text, or null when nothing needs saying; entries
 * are looked up by [LibraryBinItem.trashPath] so a stale item (already purged) is reported instead of crashing.
 */
class LibraryBinOps(
    private val documents: DocumentRepository,
) {
    /** Moves [item] back to where it was deleted from. */
    suspend fun restore(item: LibraryBinItem): String? {
        val entry = find(item) ?: return GONE
        return when (val result = documents.restore(entry)) {
            is Outcome.Success -> restoredText(item, result.value)
            is Outcome.Failure -> failureText("restore", item.name, result)
        }
    }

    /** Deletes [item] for good (the user confirmed). */
    suspend fun deleteForever(item: LibraryBinItem): String? {
        val entry = find(item) ?: return GONE
        return when (val result = documents.deleteForever(entry)) {
            is Outcome.Success -> "Deleted “${item.name}” forever."
            is Outcome.Failure -> failureText("delete", item.name, result)
        }
    }

    /** Deletes everything in the bin (the user confirmed). */
    suspend fun emptyBin(): String? =
        when (val result = documents.emptyBin()) {
            is Outcome.Success -> {
                if (result.value == 1) "Deleted 1 item forever." else "Deleted ${result.value} items forever."
            }

            is Outcome.Failure -> {
                FolioLog.w(LIBRARY_TAG, "empty bin failed: ${result.message}", result.cause)
                "Could not empty the bin."
            }
        }

    private fun find(item: LibraryBinItem): BinEntry? = documents.bin.value.firstOrNull { it.trashPath == item.trashPath }

    private fun restoredText(
        item: LibraryBinItem,
        path: String,
    ): String {
        val folder = path.substringBeforeLast('/', missingDelimiterValue = "")
        return if (folder.isEmpty()) "Restored “${item.name}”." else "Restored “${item.name}” to $folder."
    }

    private companion object {
        const val GONE = "That item is no longer in the bin."
    }
}
