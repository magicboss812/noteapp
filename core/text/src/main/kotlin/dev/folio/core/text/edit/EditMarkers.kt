package dev.folio.core.text.edit

import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.InlineSpan
import dev.folio.core.text.markdown.InlineText
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.SpanKind

/** The syntax characters of a block's source: what the live field of the focused block shows dimmed (07-text-engine.md#editing). */
object EditMarkers {
    /**
     * Source ranges of [block] that are syntax rather than text: the leading marker (`## `, `- [ ] `, `> `), the
     * delimiters of inline spans (`**`, `==`, backticks, `[`/`](url)`), fences, table pipes and the rule itself.
     * Flat `[start, end)` pairs, ascending and disjoint.
     */
    fun ranges(block: MarkdownBlock): IntArray {
        val source = block.source
        val found = ArrayList<IntArray>()
        val marker = BlockMarkers.length(block)
        if (marker > 0) found += intArrayOf(0, marker)
        when (val content = block.content) {
            is BlockContent.Paragraph -> inline(source, content.text, found)
            is BlockContent.Heading -> inline(source, content.text, found)
            is BlockContent.ListItem -> inline(source, content.text, found)
            is BlockContent.Quote -> inline(source, content.text, found)
            is BlockContent.Code -> fences(source, found)
            is BlockContent.Table -> pipes(source, found)
            BlockContent.Rule -> found += intArrayOf(0, source.length)
            is BlockContent.Math, BlockContent.Empty -> Unit
        }
        return merged(found)
    }

    private fun inline(
        source: String,
        text: InlineText,
        out: MutableList<IntArray>,
    ) {
        for (span in text.spans) {
            if (span.sourceStart < span.sourceEnd && span.sourceEnd <= source.length) delimiters(source, span, out)
        }
    }

    private fun delimiters(
        source: String,
        span: InlineSpan,
        out: MutableList<IntArray>,
    ) {
        val start = span.sourceStart
        val end = span.sourceEnd
        when (span.kind) {
            SpanKind.LINK -> {
                val close = source.lastIndexOf("](", end - 1)
                if (source[start] == '[' && close > start) {
                    out += intArrayOf(start, start + 1)
                    out += intArrayOf(close, end)
                }
            }

            SpanKind.DOC_LINK -> {
                if (source.startsWith("[[", start) && end - start >= DOC_LINK_DELIMS) {
                    out += intArrayOf(start, start + 2)
                    out += intArrayOf(end - 2, end)
                }
            }

            else -> {
                val cap = delimiterCap(span.kind)
                val lead = leadingRun(source, start, end, cap)
                val trail = trailingRun(source, start + lead, end, cap)
                if (lead > 0) out += intArrayOf(start, start + lead)
                if (trail > 0) out += intArrayOf(end - trail, end)
            }
        }
    }

    /** Most delimiter characters a span of [kind] opens with: `*`, `**`, `~~`, `==`, any backtick run, `$`. */
    private fun delimiterCap(kind: SpanKind): Int =
        when (kind) {
            SpanKind.CODE -> Int.MAX_VALUE
            SpanKind.ITALIC, SpanKind.MATH -> 1
            else -> 2
        }

    /** Length of the run of one punctuation character at [from] within [from] until [to], at most [cap]; 0 for letters. */
    private fun leadingRun(
        source: String,
        from: Int,
        to: Int,
        cap: Int,
    ): Int {
        val ch = source[from]
        if (ch.isLetterOrDigit() || ch.isWhitespace()) return 0
        var n = 0
        while (n < cap && from + n < to && source[from + n] == ch) n++
        return n
    }

    /** [leadingRun] backwards from [to]. */
    private fun trailingRun(
        source: String,
        from: Int,
        to: Int,
        cap: Int,
    ): Int {
        if (to <= from) return 0
        val ch = source[to - 1]
        if (ch.isLetterOrDigit() || ch.isWhitespace()) return 0
        var n = 0
        while (n < cap && to - 1 - n >= from && source[to - 1 - n] == ch) n++
        return n
    }

    private fun fences(
        source: String,
        out: MutableList<IntArray>,
    ) {
        val opener = BlockMarkers.fenceOpener(source) ?: return
        val firstEnd = source.indexOf('\n').let { if (it < 0) source.length else it }
        out += intArrayOf(0, firstEnd)
        if (BlockMarkers.isClosedFence(source, opener)) out += intArrayOf(source.lastIndexOf('\n') + 1, source.length)
    }

    private fun pipes(
        source: String,
        out: MutableList<IntArray>,
    ) {
        for (i in source.indices) {
            if (source[i] == '|') out += intArrayOf(i, i + 1)
        }
        val firstEnd = source.indexOf('\n')
        val secondEnd = if (firstEnd < 0) -1 else source.indexOf('\n', firstEnd + 1)
        if (firstEnd >= 0) out += intArrayOf(firstEnd + 1, if (secondEnd < 0) source.length else secondEnd)
    }

    private fun merged(found: List<IntArray>): IntArray {
        val sorted = found.sortedBy { it[0] }
        val out = ArrayList<Int>(sorted.size * 2)
        for (range in sorted) {
            val last = out.size - 1
            if (last >= 0 && range[0] <= out[last]) {
                if (range[1] > out[last]) out[last] = range[1]
            } else {
                out += range[0]
                out += range[1]
            }
        }
        return out.toIntArray()
    }

    private const val DOC_LINK_DELIMS = 4
}
