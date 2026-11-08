package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.InlineSpan
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.SpanKind
import java.text.BreakIterator
import java.util.Locale

/** A stretch [start] until [end] of a block source; empty when they are equal. */
data class WordRange(
    val start: Int,
    val end: Int,
) {
    val isEmpty: Boolean get() = start == end
}

/**
 * The word a long-press or double tap selects (07-text-engine.md#selection-as-built): words by [BreakIterator] on the
 * block source, widened so a selection never splits a marker (`**`, `==`, `## `) or a formula (`$x$` whole).
 */
object WordRanges {
    /**
     * The word of [block] at source [offset]: the word the offset lies in or touches, else the punctuation run there;
     * a marker selects the span it delimits (a leading block marker itself), any point of a formula the formula.
     * Empty at [offset] on blank space between words.
     */
    fun at(
        block: MarkdownBlock,
        offset: Int,
    ): WordRange {
        val source = block.source
        val at = offset.coerceIn(0, source.length)
        val spans = spansOf(block)
        val formula = spans.firstOrNull { it.kind == SpanKind.MATH && at > it.sourceStart && at < it.sourceEnd }
        if (formula != null) return WordRange(formula.sourceStart, formula.sourceEnd)
        return segmentAt(source, at)?.let { unsplit(block, spans, it) } ?: WordRange(at, at)
    }

    // The BreakIterator segment for [at]: a word segment beside the offset wins, else the punctuation after it.
    private fun segmentAt(
        source: String,
        at: Int,
    ): WordRange? {
        val words = BreakIterator.getWordInstance(Locale.ROOT)
        words.setText(source)
        val after =
            if (at >= source.length) {
                null
            } else {
                val start = if (words.isBoundary(at)) at else words.preceding(at)
                WordRange(start, words.following(start))
            }
        val before =
            if (at == 0 || (after != null && after.start < at)) {
                null
            } else {
                WordRange(words.preceding(at), at)
            }
        return listOfNotNull(after, before).firstOrNull { isWord(source, it) }
            ?: after?.takeIf { !isBlank(source, it) }
            ?: before?.takeIf { !isBlank(source, it) && at == source.length }
    }

    // A segment inside the markers selects the span they delimit, or the block marker; a word that runs into a marker
    // (or a formula) grows to its edge.
    private fun unsplit(
        block: MarkdownBlock,
        spans: List<InlineSpan>,
        segment: WordRange,
    ): WordRange {
        val markers = EditMarkers.ranges(block)
        val delimited =
            if (insideMarkers(markers, segment)) {
                spans
                    .filter { it.sourceStart <= segment.start && segment.end <= it.sourceEnd }
                    .minByOrNull { it.sourceEnd - it.sourceStart }
            } else {
                null
            }
        if (delimited != null) return WordRange(delimited.sourceStart, delimited.sourceEnd)
        var range = segment
        for (i in 0 until markers.size - 1 step 2) range = widen(range, markers[i], markers[i + 1])
        for (span in spans) {
            if (span.kind == SpanKind.MATH) range = widen(range, span.sourceStart, span.sourceEnd)
        }
        return range
    }

    // [range] with an end strictly inside [start, end) moved out to that edge.
    private fun widen(
        range: WordRange,
        start: Int,
        end: Int,
    ): WordRange =
        WordRange(
            if (range.start > start && range.start < end) start else range.start,
            if (range.end > start && range.end < end) end else range.end,
        )

    private fun insideMarkers(
        markers: IntArray,
        segment: WordRange,
    ): Boolean {
        for (i in 0 until markers.size - 1 step 2) {
            if (markers[i] <= segment.start && segment.end <= markers[i + 1]) return true
        }
        return false
    }

    private fun isWord(
        source: String,
        range: WordRange,
    ): Boolean = (range.start until range.end).any { source[it].isLetterOrDigit() }

    private fun isBlank(
        source: String,
        range: WordRange,
    ): Boolean = (range.start until range.end).all { source[it].isWhitespace() }

    private fun spansOf(block: MarkdownBlock): List<InlineSpan> =
        when (val content = block.content) {
            is BlockContent.Paragraph -> content.text.spans
            is BlockContent.Heading -> content.text.spans
            is BlockContent.ListItem -> content.text.spans
            is BlockContent.Quote -> content.text.spans
            is BlockContent.Table -> (content.header + content.rows.flatten()).flatMap { it.spans }
            else -> emptyList()
        }
}
