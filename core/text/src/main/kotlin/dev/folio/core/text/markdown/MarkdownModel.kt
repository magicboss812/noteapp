package dev.folio.core.text.markdown

/** Inline formatting kinds a span can carry (07-text-engine.md#block-styles). */
enum class SpanKind { BOLD, ITALIC, STRIKE, HIGHLIGHT, CODE, LINK, DOC_LINK, MATH }

/**
 * A styled stretch of an [InlineText]. [sourceStart]..[sourceEnd] address the block source including the markers
 * (so an editor can dim them); [plainStart]..[plainEnd] address [InlineText.plain]. Spans may nest.
 * [target] is the link URL, the note id of a `folio://doc/` link, or the LaTeX of a math span.
 */
data class InlineSpan(
    val kind: SpanKind,
    val sourceStart: Int,
    val sourceEnd: Int,
    val plainStart: Int,
    val plainEnd: Int,
    val target: String? = null,
) {
    /** The document a `folio://doc/` link points to (its target without a `#page=` fragment); null for other spans and `[[Title]]`. */
    val docId: String? get() = if (kind == SpanKind.DOC_LINK) target?.substringBefore('#')?.takeIf { it.isNotEmpty() } else null
}

/**
 * Inline content of one block as plain text plus spans. A hard line break is `\n`, a soft break a space, inline math
 * and note links contribute their LaTeX or title. [plain] is also the search text.
 */
data class InlineText(
    val plain: String,
    val spans: List<InlineSpan> = emptyList(),
) {
    companion object {
        val EMPTY = InlineText("")
    }
}

/** GFM alert flavors for `> [!NOTE]` callouts. */
enum class CalloutType { NOTE, TIP, IMPORTANT, WARNING, CAUTION }

enum class ListKind { BULLET, ORDERED, TASK }

enum class TableAlign { NONE, LEFT, CENTER, RIGHT }

/** Parsed meaning of one block (07-text-engine.md#blocks). Unknown syntax degrades to [Paragraph] with the source as text. */
sealed interface BlockContent {
    /** An empty paragraph: one line box, no text. */
    data object Empty : BlockContent

    data class Paragraph(
        val text: InlineText,
    ) : BlockContent

    data class Heading(
        val level: Int,
        val text: InlineText,
    ) : BlockContent

    /** [number] is the number as written for [ListKind.ORDERED]; [checked] is set for [ListKind.TASK]. */
    data class ListItem(
        val kind: ListKind,
        val level: Int,
        val number: Int?,
        val checked: Boolean?,
        val text: InlineText,
    ) : BlockContent

    /** Quote or callout; [text] has the callout marker removed. */
    data class Quote(
        val callout: CalloutType?,
        val text: InlineText,
    ) : BlockContent

    data class Code(
        val language: String?,
        val code: String,
    ) : BlockContent

    data class Math(
        val latex: String,
    ) : BlockContent

    data class Table(
        val aligns: List<TableAlign>,
        val header: List<InlineText>,
        val rows: List<List<InlineText>>,
    ) : BlockContent

    data object Rule : BlockContent
}

/** Every inline text of the block: its text, or a table's header and body cells row by row; none for code, math and rules. */
fun BlockContent.inlineTexts(): List<InlineText> =
    when (this) {
        is BlockContent.Paragraph -> listOf(text)
        is BlockContent.Heading -> listOf(text)
        is BlockContent.ListItem -> listOf(text)
        is BlockContent.Quote -> listOf(text)
        is BlockContent.Table -> header + rows.flatten()
        BlockContent.Empty, BlockContent.Rule, is BlockContent.Code, is BlockContent.Math -> emptyList()
    }

/** Ids of the documents the note links of this block point to. */
fun BlockContent.linkedDocIds(): Set<String> {
    val texts = inlineTexts()
    if (texts.isEmpty()) return emptySet()
    val out = HashSet<String>()
    for (t in texts) for (span in t.spans) span.docId?.let(out::add)
    return out
}

/**
 * One block of a flow: the unit of parsing, layout caching and editing. [id] is stable within a session and never
 * stored. [source] is the exact Markdown of the block (no line terminator after the last line); [gap] is the
 * separator that follows it (the line terminator plus at most one blank line), so concatenating all
 * `source + gap` reproduces the flow byte for byte.
 */
data class MarkdownBlock(
    val id: Long,
    val source: String,
    val gap: String,
    val content: BlockContent,
)
