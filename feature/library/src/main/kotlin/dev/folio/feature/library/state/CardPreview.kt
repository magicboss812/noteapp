package dev.folio.feature.library.state

private const val EXCERPT_MAX_CHARS = 180
private const val EXCERPT_MAX_LINES = 8
private val HEADING_MARK = Regex("^#{1,6}(\\s+|$)")
private val BULLET_MARK = Regex("^[-*+]\\s+")

/** What fills the lower part of a note card (11-design-system.md#cards). */
sealed interface CardPreview {
    /** The stored `thumbs/cover.webp`. */
    data object Cover : CardPreview

    /** A short Markdown excerpt in caption type on paper white. */
    data class Excerpt(
        val text: String,
    ) : CardPreview

    /** Nothing to show yet: an empty paper. */
    data object Blank : CardPreview
}

/** The cover when the file has one, else an excerpt of the note text, else a blank paper. */
fun cardPreview(doc: LibraryDocItem): CardPreview =
    when {
        doc.hasCover -> CardPreview.Cover
        else -> excerptOf(doc.textPreview)?.let(CardPreview::Excerpt) ?: CardPreview.Blank
    }

/**
 * Plain-text excerpt of Markdown [text]: heading marks dropped, bullets shown as dots, blank lines removed, at most
 * 8 lines and 180 characters; null when nothing visible is left.
 */
fun excerptOf(text: String): String? {
    val lines =
        text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { line -> BULLET_MARK.replace(HEADING_MARK.replace(line, ""), "• ") }
            .filter { it.isNotBlank() }
            .take(EXCERPT_MAX_LINES)
            .joinToString("\n")
    return lines.take(EXCERPT_MAX_CHARS).trimEnd().ifEmpty { null }
}
