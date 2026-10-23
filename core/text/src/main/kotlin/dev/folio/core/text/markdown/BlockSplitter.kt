package dev.folio.core.text.markdown

/** A block cut out of a flow: [source] plus the separator [gap] that follows it. Not parsed yet. */
internal data class RawBlock(
    val source: String,
    val gap: String,
)

/**
 * Cuts flow Markdown into blocks without parsing inline syntax (07-text-engine.md#blocks).
 *
 * Every list item, heading, rule, fenced code, `$$` math and table is one block; consecutive lines of a paragraph,
 * quote or item stay together. Between two blocks one blank line is the plain separator; every further blank line is an
 * empty paragraph block. Concatenating `source + gap` of the result gives back the input exactly.
 */
internal object BlockSplitter {
    /** Shortest line that holds both `$$` fences, so the block ends on its first line. */
    private const val MIN_SINGLE_LINE_MATH = 4
    private val fence = Regex("^ {0,3}(`{3,}|~{3,})")
    private val atx = Regex("^ {0,3}#{1,6}(\\s|$)")
    private val rule = Regex("^ {0,3}([-*_])( *\\1){2,} *$")
    private val listItem = Regex("^( *)([-*+]|\\d{1,9}[.)])( +\\S|\\s*$)")
    private val orderedStart = Regex("^ *1[.)]")
    private val quote = Regex("^ {0,3}>")
    private val setextUnderline = Regex("^ {0,3}(=+|-+) *$")
    private val tableDelimiter = Regex("^ *\\|? *:?-+:? *(\\| *:?-+:? *)*\\|? *$")

    private class Line(
        val start: Int,
        val end: Int,
        val termEnd: Int,
        val text: String,
    ) {
        val blank get() = text.isBlank()
        val terminated get() = termEnd > end
    }

    fun split(source: String): List<RawBlock> {
        val lines = lines(source)
        val out = ArrayList<RawBlock>()
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            if (line.blank) {
                emptyBlock(source, line)?.let(out::add)
                i++
            } else {
                val last = blockEnd(lines, i)
                val srcEnd = lines[last].end
                var gapEnd = lines[last].termEnd
                var next = last + 1
                // One terminated blank line directly after the block is the separator.
                if (next < lines.size && lines[next].blank && lines[next].terminated) {
                    gapEnd = lines[next].termEnd
                    next++
                }
                out.add(RawBlock(source.substring(line.start, srcEnd), source.substring(srcEnd, gapEnd)))
                i = next
            }
        }
        return out
    }

    /** A blank line that is not the separator of a previous block is an empty paragraph. */
    private fun emptyBlock(
        source: String,
        line: Line,
    ): RawBlock? =
        when {
            line.terminated -> RawBlock(line.text, source.substring(line.end, line.termEnd))
            line.text.isNotEmpty() -> RawBlock(line.text, "")
            else -> null
        }

    private fun lines(source: String): List<Line> {
        val out = ArrayList<Line>()
        var pos = 0
        while (pos < source.length) {
            val nl = source.indexOf('\n', pos)
            if (nl < 0) {
                out.add(Line(pos, source.length, source.length, source.substring(pos)))
                break
            }
            val end = if (nl > pos && source[nl - 1] == '\r') nl - 1 else nl
            out.add(Line(pos, end, nl + 1, source.substring(pos, end)))
            pos = nl + 1
        }
        return out
    }

    /** Index of the last line of the block starting at [first] (a non-blank line). */
    private fun blockEnd(
        lines: List<Line>,
        first: Int,
    ): Int {
        val text = lines[first].text
        val opener = fence.find(text)
        return when {
            opener != null -> fencedEnd(lines, first, opener.groupValues[1])
            text.trimStart().startsWith("$$") -> mathEnd(lines, first)
            atx.containsMatchIn(text) || rule.matches(text) -> first
            isTableStart(lines, first) -> tableEnd(lines, first)
            else -> runEnd(lines, first)
        }
    }

    /** End of a paragraph, list item or quote: it runs until a blank line or the start of another block. */
    private fun runEnd(
        lines: List<Line>,
        first: Int,
    ): Int {
        val text = lines[first].text
        val quoted = quote.containsMatchIn(text)
        val isItem = listItem.containsMatchIn(text)
        var end = first
        while (end + 1 < lines.size && continuesRun(lines[end + 1].text, quoted, isItem)) end++
        return end
    }

    private fun continuesRun(
        next: String,
        quoted: Boolean,
        isItem: Boolean,
    ): Boolean =
        when {
            next.isBlank() -> false

            // Inside a list every following item starts its own block, whatever its number.
            isItem && listItem.containsMatchIn(next) -> false

            quoted && quote.containsMatchIn(next) -> true

            // Only a plain paragraph can turn into a setext heading.
            else -> !interrupts(next, setextOk = !quoted && !isItem, itemsInterrupt = true)
        }

    /** Whether [text] starts a new block while a paragraph, item or quote is running. */
    private fun interrupts(
        text: String,
        setextOk: Boolean,
        itemsInterrupt: Boolean,
    ): Boolean {
        if (setextOk && setextUnderline.matches(text)) return false
        if (fence.containsMatchIn(text) || atx.containsMatchIn(text) || rule.matches(text)) return true
        if (text.trimStart().startsWith("$$") || quote.containsMatchIn(text)) return true
        val item = listItem.find(text) ?: return false
        val marker = item.groupValues[2]
        val hasContent = item.groupValues[3].isNotBlank()
        // CommonMark: only a non-empty bullet or an ordered item numbered 1 interrupts a paragraph.
        return itemsInterrupt && hasContent && (marker[0] !in '0'..'9' || orderedStart.containsMatchIn(text))
    }

    private fun fencedEnd(
        lines: List<Line>,
        first: Int,
        opener: String,
    ): Int {
        val closer = Regex("^ {0,3}${Regex.escape(opener[0].toString())}{${opener.length},} *$")
        var i = first + 1
        while (i < lines.size) {
            if (closer.matches(lines[i].text)) return i
            i++
        }
        return lines.size - 1
    }

    private fun mathEnd(
        lines: List<Line>,
        first: Int,
    ): Int {
        val head = lines[first].text.trim()
        if (head.length >= MIN_SINGLE_LINE_MATH && head.endsWith("$$")) return first
        var i = first + 1
        while (i < lines.size) {
            if (lines[i].text.trim().endsWith("$$")) return i
            i++
        }
        return lines.size - 1
    }

    private fun isTableStart(
        lines: List<Line>,
        first: Int,
    ): Boolean {
        if (first + 1 >= lines.size || '|' !in lines[first].text) return false
        val delimiter = lines[first + 1].text
        return '-' in delimiter && tableDelimiter.matches(delimiter)
    }

    private fun tableEnd(
        lines: List<Line>,
        first: Int,
    ): Int {
        var end = first + 1
        while (end + 1 < lines.size) {
            val next = lines[end + 1]
            if (next.blank || interrupts(next.text, setextOk = false, itemsInterrupt = false)) break
            end++
        }
        return end
    }
}
