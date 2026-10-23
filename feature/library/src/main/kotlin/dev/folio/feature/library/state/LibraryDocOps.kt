package dev.folio.feature.library.state

import dev.folio.core.common.Outcome
import dev.folio.core.storage.repo.DocumentOpenException
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.LibraryRepository

/** Snackbar text for a refused or failed action on [name]: an open document is named, anything else is "Could not <verb>". */
internal fun failureText(
    verb: String,
    name: String,
    failure: Outcome.Failure,
): String =
    if (failure.cause is DocumentOpenException) {
        "“$name” is open in the editor. Close it first."
    } else {
        "Could not $verb “$name”."
    }

/**
 * The note menu and batch actions on closed documents. Every function returns the text for a snackbar, or null when
 * nothing needs saying; a document that is open in the editor is refused by the repositories (D-007) and named here.
 */
class LibraryDocOps(
    private val documents: DocumentRepository,
    private val library: LibraryRepository,
) {
    /** Renames the note at [path] to [title]. */
    suspend fun rename(
        path: String,
        title: String,
    ): String? {
        if (title.isBlank()) return "A note needs a title."
        return report("rename", listOf(path), listOf(documents.rename(path, title)), successText = null)
    }

    /** Moves each of [paths] into [folder] ("" = library root). */
    suspend fun move(
        paths: List<String>,
        folder: String,
    ): String? = report("move", paths, paths.map { documents.move(it, folder) }, successText = null)

    /** Copies the note at [path] next to it. */
    suspend fun duplicate(path: String): String? = report("duplicate", listOf(path), listOf(documents.duplicate(path)), successText = null)

    /** Sets or clears the favorite mark of each of [paths]. */
    suspend fun setFavorite(
        paths: List<String>,
        favorite: Boolean,
    ): String? = report("update", paths, paths.map { library.setFavorite(it, favorite) }, successText = null)

    /** Moves each of [paths] to the bin. */
    suspend fun delete(paths: List<String>): String? =
        report("delete", paths, paths.map { documents.delete(it) }, successText = binText(paths.size))

    private fun report(
        verb: String,
        paths: List<String>,
        results: List<Outcome<*>>,
        successText: String?,
    ): String? {
        val failed = paths.zip(results).filter { it.second is Outcome.Failure }
        return when {
            failed.isEmpty() -> {
                successText
            }

            else -> {
                val (path, result) = failed.first()
                val reason = failureText(verb, nameOf(path), result as Outcome.Failure)
                if (paths.size == 1) reason else "${paths.size - failed.size} of ${paths.size} done. $reason"
            }
        }
    }

    private fun binText(count: Int) = if (count == 1) "Moved to the bin." else "Moved $count notes to the bin."

    private fun nameOf(path: String) = path.substringAfterLast('/').removeSuffix(".folio")
}
