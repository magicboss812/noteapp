package dev.folio.feature.library.state

import dev.folio.core.common.Outcome
import dev.folio.core.storage.repo.LibraryRepository

/**
 * Folder menu actions (create, rename, tint, move, delete to the bin). Every function returns the text for a snackbar,
 * or null when nothing needs saying; a folder with an open document inside is refused by the repository (D-007).
 */
class LibraryFolderOps(
    private val library: LibraryRepository,
) {
    /** Creates folder [name] inside [parent] ("" = root). */
    suspend fun create(
        parent: String,
        name: String,
    ): String? {
        if (name.isBlank()) return "A folder needs a name."
        return report("create", name.trim(), library.createFolder(parent, name.trim()), successText = null)
    }

    /** Renames the folder at [path] to [name]. */
    suspend fun rename(
        path: String,
        name: String,
    ): String? {
        if (name.isBlank()) return "A folder needs a name."
        return report("rename", nameOf(path), library.renameFolder(path, name.trim()), successText = null)
    }

    /** Sets the folder color (a [tintName]); null clears it. */
    suspend fun setTint(
        path: String,
        tint: String?,
    ): String? = report("recolor", nameOf(path), library.setFolderTint(path, tint), successText = null)

    /** Moves the folder at [path] into [parent]; a folder cannot go into itself or one of its own subfolders. */
    suspend fun move(
        path: String,
        parent: String,
    ): String? {
        if (parent == path || parent.startsWith("$path/")) return "A folder cannot move into itself."
        return report("move", nameOf(path), library.moveFolder(path, parent), successText = null)
    }

    /** Moves the folder at [path] with its contents to the bin. */
    suspend fun delete(path: String): String? =
        report("delete", nameOf(path), library.deleteFolder(path), successText = "Moved “${nameOf(path)}” to the bin.")

    private fun report(
        verb: String,
        name: String,
        result: Outcome<*>,
        successText: String?,
    ): String? = if (result is Outcome.Failure) failureText(verb, name, result) else successText

    private fun nameOf(path: String) = path.substringAfterLast('/')
}
