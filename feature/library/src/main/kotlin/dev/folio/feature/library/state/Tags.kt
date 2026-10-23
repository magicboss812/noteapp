package dev.folio.feature.library.state

import androidx.compose.runtime.Immutable
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.toImmutableList

/** Longest tag the editor accepts; longer input is cut. */
const val TAG_MAX_LENGTH = 32

private const val SUGGESTION_LIMIT = 8
private val WHITESPACE = Regex("\\s+")

/** A tag with the number of notes that carry it. */
@Immutable
data class TagCount(
    val tag: String,
    val count: Int,
)

/** A typed tag without leading `#`, with inner whitespace collapsed and cut to [TAG_MAX_LENGTH]; null when nothing is left. */
fun normalizeTag(input: String): String? =
    input
        .trim()
        .trimStart('#')
        .trim()
        .replace(WHITESPACE, " ")
        .take(TAG_MAX_LENGTH)
        .trimEnd()
        .ifEmpty { null }

/**
 * [current] plus the typed [input]. A tag equal to one already there (ignoring case) is not added twice; one equal to a
 * tag of [known] (ignoring case) takes that spelling, so "Physics" and "physics" never split into two tags.
 */
fun addTag(
    current: List<String>,
    input: String,
    known: Collection<String> = emptyList(),
): ImmutableList<String> {
    val tag = normalizeTag(input) ?: return current.toImmutableList()
    if (current.any { it.equals(tag, ignoreCase = true) }) return current.toImmutableList()
    val spelled = known.firstOrNull { it.equals(tag, ignoreCase = true) } ?: tag
    return (current + spelled).toImmutableList()
}

/** [current] without [tag]. */
fun removeTag(
    current: List<String>,
    tag: String,
): ImmutableList<String> = current.filterNot { it == tag }.toImmutableList()

/**
 * Tags of [all] not yet in [current] that contain the typed [query] (ignoring case), prefix matches first, then the
 * rest alphabetically; an empty query lists them all. At most eight.
 */
fun tagSuggestions(
    all: Collection<String>,
    current: List<String>,
    query: String,
): ImmutableList<String> {
    val needle = normalizeTag(query).orEmpty().lowercase()
    return all
        .filter { tag -> current.none { it.equals(tag, ignoreCase = true) } && tag.lowercase().contains(needle) }
        .sortedWith(compareBy<String>({ !it.lowercase().startsWith(needle) }, { it.lowercase() }))
        .take(SUGGESTION_LIMIT)
        .toImmutableList()
}

/** Every tag in use with its note count, most used first, then alphabetically. */
fun tagCounts(documents: List<LibraryDocItem>): ImmutableList<TagCount> =
    documents
        .flatMap { it.tags }
        .groupingBy { it }
        .eachCount()
        .map { (tag, count) -> TagCount(tag, count) }
        .sortedWith(compareBy<TagCount>({ -it.count }, { it.tag.lowercase() }))
        .toImmutableList()
