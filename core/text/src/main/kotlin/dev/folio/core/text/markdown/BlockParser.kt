package dev.folio.core.text.markdown

import org.commonmark.ext.gfm.strikethrough.Strikethrough
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.ext.gfm.tables.TableBlock
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.ext.gfm.tables.TablesExtension
import org.commonmark.ext.task.list.items.TaskListItemMarker
import org.commonmark.ext.task.list.items.TaskListItemsExtension
import org.commonmark.node.BlockQuote
import org.commonmark.node.BulletList
import org.commonmark.node.Code
import org.commonmark.node.Emphasis
import org.commonmark.node.FencedCodeBlock
import org.commonmark.node.HardLineBreak
import org.commonmark.node.Heading
import org.commonmark.node.HtmlInline
import org.commonmark.node.IndentedCodeBlock
import org.commonmark.node.Link
import org.commonmark.node.ListItem
import org.commonmark.node.Node
import org.commonmark.node.OrderedList
import org.commonmark.node.Paragraph
import org.commonmark.node.SoftLineBreak
import org.commonmark.node.StrongEmphasis
import org.commonmark.node.Text
import org.commonmark.node.ThematicBreak
import org.commonmark.parser.IncludeSourceSpans
import org.commonmark.parser.Parser

/**
 * Parses the source of ONE block into its [BlockContent] (commonmark-java with GFM tables, strikethrough, task lists
 * and the Folio inline extensions). Anything it does not understand degrades to a paragraph holding the source text.
 */
internal class BlockParser {
    /** Number of [parse] calls so far; tests use it to prove an edit re-parses a single block. */
    var parseCount = 0
        private set

    private val parser: Parser =
        Parser
            .builder()
            .extensions(
                listOf(
                    TablesExtension.create(),
                    StrikethroughExtension.create(),
                    TaskListItemsExtension.create(),
                ),
            ).customDelimiterProcessor(HighlightDelimiterProcessor())
            .customInlineContentParserFactory(MathInlineParserFactory())
            .includeSourceSpans(IncludeSourceSpans.BLOCKS_AND_INLINES)
            .build()

    fun parse(source: String): BlockContent {
        parseCount++
        return when {
            source.isBlank() -> BlockContent.Empty
            source.trimStart().startsWith("$$") -> mathBlock(source)
            else -> parseMarkdown(source)
        }
    }

    private fun parseMarkdown(source: String): BlockContent {
        val indent = leadingColumns(source)
        // A list item may be indented by any amount here; commonmark would take 4+ spaces for code.
        val shift = if (isListItemLine(source)) source.length - source.trimStart(' ').length else 0
        val text = source.substring(shift)
        val doc = parser.parse(text)
        val inlines = InlineCollector(text, shift)
        return when (val first = doc.firstChild) {
            is Paragraph -> {
                BlockContent.Paragraph(inlines.of(first))
            }

            is Heading -> {
                BlockContent.Heading(first.level, inlines.of(first))
            }

            is BulletList, is OrderedList -> {
                listItem(first, inlines, indent / 2)
            }

            is BlockQuote -> {
                quote(first, inlines)
            }

            is FencedCodeBlock -> {
                BlockContent.Code(
                    first.info
                        .trim()
                        .substringBefore(' ')
                        .ifEmpty { null },
                    first.literal.trimEnd('\n'),
                )
            }

            is IndentedCodeBlock -> {
                BlockContent.Code(null, first.literal.trimEnd('\n'))
            }

            is ThematicBreak -> {
                BlockContent.Rule
            }

            is TableBlock -> {
                table(first, inlines)
            }

            else -> {
                BlockContent.Paragraph(InlineText(source))
            }
        }
    }

    private fun mathBlock(source: String): BlockContent {
        var body = source.trim().removePrefix("$$")
        body = body.removeSuffix("$$")
        return BlockContent.Math(body.trim())
    }

    private fun listItem(
        list: Node,
        inlines: InlineCollector,
        level: Int,
    ): BlockContent {
        val item = list.firstChild as? ListItem ?: return BlockContent.Paragraph(InlineText(""))
        val marker = item.firstChild as? TaskListItemMarker
        val ordered = list as? OrderedList
        val paragraph = (marker?.next ?: item.firstChild) as? Paragraph
        val text = paragraph?.let { inlines.of(it) } ?: InlineText.EMPTY
        val kind =
            when {
                marker != null -> ListKind.TASK
                ordered != null -> ListKind.ORDERED
                else -> ListKind.BULLET
            }
        return BlockContent.ListItem(kind, level, ordered?.startNumber, marker?.isChecked, text)
    }

    private fun quote(
        quote: BlockQuote,
        inlines: InlineCollector,
    ): BlockContent {
        val callout = calloutOf(quote)
        val texts = ArrayList<InlineText>()
        collectTexts(quote, inlines, texts)
        val joined = joinInlineTexts(texts)
        return BlockContent.Quote(callout, joined)
    }

    /** Detects `[!NOTE]` on the first line of the quote and removes the marker (and its line break) from the tree. */
    private fun calloutOf(quote: BlockQuote): CalloutType? {
        val paragraph = quote.firstChild as? Paragraph ?: return null
        val head = paragraph.firstChild as? Text ?: return null
        val match = CALLOUT.matchEntire(head.literal) ?: return null
        val type = CalloutType.entries.firstOrNull { it.name == match.groupValues[1] } ?: return null
        val after = head.next
        head.unlink()
        if (after is SoftLineBreak || after is HardLineBreak) after.unlink()
        return type
    }

    private fun collectTexts(
        node: Node,
        inlines: InlineCollector,
        out: MutableList<InlineText>,
    ) {
        var child = node.firstChild
        while (child != null) {
            if (child is Paragraph || child is Heading) {
                if (child.firstChild != null) out.add(inlines.of(child))
            } else {
                collectTexts(child, inlines, out)
            }
            child = child.next
        }
    }

    private fun table(
        table: TableBlock,
        inlines: InlineCollector,
    ): BlockContent {
        var aligns = emptyList<TableAlign>()
        var header = emptyList<InlineText>()
        val rows = ArrayList<List<InlineText>>()
        var section = table.firstChild
        while (section != null) {
            var row = section.firstChild
            while (row is TableRow) {
                val cells = ArrayList<InlineText>()
                val rowAligns = ArrayList<TableAlign>()
                var cell = row.firstChild
                while (cell is TableCell) {
                    cells.add(inlines.of(cell))
                    rowAligns.add(alignOf(cell))
                    cell = cell.next
                }
                if (section is TableHead) {
                    header = cells
                    aligns = rowAligns
                } else {
                    rows.add(cells)
                }
                row = row.next
            }
            section = section.next
        }
        return BlockContent.Table(aligns, header, rows)
    }

    private fun alignOf(cell: TableCell): TableAlign =
        when (cell.alignment) {
            TableCell.Alignment.LEFT -> TableAlign.LEFT
            TableCell.Alignment.CENTER -> TableAlign.CENTER
            TableCell.Alignment.RIGHT -> TableAlign.RIGHT
            null -> TableAlign.NONE
        }

    private fun leadingColumns(source: String): Int {
        var columns = 0
        for (c in source) {
            when (c) {
                ' ' -> columns++
                '\t' -> columns += TAB_COLUMNS
                else -> return columns
            }
        }
        return columns
    }

    private fun isListItemLine(source: String): Boolean = LIST_ITEM.containsMatchIn(source.lineSequence().first())

    private companion object {
        const val TAB_COLUMNS = 4
        val CALLOUT = Regex("^\\[!(NOTE|TIP|IMPORTANT|WARNING|CAUTION)]$")
        val LIST_ITEM = Regex("^ +([-*+]|\\d{1,9}[.)])( |$)")
    }
}

/** Flattens inline nodes into [InlineText]; offsets are shifted by [shift] back into the block source. */
internal class InlineCollector(
    source: String,
    private val shift: Int,
) {
    private val lineStarts = lineStarts(source)

    private var sb = StringBuilder()
    private var spans = ArrayList<InlineSpan>()

    fun of(container: Node): InlineText {
        sb = StringBuilder()
        spans = ArrayList()
        children(container)
        return finish()
    }

    private fun finish(): InlineText {
        val sorted = spans.sortedWith(compareBy<InlineSpan> { it.sourceStart }.thenByDescending { it.sourceEnd })
        return InlineText(sb.toString(), sorted)
    }

    private fun visit(node: Node) {
        when (node) {
            is Text -> sb.append(node.literal)
            is SoftLineBreak -> sb.append(' ')
            is HardLineBreak -> sb.append('\n')
            is HtmlInline -> sb.append(node.literal)
            is Code -> leaf(node, SpanKind.CODE, node.literal, null)
            is MathInline -> leaf(node, SpanKind.MATH, node.latex, node.latex)
            is Emphasis -> wrap(node, SpanKind.ITALIC, null)
            is StrongEmphasis -> wrap(node, SpanKind.BOLD, null)
            is Strikethrough -> wrap(node, SpanKind.STRIKE, null)
            is Highlight -> wrap(node, SpanKind.HIGHLIGHT, null)
            is Link -> link(node)
            else -> children(node)
        }
    }

    private fun children(node: Node) {
        var child = node.firstChild
        while (child != null) {
            if (child is Text) {
                // commonmark leaves "[" and "]" of an unresolved `[[Title]]` as separate text nodes: scan the run.
                val run = ArrayList<Text>()
                var t: Node? = child
                while (t is Text) {
                    run.add(t)
                    t = t.next
                }
                textRun(run)
                child = t
            } else {
                visit(child)
                child = child.next
            }
        }
    }

    /** Appends consecutive text nodes, turning `[[Title]]` into a DOC_LINK span when the source offsets are known. */
    private fun textRun(run: List<Text>) {
        val joined = StringBuilder()
        val sourceAt = ArrayList<Int>()
        for (t in run) {
            val range = sourceRange(t)
            val mapped = range != null && range.last + 1 - range.first == t.literal.length
            for (i in t.literal.indices) sourceAt.add(if (mapped && range != null) range.first + i else -1)
            joined.append(t.literal)
        }
        val text = joined.toString()
        var pos = 0
        var match = findDocLink(text, pos, sourceAt)
        while (match != null) {
            sb.append(text, pos, match.open)
            val plainStart = sb.length
            sb.append(text, match.open + 2, match.close)
            spans.add(InlineSpan(SpanKind.DOC_LINK, sourceAt[match.open], sourceAt[match.close + 1] + 1, plainStart, sb.length))
            pos = match.close + 2
            match = findDocLink(text, pos, sourceAt)
        }
        sb.append(text, pos, text.length)
    }

    private fun leaf(
        node: Node,
        kind: SpanKind,
        plain: String,
        target: String?,
    ) {
        val start = sb.length
        sb.append(plain)
        add(node, kind, start, target)
    }

    private fun wrap(
        node: Node,
        kind: SpanKind,
        target: String?,
    ) {
        val start = sb.length
        children(node)
        add(node, kind, start, target)
    }

    private fun link(node: Link) {
        val dest = node.destination.orEmpty()
        if (dest.startsWith(DOC_SCHEME)) {
            wrap(node, SpanKind.DOC_LINK, dest.removePrefix(DOC_SCHEME))
        } else {
            wrap(node, SpanKind.LINK, dest)
        }
    }

    private fun add(
        node: Node,
        kind: SpanKind,
        plainStart: Int,
        target: String?,
    ) {
        val range = sourceRange(node) ?: return
        spans.add(InlineSpan(kind, range.first, range.last + 1, plainStart, sb.length, target))
    }

    /** Smallest source range covering the node's spans, or its children's when it has none of its own. */
    private fun sourceRange(node: Node): IntRange? {
        var min = Int.MAX_VALUE
        var max = -1

        fun take(n: Node) {
            for (s in n.sourceSpans) {
                val start = lineStarts[s.lineIndex] + s.columnIndex + shift
                min = minOf(min, start)
                max = maxOf(max, start + s.length - 1)
            }
        }
        take(node)
        if (max < 0) {
            var child = node.firstChild
            while (child != null) {
                take(child)
                child = child.next
            }
        }
        return if (max < 0) null else min..max
    }

    private companion object {
        const val DOC_SCHEME = "folio://doc/"

        fun lineStarts(text: String): IntArray {
            val starts = ArrayList<Int>()
            starts.add(0)
            var i = 0
            while (i < text.length) {
                val c = text[i]
                if (c == '\n') {
                    starts.add(i + 1)
                } else if (c == '\r' && (i + 1 >= text.length || text[i + 1] != '\n')) {
                    starts.add(i + 1)
                }
                i++
            }
            return starts.toIntArray()
        }
    }
}
