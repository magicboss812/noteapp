package dev.folio.core.model.edit

import dev.folio.core.model.Document
import dev.folio.core.model.Page
import dev.folio.core.model.PageId

/**
 * A document change. Executing is pure: it returns the new document and the exact inverse computed
 * from the pre-state. Invalid arguments (unknown ids, page body not loaded) are programmer errors and
 * throw IllegalArgumentException / IllegalStateException; the session validates UI input first.
 */
sealed interface EditCommand {
    /** Pages whose bodies must be loaded in [Document.pageBodies] before [execute]. */
    val requiredPages: Set<PageId>

    /** Undo entries with the same non-null key within the coalescing window merge (typing, slider drags). */
    val coalesceKey: String?

    /** Applies the command to [doc]. */
    fun execute(doc: Document): Applied
}

/** Result of [EditCommand.execute]: new document, inverse command and the command's coalescing key. */
data class Applied(
    val doc: Document,
    val inverse: EditCommand,
    val coalesceKey: String?,
)

/** Several commands as one undo step; the inverse runs the inverses in reverse order. */
data class Batch(
    val commands: List<EditCommand>,
    override val coalesceKey: String? = null,
) : EditCommand {
    override val requiredPages: Set<PageId> get() = commands.flatMapTo(HashSet()) { it.requiredPages }

    override fun execute(doc: Document): Applied {
        var current = doc
        val inverses = ArrayList<EditCommand>(commands.size)
        for (command in commands) {
            val applied = command.execute(current)
            current = applied.doc
            inverses += applied.inverse
        }
        inverses.reverse()
        return Applied(current, Batch(inverses), coalesceKey)
    }
}

internal fun Document.requireBody(pageId: PageId): Page =
    checkNotNull(pageBodies[pageId]) { "page ${pageId.value} is not loaded (or does not exist)" }

/** Stores [page] as the loaded body and refreshes its summary (invariant: ref == body.toRef()). */
internal fun Document.withBody(page: Page): Document {
    val index = indexOfPage(page.id)
    check(index >= 0) { "page ${page.id.value} is not in the page list" }
    return copy(pages = pages.set(index, page.toRef()), pageBodies = pageBodies.put(page.id, page))
}
