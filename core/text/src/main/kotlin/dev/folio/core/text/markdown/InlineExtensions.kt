package dev.folio.core.text.markdown

import org.commonmark.node.CustomNode
import org.commonmark.node.Delimited
import org.commonmark.node.Node
import org.commonmark.node.Text
import org.commonmark.parser.beta.InlineContentParser
import org.commonmark.parser.beta.InlineContentParserFactory
import org.commonmark.parser.beta.InlineParserState
import org.commonmark.parser.beta.ParsedInline
import org.commonmark.parser.beta.Position
import org.commonmark.parser.beta.Scanner
import org.commonmark.parser.delimiter.DelimiterProcessor
import org.commonmark.parser.delimiter.DelimiterRun

// Folio inline extensions (07-text-engine.md#markdown-dialect): ==highlight== and $math$ here; [[doc link]] is
// recognised by InlineCollector because commonmark's own bracket handling runs before custom parsers.

/** `==text==`. */
internal class Highlight :
    CustomNode(),
    Delimited {
    override fun getOpeningDelimiter() = "=="

    override fun getClosingDelimiter() = "=="
}

/** `$latex$`; [latex] is the text between the dollars. */
internal class MathInline(
    val latex: String,
) : CustomNode()

internal class HighlightDelimiterProcessor : DelimiterProcessor {
    override fun getOpeningCharacter() = '='

    override fun getClosingCharacter() = '='

    override fun getMinLength() = 2

    override fun process(
        openingRun: DelimiterRun,
        closingRun: DelimiterRun,
    ): Int {
        if (openingRun.length() < 2 || closingRun.length() < 2) return 0
        val opener: Text = openingRun.opener
        val closer: Text = closingRun.closer
        val highlight = Highlight()
        val spans = ArrayList<org.commonmark.node.SourceSpan>()
        openingRun.getOpeners(2).forEach { spans.addAll(it.sourceSpans) }
        var node: Node? = opener.next
        while (node != null && node !== closer) {
            val next = node.next
            highlight.appendChild(node)
            spans.addAll(node.sourceSpans)
            node = next
        }
        closingRun.getClosers(2).forEach { spans.addAll(it.sourceSpans) }
        highlight.sourceSpans = spans
        opener.insertAfter(highlight)
        return 2
    }
}

internal class MathInlineParserFactory : InlineContentParserFactory {
    override fun getTriggerCharacters() = setOf('$')

    override fun create(): InlineContentParser = MathInlineParser()
}

/**
 * Opening `$` must not follow a digit nor be followed by whitespace or `$`; the closing `$` must not follow whitespace
 * nor be followed by a digit. So "costs $5 and $6" stays literal. `\$` does not end the formula.
 */
internal class MathInlineParser : InlineContentParser {
    override fun tryParse(state: InlineParserState): ParsedInline? {
        val scanner = state.scanner()
        val start = scanner.position()
        val digitBefore = Character.isDigit(scanner.peekPreviousCodePoint())
        scanner.next()
        val contentStart = scanner.position()
        val contentEnd = if (digitBefore) null else findClosing(scanner)
        if (contentEnd == null) return ParsedInline.none()
        val node = MathInline(scanner.getSource(contentStart, contentEnd).content)
        node.sourceSpans = scanner.getSource(start, scanner.position()).sourceSpans
        return ParsedInline.of(node, scanner.position())
    }

    /** Moves behind the closing `$` and returns its position, or null when the dollars do not enclose a formula. */
    private fun findClosing(scanner: Scanner): Position? {
        val first = scanner.peek()
        var open = !(first == '$' || first == Scanner.END || first.isWhitespace())
        var closing: Position? = null
        while (open && scanner.hasNext()) {
            if (scanner.peek() == '\\') {
                scanner.next()
                scanner.next()
            } else if (scanner.peek() == '$') {
                val position = scanner.position()
                val spaceBefore = Character.isWhitespace(scanner.peekPreviousCodePoint())
                scanner.next()
                if (!spaceBefore && !Character.isDigit(scanner.peek())) closing = position
                // The first '$' that is not a valid closer ends the attempt, so "$5 and $6" stays literal.
                open = false
            } else {
                scanner.next()
            }
        }
        return closing
    }
}

/** A `[[Title]]` in a text run: [open] indexes the `[[`, [close] the `]]`. */
internal data class DocLinkMatch(
    val open: Int,
    val close: Int,
)

/**
 * First `[[Title]]` at or after [from]. The title must not be blank nor hold `[`, and both brackets must map back to
 * the source ([sourceAt] is the source offset of every char, -1 when unknown, e.g. after an escape).
 */
internal fun findDocLink(
    text: String,
    from: Int,
    sourceAt: List<Int>,
): DocLinkMatch? {
    var open = text.indexOf("[[", from)
    while (open >= 0) {
        val close = text.indexOf("]]", open + 2)
        val title = if (close < 0) "" else text.substring(open + 2, close)
        val mapped = close >= 0 && sourceAt[open] >= 0 && sourceAt[close + 1] >= 0
        val usable = mapped && title.isNotBlank() && '[' !in title
        if (usable || close < 0) return if (usable) DocLinkMatch(open, close) else null
        open = text.indexOf("[[", open + 2)
    }
    return null
}

/** Joins several texts with hard line breaks; spans keep their source ranges. */
internal fun joinInlineTexts(texts: List<InlineText>): InlineText {
    if (texts.size == 1) return texts[0]
    val plain = StringBuilder()
    val all = ArrayList<InlineSpan>()
    for (t in texts) {
        if (plain.isNotEmpty()) plain.append('\n')
        val offset = plain.length
        plain.append(t.plain)
        t.spans.mapTo(all) { it.copy(plainStart = it.plainStart + offset, plainEnd = it.plainEnd + offset) }
    }
    return InlineText(plain.toString(), all)
}
