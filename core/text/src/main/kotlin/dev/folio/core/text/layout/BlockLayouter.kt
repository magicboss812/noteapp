package dev.folio.core.text.layout

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import dev.folio.core.common.PerfMonitor
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.fonts.TextFontFamily
import dev.folio.core.text.fonts.TextRole
import dev.folio.core.text.fonts.TextSizing
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.CalloutType
import dev.folio.core.text.markdown.InlineText
import dev.folio.core.text.markdown.ListKind
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.SpanKind
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Lays out Markdown blocks on the grid (07-text-engine.md#line-box): font sizes from
 * [TextSizing.derive], line boxes of k x U, text laid out at the grid-pitch reference scale
 * r = round(4U)/U px per pt with linear metrics, then shifted so the first measured baseline sits
 * on its rule. Results are cached per (block source, width, flow style, grid), least recently used
 * first out. Thread-safe.
 *
 * Alignment: CENTER applies to paragraphs and headings, JUSTIFIED to paragraphs; other blocks
 * start-align. Math blocks and tables are laid out as source text until P06-T07 / P06-T08.
 */
class BlockLayouter(
    private val resolver: FontFamily.Resolver,
    private val metrics: FontMetricsCache,
    private val registry: FontRegistry = FontRegistry.Bundled,
    private val maxCached: Int = DEFAULT_CACHE_SIZE,
) {
    private data class Key(
        val source: String,
        val widthPt: Float,
        val style: FlowTextStyle,
        val grid: LayoutGrid,
    )

    private val cache =
        object : LinkedHashMap<Key, BlockLayout>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, BlockLayout>): Boolean = size > maxCached
        }

    /** Number of cached layouts. */
    val cachedCount: Int get() = synchronized(cache) { cache.size }

    /** Layout of [block] in a frame [widthPt] wide, from the cache when the same block was laid out with the same settings. */
    fun layout(
        block: MarkdownBlock,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
    ): BlockLayout {
        val key = Key(block.source, widthPt, style, grid)
        synchronized(cache) { cache[key] }?.let { return it }
        val layout = PerfMonitor.trace(TRACE_LAYOUT) { build(block.content, widthPt, style, grid) }
        synchronized(cache) { cache[key] = layout }
        return layout
    }

    /** Drops every cached layout (fonts or palette changed). */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }

    /** Uncached layout of [content]. */
    fun build(
        content: BlockContent,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
    ): BlockLayout {
        val g = Geometry(grid, widthPt, (grid.unitPt * REFERENCE_SCALE).roundToInt().coerceAtLeast(1))
        val family = registry.resolve(style.familyName)
        return when (content) {
            BlockContent.Empty -> {
                empty(g, style, rule = false)
            }

            BlockContent.Rule -> {
                empty(g, style, rule = true)
            }

            is BlockContent.Paragraph -> {
                textBlock(g, style, family, TextRole.BODY, content.text, align(style.alignment, justify = true))
            }

            is BlockContent.Heading -> {
                textBlock(
                    g,
                    style,
                    family,
                    headingRole(content.level),
                    content.text,
                    align(style.alignment, justify = false),
                )
            }

            is BlockContent.ListItem -> {
                listItem(g, style, family, content)
            }

            is BlockContent.Quote -> {
                quote(g, style, family, content)
            }

            is BlockContent.Code -> {
                code(g, style, content.code)
            }

            is BlockContent.Math -> {
                code(g, style, content.latex)
            }

            is BlockContent.Table -> {
                table(g, style, family, content)
            }
        }
    }

    /** Grid facts shared by every paragraph of one block: [pitchPx] = one U in layout px (whole). */
    private class Geometry(
        val grid: LayoutGrid,
        val widthPt: Float,
        val pitchPx: Int,
    ) {
        val unitPt: Float get() = grid.unitPt
        val refScale: Float = pitchPx / grid.unitPt
    }

    /** One body line box without text; a [rule] is drawn at its center. */
    private fun empty(
        g: Geometry,
        style: FlowTextStyle,
        rule: Boolean,
    ): BlockLayout {
        val units = TextSizing.boxUnits(TextRole.BODY, TextSizing.capFraction(TextRole.BODY, style.bodyCap))
        val decoration = if (rule) BlockDecoration.Rule(yPt = units * g.unitPt / 2f) else BlockDecoration.None
        return BlockLayout(null, null, g.unitPt, units, 1, 0f, 0f, 0f, decoration)
    }

    private fun textBlock(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        role: TextRole,
        inline: InlineText,
        textAlign: TextAlign,
        leftPt: Float = 0f,
        rightPt: Float = 0f,
        marker: Marker? = null,
        decoration: BlockDecoration = BlockDecoration.None,
        prefix: String? = null,
    ): BlockLayout {
        val size = TextSizing.derive(metrics, family, role, style.bodyCap, g.unitPt)
        val offset = prefix?.let { it.length + 1 } ?: 0
        val plain = if (prefix == null) inline.plain else "$prefix\n${inline.plain}"
        val spans = spanStyles(inline, offset, size.capFraction, g, style, family)
        if (prefix != null) spans.add(0, AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, prefix.length))
        val text =
            gridText(
                plain,
                spans,
                family,
                role.bold,
                size.fontSizePt,
                size.boxUnits,
                g,
                widthPt = g.widthPt - leftPt - rightPt,
                textAlign = textAlign,
            )
        // The label keeps its natural width on one line and is placed in the gutter (it may overhang a narrow one).
        val markerText =
            marker?.let { gridText(it.label, emptyList(), family, false, size.fontSizePt, size.boxUnits, g, g.widthPt, TextAlign.Start) }
        val markerLeft =
            if (marker == null || markerText == null) {
                0f
            } else {
                val free = marker.widthPt - markerText.lineRightPt(0)
                max(0f, marker.leftPt + if (marker.centered) free / 2f else free)
            }
        return BlockLayout(text, markerText, g.unitPt, size.boxUnits, text.lineCount, leftPt, markerLeft, size.fontSizePt, decoration)
    }

    /** A list label in the gutter [leftPt]..[leftPt] + [widthPt]: centered (bullets) or end-aligned (numbers). */
    private class Marker(
        val label: String,
        val leftPt: Float,
        val widthPt: Float,
        val centered: Boolean,
    )

    private fun listItem(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        item: BlockContent.ListItem,
    ): BlockLayout {
        val step = g.grid.indentStepPt
        val gutterLeft = min(item.level * step, g.widthPt / 2f)
        val left = gutterLeft + step
        val cap = TextSizing.capFraction(TextRole.BODY, style.bodyCap)
        return when (item.kind) {
            ListKind.TASK -> {
                val sizePt = CHECKBOX_UNITS * g.unitPt
                val baseline = TextSizing.boxUnits(TextRole.BODY, cap) * g.unitPt
                val box =
                    BlockDecoration.Checkbox(
                        gutterLeft + max(0f, (step - sizePt) / 2f),
                        baseline - sizePt,
                        sizePt,
                        item.checked == true,
                    )
                textBlock(g, style, family, TextRole.BODY, item.text, TextAlign.Start, leftPt = left, decoration = box)
            }

            ListKind.BULLET -> {
                val marker = Marker(ListLabels.bullet(item.level), gutterLeft, step, centered = true)
                textBlock(g, style, family, TextRole.BODY, item.text, TextAlign.Start, leftPt = left, marker = marker)
            }

            ListKind.ORDERED -> {
                val marker =
                    Marker(
                        ListLabels.ordered(item.number ?: 1, item.level),
                        gutterLeft,
                        step - MARKER_GAP_UNITS * g.unitPt,
                        centered = false,
                    )
                textBlock(g, style, family, TextRole.BODY, item.text, TextAlign.Start, leftPt = left, marker = marker)
            }
        }
    }

    private fun quote(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        quote: BlockContent.Quote,
    ): BlockLayout {
        val step = g.grid.indentStepPt
        val bar = BlockDecoration.QuoteBar(step * QUOTE_BAR_LEFT, g.unitPt * QUOTE_BAR_UNITS, quote.callout)
        return textBlock(
            g,
            style,
            family,
            TextRole.BODY,
            quote.text,
            TextAlign.Start,
            leftPt = step,
            decoration = bar,
            prefix = quote.callout?.let(::calloutTitle),
        )
    }

    private fun code(
        g: Geometry,
        style: FlowTextStyle,
        code: String,
    ): BlockLayout {
        val inset = CODE_INSET_UNITS * g.unitPt
        val mono = registry.codeFamily(style.familyName)
        return textBlock(
            g,
            style,
            mono,
            TextRole.CODE,
            InlineText(code),
            TextAlign.Start,
            leftPt = inset,
            rightPt = inset,
            decoration = BlockDecoration.CodeBackground,
        )
    }

    private fun table(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        table: BlockContent.Table,
    ): BlockLayout {
        val header = table.header.joinToString(CELL_SEPARATOR) { it.plain }
        if (table.rows.isEmpty()) return textBlock(g, style, family, TextRole.BODY, InlineText(header), TextAlign.Start)
        val body = table.rows.joinToString("\n") { row -> row.joinToString(CELL_SEPARATOR) { it.plain } }
        return textBlock(g, style, family, TextRole.BODY, InlineText(body), TextAlign.Start, prefix = header)
    }

    private fun gridText(
        plain: String,
        spans: List<AnnotatedString.Range<SpanStyle>>,
        family: TextFontFamily,
        bold: Boolean,
        fontSizePt: Float,
        boxUnits: Int,
        g: Geometry,
        widthPt: Float,
        textAlign: TextAlign,
    ): GridText {
        // Line height is a whole px count: Compose rounds line heights up, so a fraction would drift per line (A-006).
        val lineHeightPx = (boxUnits * g.pitchPx).toFloat()
        val style =
            TextStyle(
                fontFamily = family.fontFamily,
                fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
                fontSize = (fontSizePt * g.refScale).sp,
                lineHeight = lineHeightPx.sp,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Bottom, LineHeightStyle.Trim.None),
                textAlign = textAlign,
                textMotion = TextMotion.Animated, // linear, subpixel metrics: line breaks never change with zoom
                platformStyle = PlatformTextStyle(includeFontPadding = false),
            )
        val paragraph =
            Paragraph(
                text = plain,
                style = style,
                constraints = Constraints(maxWidth = max(1, ceil(widthPt * g.refScale).toInt())),
                density = UNIT_DENSITY,
                fontFamilyResolver = resolver,
                spanStyles = spans,
            )
        return GridText(paragraph, g.refScale, lineHeightPx - paragraph.getLineBaseline(0))
    }

    private fun spanStyles(
        inline: InlineText,
        offset: Int,
        roleCap: Float,
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
    ): MutableList<AnnotatedString.Range<SpanStyle>> {
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>(inline.spans.size + 1)
        if (inline.spans.isEmpty()) return out
        val length = inline.plain.length
        // Inline code: the monospace family at the code-to-body cap ratio of the surrounding role.
        val code =
            if (inline.spans.none { it.kind == SpanKind.CODE }) {
                null
            } else {
                val mono = registry.codeFamily(family.name)
                val cap = roleCap * TextRole.CODE.capFractionAtM / TextRole.BODY.capFractionAtM
                val sizePt = TextSizing.fontSizePt(metrics.proportions(mono).capRatio, cap, g.unitPt)
                SpanStyle(fontFamily = mono.fontFamily, fontSize = (sizePt * g.refScale).sp, background = style.palette.codeBackground)
            }
        for (span in inline.spans) {
            val start = span.plainStart.coerceIn(0, length)
            val end = span.plainEnd.coerceIn(start, length)
            if (start == end) continue
            val spanStyle =
                when (span.kind) {
                    SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                    SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                    SpanKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                    SpanKind.HIGHLIGHT -> SpanStyle(background = style.palette.highlight)
                    SpanKind.CODE -> checkNotNull(code)
                    SpanKind.LINK, SpanKind.DOC_LINK -> SpanStyle(color = style.palette.link, textDecoration = TextDecoration.Underline)
                    SpanKind.MATH -> SpanStyle(fontStyle = FontStyle.Italic) // MathRenderer placeholders: P06-T07
                }
            out += AnnotatedString.Range(spanStyle, start + offset, end + offset)
        }
        return out
    }

    companion object {
        /** Target layout px per pt; the exact scale is round(4U)/U (07-text-engine.md#line-box). */
        const val REFERENCE_SCALE = 4f

        /** Cached layouts per layouter. */
        const val DEFAULT_CACHE_SIZE = 2048

        /** Trace section of one uncached block layout. */
        const val TRACE_LAYOUT = "text:layout"

        private const val INITIAL_CAPACITY = 64
        private const val LOAD_FACTOR = 0.75f
        private const val CHECKBOX_UNITS = 0.7f
        private const val MARKER_GAP_UNITS = 0.25f
        private const val QUOTE_BAR_LEFT = 0.25f
        private const val QUOTE_BAR_UNITS = 0.12f
        private const val CODE_INSET_UNITS = 0.5f
        private const val CELL_SEPARATOR = "  |  "

        /** 1 sp = 1 px so styles are given in layout px. */
        private val UNIT_DENSITY = Density(density = 1f, fontScale = 1f)

        private fun headingRole(level: Int): TextRole =
            when (level) {
                1 -> TextRole.H1
                2 -> TextRole.H2
                else -> TextRole.H3
            }

        private fun align(
            alignment: FlowAlignment,
            justify: Boolean,
        ): TextAlign =
            when (alignment) {
                FlowAlignment.START -> TextAlign.Start
                FlowAlignment.CENTER -> TextAlign.Center
                FlowAlignment.JUSTIFIED -> if (justify) TextAlign.Justify else TextAlign.Start
            }

        private fun calloutTitle(type: CalloutType): String = type.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}
