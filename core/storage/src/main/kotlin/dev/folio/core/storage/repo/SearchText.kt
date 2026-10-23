package dev.folio.core.storage.repo

import dev.folio.core.storage.index.DocumentRow

/** [text] with the character ranges ([ranges], end inclusive) a search matched. */
data class HighlightedText(
    val text: String,
    val ranges: List<IntRange> = emptyList(),
)

/** One search result: the document, its title and a body snippet, both with the matched ranges. */
data class SearchHit(
    val doc: DocumentRow,
    val title: HighlightedText,
    val snippet: HighlightedText,
)

/** Pure helpers behind the search results (09-storage-library.md#search). */
object SearchText {
    /** Wraps a match inside an FTS `snippet()` result; control characters never occur in note text. */
    const val MARK_START = '\u0001'

    /** Closes a match inside an FTS `snippet()` result. */
    const val MARK_END = '\u0002'

    private val WORD = Regex("[\\p{L}\\p{N}]+")
    private val WHITESPACE = Regex("\\s+")

    /** Lowercase query words (same split as the FTS query). */
    fun tokens(query: String): List<String> = WORD.findAll(query.lowercase()).map { it.value }.toList()

    /** True when every token starts a word of [title]. */
    fun titleHit(
        title: String,
        tokens: List<String>,
    ): Boolean {
        if (tokens.isEmpty()) return false
        val words = WORD.findAll(title.lowercase()).map { it.value }.toList()
        return tokens.all { t -> words.any { it.startsWith(t) } }
    }

    /** Marks every word of [title] that a token prefixes (whole words, like the FTS snippet does). */
    fun highlightTitle(
        title: String,
        tokens: List<String>,
    ): HighlightedText {
        val ranges =
            WORD
                .findAll(title)
                .filter { word -> tokens.any { word.value.lowercase().startsWith(it) } }
                .map { it.range }
                .toList()
        return HighlightedText(title, ranges)
    }

    /** Turns an FTS snippet delimited by [MARK_START] and [MARK_END] into text plus ranges; whitespace runs become one space. */
    fun parseSnippet(marked: String): HighlightedText {
        val text = StringBuilder(marked.length)
        val ranges = ArrayList<IntRange>()
        var start = -1
        for (c in marked.replace(WHITESPACE, " ").trim()) {
            when (c) {
                MARK_START -> {
                    start = text.length
                }

                MARK_END -> {
                    if (start in 0 until text.length) ranges += start until text.length
                    start = -1
                }

                else -> {
                    text.append(c)
                }
            }
        }
        return HighlightedText(text.toString(), ranges)
    }

    /** Title matches first; the input order (newest first) is kept inside each group. */
    fun rank(
        hits: List<Pair<DocumentRow, String>>,
        tokens: List<String>,
    ): List<SearchHit> =
        hits
            .map { (doc, marked) -> SearchHit(doc, highlightTitle(doc.title, tokens), parseSnippet(marked)) }
            .sortedByDescending { titleHit(it.doc.title, tokens) }
}
