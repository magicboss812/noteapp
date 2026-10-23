package dev.folio.core.storage.repo

import dev.folio.core.common.Outcome

/** Which library files an editor session currently holds; the repositories refuse to change those files (D-007). */
interface OpenDocuments {
    /** True when the document at library [path] is open in a session. */
    fun isOpen(path: String): Boolean

    /** True when a document inside [folder] (any depth) is open. */
    fun anyOpenUnder(folder: String): Boolean

    /** Nothing is ever open (tests and tools without sessions). */
    data object None : OpenDocuments {
        override fun isOpen(path: String) = false

        override fun anyOpenUnder(folder: String) = false
    }
}

/** Typed cause of the [Outcome.Failure] a repository returns for an open document; the UI shows a snackbar for it. */
class DocumentOpenException(
    val path: String,
) : IllegalStateException("$path is open in the editor")

internal fun OpenDocuments.refuseIfOpen(path: String): Outcome.Failure? =
    if (isOpen(path)) Outcome.Failure("cannot change an open document", DocumentOpenException(path)) else null

internal fun OpenDocuments.refuseIfOpenUnder(folder: String): Outcome.Failure? =
    if (anyOpenUnder(folder)) Outcome.Failure("cannot change a folder with an open document", DocumentOpenException(folder)) else null
