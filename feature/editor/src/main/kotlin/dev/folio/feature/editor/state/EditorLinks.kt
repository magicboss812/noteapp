package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable
import dev.folio.core.storage.repo.LinkTarget
import dev.folio.core.storage.repo.NoteLinks
import dev.folio.core.text.flow.TapTarget
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** A note the `[[` picker offers or the Linked from menu lists: document [docId] at library [path], named [title]. */
@Immutable
data class NoteChoice(
    val docId: String,
    val path: String,
    val title: String,
)

/** Something the editor screen does once for a tapped link (09-storage-library.md#links-as-built). */
sealed interface EditorEvent {
    /** Opens [url] in the app the system picks. */
    data class OpenUrl(
        val url: String,
    ) : EditorEvent

    /** Opens the note at library [path] in the editor. */
    data class OpenNote(
        val path: String,
    ) : EditorEvent

    /** A note link whose note no longer exists (or a `[[Title]]` no note is named). */
    data object NoteNotFound : EditorEvent
}

/** The note links of the document at [selfPath] with id [selfId]: link taps, the `[[` picker and Linked from. */
internal class EditorLinks(
    private val noteLinks: NoteLinks,
    private val selfId: () -> String,
    private val selfPath: String,
) {
    /** What a tap on [target] does; null for a checkbox (the text engine toggles it) and a link to this note. */
    suspend fun eventFor(target: TapTarget): EditorEvent? =
        when (target) {
            is TapTarget.Checkbox -> {
                null
            }

            is TapTarget.Url -> {
                EditorEvent.OpenUrl(target.url)
            }

            is TapTarget.Note -> {
                val found = target.docId?.let { noteLinks.resolve(it) } ?: titled(target.title)
                when {
                    found == null -> EditorEvent.NoteNotFound
                    found.path == selfPath -> null
                    else -> EditorEvent.OpenNote(found.path)
                }
            }
        }

    /** The `[[` picker's notes for [query]: titles containing it, newest first, without this note. */
    suspend fun suggestions(query: String): ImmutableList<NoteChoice> =
        noteLinks
            .titled(query)
            .filter { it.docId != selfId() }
            .map { it.choice() }
            .toImmutableList()

    /** Notes that link to this one, by title. */
    suspend fun linkedFrom(): ImmutableList<NoteChoice> = noteLinks.backlinks(selfId()).map { it.choice() }.toImmutableList()

    // A `[[Title]]` link has no id: the note named exactly so (any case), the newest of several.
    private suspend fun titled(title: String): LinkTarget? =
        title.takeIf { it.isNotBlank() }?.let { name ->
            noteLinks.titled(name).firstOrNull { it.title.equals(name.trim(), ignoreCase = true) }
        }

    private fun LinkTarget.choice() = NoteChoice(docId, path, title)
}
