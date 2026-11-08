package dev.folio.core.text.layout

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.Paragraph
import androidx.compose.ui.text.ParagraphIntrinsics
import androidx.compose.ui.text.Placeholder
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
import dev.folio.core.common.Outcome
import dev.folio.core.common.PerfMonitor
import dev.folio.core.text.edit.EditMarkers
import dev.folio.core.text.fonts.DerivedSize
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
import dev.folio.core.text.markdown.TableAlign
import dev.folio.core.text.markdown.linkedDocIds
import dev.folio.core.text.math.MathRenderer
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
 * Alignment (10-editor-ui.md#text-boxes): paragraphs, list items and quotes take the flow's alignment, headings
 * too except JUSTIFIED (start); code and block math start-align; table columns take their own alignment. Formulas go through [math]
 * (07-text-engine.md#math); without a renderer inline math is italic source and math blocks are code.
 */
@Suppress("TooManyFunctions", "LargeClass") // one builder per block kind plus the shared grid text steps
class BlockLayouter(
    private val resolver: FontFamily.Resolver,
    private val metrics: FontMetricsCache,
    /** The families to lay out with; a new registry (fonts imported) drops the cached layouts. */
    private val fonts: () -> FontRegistry = { FontRegistry.Bundled },
    private val maxCached: Int = DEFAULT_CACHE_SIZE,
    private val math: MathRenderer? = null,
) {
    @Volatile private var registry: FontRegistry = fonts()

    private data class Key(
        val source: String,
        val widthPt: Float,
        val style: FlowTextStyle,
        val grid: LayoutGrid,
        val edit: Boolean,
    )

    private val cache =
        object : LinkedHashMap<Key, BlockLayout>(INITIAL_CAPACITY, LOAD_FACTOR, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, BlockLayout>): Boolean = size > maxCached
        }

    private val formulas = math?.let(::MathFormulas)

    /** Number of cached layouts. */
    val cachedCount: Int get() = synchronized(cache) { cache.size }

    /** Layout of [block] in a frame [widthPt] wide, from the cache when the same block was laid out with the same settings. */
    fun layout(
        block: MarkdownBlock,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
    ): BlockLayout = cached(block, widthPt, style, grid, edit = false)

    /**
     * Layout of [block] while it is edited (07-text-engine.md#rendering, A-058): its source in the block's role, indent
     * and frame width with the markers dimmed and math as LaTeX, so the canvas draws the edited text paginated like any
     * other block. Offsets into its [BlockLayout.plainText] are source offsets ([BlockLayout.editsSource]). A table keeps
     * its normal layout (the live field edits one cell).
     */
    fun editLayout(
        block: MarkdownBlock,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
    ): BlockLayout = cached(block, widthPt, style, grid, edit = true)

    private fun cached(
        block: MarkdownBlock,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
        edit: Boolean,
    ): BlockLayout {
        val current = fonts()
        if (current !== registry) {
            // Imported fonts changed what a family name resolves to: earlier layouts may use a fallback.
            clear()
            registry = current
        }
        val keyStyle = styleFor(block.content, style)
        val key = Key(block.source, widthPt, keyStyle, grid, edit)
        synchronized(cache) { cache[key] }?.let { return it }
        val layout =
            PerfMonitor.trace(TRACE_LAYOUT) {
                if (edit) buildEdit(block, widthPt, keyStyle, grid) else build(block.content, widthPt, keyStyle, grid)
            }
        synchronized(cache) { cache[key] = layout }
        return layout
    }

    /** Body font size in pt of [style] on a grid of [unitPt] (the box toolbar's size number, P07-T17). */
    fun bodyFontSizePt(
        style: FlowTextStyle,
        unitPt: Float,
    ): Float = TextSizing.derive(metrics, fonts().resolve(style.familyName), TextRole.BODY, style.bodyCap, unitPt).fontSizePt

    /** Drops every cached layout (fonts or palette changed). */
    fun clear() {
        synchronized(cache) { cache.clear() }
        formulas?.clear()
    }

    /** Uncached layout of [content]. */
    fun build(
        content: BlockContent,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
    ): BlockLayout {
        val g = geometry(grid, widthPt, style)
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
                mathBlock(g, style, family, content.latex)
            }

            is BlockContent.Table -> {
                table(g, style, family, content)
            }
        }
    }

    // The source of [block] laid out for editing; see editLayout.
    private fun buildEdit(
        block: MarkdownBlock,
        widthPt: Float,
        style: FlowTextStyle,
        grid: LayoutGrid,
    ): BlockLayout {
        val content = block.content
        if (content is BlockContent.Table) return build(content, widthPt, style, grid)
        val g = geometry(grid, widthPt, style)
        val family = registry.resolve(style.familyName)
        val step = g.grid.indentStepPt
        val source = EditSource(block.source, EditMarkers.ranges(block), layout(block, widthPt, style, grid).mathErrors)
        return when (content) {
            BlockContent.Empty, BlockContent.Rule, is BlockContent.Table -> {
                sourceBlock(g, style, family, TextRole.BODY, source, null, TextAlign.Start)
            }

            is BlockContent.Paragraph -> {
                sourceBlock(g, style, family, TextRole.BODY, source, content.text, align(style.alignment, justify = true))
            }

            is BlockContent.Heading -> {
                sourceBlock(g, style, family, headingRole(content.level), source, content.text, align(style.alignment, justify = false))
            }

            is BlockContent.ListItem -> {
                // The marker takes the gutter: the source starts where the bullet is drawn.
                val left = min(content.level * step, g.widthPt / 2f)
                sourceBlock(g, style, family, TextRole.BODY, source, content.text, align(style.alignment, justify = true), leftPt = left)
            }

            is BlockContent.Quote -> {
                val bar = BlockDecoration.QuoteBar(step * QUOTE_BAR_LEFT, g.unitPt * QUOTE_BAR_UNITS, content.callout)
                val aligned = align(style.alignment, justify = true)
                sourceBlock(g, style, family, TextRole.BODY, source, content.text, aligned, leftPt = step, decoration = bar)
            }

            is BlockContent.Code -> {
                val inset = CODE_INSET_UNITS * g.unitPt
                val mono = registry.codeFamily(style.familyName)
                sourceBlock(g, style, mono, TextRole.CODE, source, null, TextAlign.Start, inset, inset, BlockDecoration.CodeBackground)
            }

            is BlockContent.Math -> {
                sourceBlock(g, style, registry.codeFamily(style.familyName), TextRole.CODE, source, null, TextAlign.Start)
            }
        }
    }

    // A block's source with its syntax ranges (dimmed) and the formula errors of its normal layout (the field shows them).
    private class EditSource(
        val text: String,
        val dimmed: IntArray,
        val mathErrors: List<MathError>,
    )

    private fun sourceBlock(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        role: TextRole,
        source: EditSource,
        inline: InlineText?,
        textAlign: TextAlign,
        leftPt: Float = 0f,
        rightPt: Float = 0f,
        decoration: BlockDecoration = BlockDecoration.None,
    ): BlockLayout {
        val size = TextSizing.derive(metrics, family, role, style.bodyCap, g.unitPt)
        val length = source.text.length
        val spans = ArrayList<AnnotatedString.Range<SpanStyle>>()
        if (inline != null) {
            val code by lazy { monoStyle(family, size.capFraction, g).copy(background = style.palette.codeBackground) }
            for (span in inline.spans) {
                val start = span.sourceStart.coerceIn(0, length)
                val end = span.sourceEnd.coerceIn(start, length)
                // Math stays LaTeX in the body face; marks keep their look around the dimmed delimiters.
                val spanStyle = if (span.kind == SpanKind.CODE) code else markStyle(span.kind, style.palette)
                if (start < end && spanStyle != null) spans += AnnotatedString.Range(spanStyle, start, end)
            }
        }
        val dim = SpanStyle(color = style.palette.marker)
        for (i in 0 until source.dimmed.size - 1 step 2) {
            val start = source.dimmed[i].coerceIn(0, length)
            val end = source.dimmed[i + 1].coerceIn(start, length)
            if (start < end) spans += AnnotatedString.Range(dim, start, end)
        }
        val text =
            gridText(source.text, spans, family, role.bold, size.fontSizePt, size.boxUnits, g, g.widthPt - leftPt - rightPt, textAlign)
        return BlockLayout(
            text = text,
            marker = null,
            unitPt = g.unitPt,
            boxUnits = size.boxUnits,
            lineCount = text.lineCount,
            textLeftPt = leftPt,
            markerLeftPt = leftPt,
            fontSizePt = size.fontSizePt,
            decoration = decoration,
            face = BlockFace(family.fontFamily, role.bold),
            mathErrors = source.mathErrors,
            liftPt = g.liftPt,
            editsSource = true,
        )
    }

    private fun geometry(
        grid: LayoutGrid,
        widthPt: Float,
        style: FlowTextStyle,
    ): Geometry = Geometry(grid, widthPt, (grid.unitPt * REFERENCE_SCALE).roundToInt().coerceAtLeast(1), style.baselineLiftUnits)

    /**
     * Grid facts shared by every paragraph of one block: [pitchPx] = one U in layout px (whole); baselines sit
     * [liftUnits] x U above the bottom of their line box (A-054).
     */
    private class Geometry(
        val grid: LayoutGrid,
        val widthPt: Float,
        val pitchPx: Int,
        liftUnits: Float,
    ) {
        val unitPt: Float get() = grid.unitPt
        val refScale: Float = pitchPx / grid.unitPt
        val liftPx: Float = liftUnits * pitchPx
        val liftPt: Float = liftUnits * grid.unitPt
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
        sourceError: String? = null,
    ): BlockLayout {
        val size = TextSizing.derive(metrics, family, role, style.bodyCap, g.unitPt)
        val offset = prefix?.let { it.length + 1 } ?: 0
        val plain = if (prefix == null) inline.plain else "$prefix\n${inline.plain}"
        val inlineMath = formulas?.inline(inline, size.fontSizePt * g.refScale, (size.boxUnits * g.pitchPx).toFloat()).orEmpty()
        val spans = spanStyles(inline, offset, size.capFraction, g, style, family, inlineMath)
        if (prefix != null) spans.add(0, AnnotatedString.Range(SpanStyle(fontWeight = FontWeight.Bold), 0, prefix.length))
        if (sourceError != null) spans.add(AnnotatedString.Range(SpanStyle(color = style.palette.mathError), 0, plain.length))
        val shown = inlineMath.filter { it.layout != null }
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
                placeholders = shown.map { it.placeholder(offset) },
            )
        val maths =
            shown.mapIndexedNotNull { i, f ->
                val rect = text.placeholderPt(i) ?: return@mapIndexedNotNull null
                MathPlacement(checkNotNull(f.layout), f.scale / g.refScale, leftPt + rect.left, rect.bottom, f.tall)
            }
        val errors =
            if (sourceError != null) {
                listOf(MathError(0, plain.length, sourceError))
            } else {
                inlineMath.mapNotNull { f -> f.error?.let { MathError(f.span.plainStart + offset, f.span.plainEnd + offset, it) } }
            }
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
        val labelShift = labelShiftPt(text, textAlign, marker != null, decoration)
        val face = BlockFace(family.fontFamily, role.bold)
        return BlockLayout(
            text,
            markerText,
            g.unitPt,
            size.boxUnits,
            text.lineCount,
            leftPt,
            markerLeft,
            size.fontSizePt,
            decoration.shiftedBy(labelShift),
            face,
            maths,
            errors,
            labelShiftPt = labelShift,
        )
    }

    /** Math block (07-text-engine.md#math): 1.15 x body size, fit to the width, centered in whole 1U lines. */
    private fun mathBlock(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        latex: String,
    ): BlockLayout {
        val body = TextSizing.derive(metrics, family, TextRole.BODY, style.bodyCap, g.unitPt)
        val out = formulas?.layout(latex, body.fontSizePt * BLOCK_MATH_SIZE * g.refScale, display = true) ?: return code(g, style, latex)
        val layout =
            when (out) {
                is Outcome.Failure -> return code(g, style, latex, out.errorMessage())
                is Outcome.Success -> out.value
            }
        val box = layout.box
        val widthPx = g.widthPt * g.refScale
        val fit = if (box.widthPx > widthPx) max(MIN_BLOCK_SCALE, widthPx / box.widthPx) else 1f
        val heightPx = (box.ascentPx + box.depthPx) * fit
        val lines = max(1, ceil((heightPx + BLOCK_MATH_PAD_UNITS * g.pitchPx) / g.pitchPx).toInt())
        val topPx = (lines * g.pitchPx - heightPx) / 2f
        val leftPx = max(0f, (widthPx - box.widthPx * fit) / 2f)
        val placement =
            MathPlacement(layout, fit / g.refScale, leftPx / g.refScale, (topPx + box.ascentPx * fit) / g.refScale, tall = false)
        // The live field edits the source in the code face.
        val mono = registry.codeFamily(style.familyName)
        val code = TextSizing.derive(metrics, mono, TextRole.CODE, style.bodyCap, g.unitPt)
        return BlockLayout(
            text = null,
            marker = null,
            unitPt = g.unitPt,
            boxUnits = 1,
            lineCount = lines,
            textLeftPt = 0f,
            markerLeftPt = 0f,
            fontSizePt = code.fontSizePt,
            decoration = BlockDecoration.None,
            face = BlockFace(mono.fontFamily, bold = false),
            maths = listOf(placement),
            liftPt = g.liftPt,
        )
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
        val aligned = align(style.alignment, justify = true)
        return when (item.kind) {
            ListKind.TASK -> {
                val sizePt = CHECKBOX_UNITS * g.unitPt
                val baseline = TextSizing.boxUnits(TextRole.BODY, cap) * g.unitPt - g.liftPt
                val box =
                    BlockDecoration.Checkbox(
                        gutterLeft + max(0f, (step - sizePt) / 2f),
                        baseline - sizePt,
                        sizePt,
                        item.checked == true,
                    )
                textBlock(g, style, family, TextRole.BODY, item.text, aligned, leftPt = left, decoration = box)
            }

            ListKind.BULLET -> {
                val marker = Marker(ListLabels.bullet(item.level), gutterLeft, step, centered = true)
                textBlock(g, style, family, TextRole.BODY, item.text, aligned, leftPt = left, marker = marker)
            }

            ListKind.ORDERED -> {
                val marker =
                    Marker(
                        ListLabels.ordered(item.number ?: 1, item.level),
                        gutterLeft,
                        step - MARKER_GAP_UNITS * g.unitPt,
                        centered = false,
                    )
                textBlock(g, style, family, TextRole.BODY, item.text, aligned, leftPt = left, marker = marker)
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
            align(style.alignment, justify = true),
            leftPt = step,
            decoration = bar,
            prefix = quote.callout?.let(::calloutTitle),
        )
    }

    /** A code block; [error] marks the source of a math block that does not parse (drawn red). */
    private fun code(
        g: Geometry,
        style: FlowTextStyle,
        code: String,
        error: String? = null,
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
            sourceError = error,
        )
    }

    /** A table cell measured but not yet broken into lines: its text, the formulas shown in it and those that failed. */
    private class TableCellText(
        val intrinsics: ParagraphIntrinsics,
        val plain: String,
        val shown: List<InlineFormula>,
        val failed: List<InlineFormula>,
    )

    /**
     * Table (07-text-engine.md#tables): columns share the width in proportion to their unwrapped content (at least
     * [TableColumns.MIN_COLUMN_PT] each), every row is as many body lines as its tallest cell, the header is bold.
     */
    private fun table(
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        table: BlockContent.Table,
    ): BlockLayout {
        val size = TextSizing.derive(metrics, family, TextRole.BODY, style.bodyCap, g.unitPt)
        val columns = max(1, table.header.size)
        val aligns = List(columns) { table.aligns.getOrNull(it) ?: TableAlign.NONE }
        val rows = listOf(table.header) + table.rows
        val cells = tableCells(rows, aligns, size, g, style, family)
        val paddingPt = CELL_PAD_UNITS * g.unitPt
        // Widest word and unwrapped width of every column, padding included.
        val least = FloatArray(columns)
        val natural = FloatArray(columns)
        for ((i, cell) in cells.withIndex()) {
            val c = i % columns
            least[c] = max(least[c], ceil(cell.intrinsics.minIntrinsicWidth) / g.refScale + 2f * paddingPt)
            natural[c] = max(natural[c], ceil(cell.intrinsics.maxIntrinsicWidth) / g.refScale + 2f * paddingPt)
        }
        // Grid templates (indent step = U) put the column borders on grid lines.
        val snap = if (g.grid.indentStepPt == g.unitPt) g.unitPt else 0f
        val edges = TableColumns.edges(TableColumns.widths(least, natural, g.widthPt), snap)
        val texts =
            Array(cells.size) { i ->
                val c = i % columns
                gridText(cells[i].intrinsics, cells[i].plain, size.boxUnits, g, edges[c + 1] - edges[c] - 2f * paddingPt)
            }
        val rowStarts = IntArray(rows.size + 1)
        for (r in rows.indices) {
            var lines = 1
            for (c in 0 until columns) lines = max(lines, texts[r * columns + c].lineCount)
            rowStarts[r + 1] = rowStarts[r] + lines
        }
        val lineHeightPt = size.boxUnits * g.unitPt
        val maths = ArrayList<MathPlacement>()
        val errors = ArrayList<MathError>()
        for ((i, cell) in cells.withIndex()) {
            val left = edges[i % columns] + paddingPt
            val top = rowStarts[i / columns] * lineHeightPt
            for ((p, f) in cell.shown.withIndex()) {
                val rect = texts[i].placeholderPt(p) ?: continue
                maths += MathPlacement(checkNotNull(f.layout), f.scale / g.refScale, left + rect.left, top + rect.bottom, f.tall, cell = i)
            }
            for (f in cell.failed) errors += MathError(f.span.plainStart, f.span.plainEnd, checkNotNull(f.error), cell = i)
        }
        val geometry = TableLayout(edges, rowStarts, texts, paddingPt, aligns, BlockFace(family.fontFamily, bold = true))
        return BlockLayout(
            text = null,
            marker = null,
            unitPt = g.unitPt,
            boxUnits = size.boxUnits,
            lineCount = rowStarts[rows.size],
            textLeftPt = 0f,
            markerLeftPt = 0f,
            fontSizePt = size.fontSizePt,
            decoration = BlockDecoration.Table,
            face = BlockFace(family.fontFamily, bold = false),
            maths = maths,
            mathErrors = errors,
            table = geometry,
            liftPt = g.liftPt,
        )
    }

    // Every cell of [rows] measured unwrapped, row by row, each row [aligns] long (missing cells empty).
    private fun tableCells(
        rows: List<List<InlineText>>,
        aligns: List<TableAlign>,
        size: DerivedSize,
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
    ): List<TableCellText> {
        val boxPx = (size.boxUnits * g.pitchPx).toFloat()
        return rows.flatMapIndexed { r, row ->
            List(aligns.size) { c ->
                val inline = row.getOrNull(c) ?: InlineText("")
                val formulas = formulas?.inline(inline, size.fontSizePt * g.refScale, boxPx).orEmpty()
                val spans = spanStyles(inline, 0, size.capFraction, g, style, family, formulas)
                val shown = formulas.filter { it.layout != null }
                val intrinsics =
                    intrinsics(
                        inline.plain,
                        spans,
                        family,
                        bold = r == 0,
                        size.fontSizePt,
                        size.boxUnits,
                        g,
                        cellAlign(aligns[c]),
                        shown.map { it.placeholder(0) },
                    )
                TableCellText(intrinsics, inline.plain, shown, formulas.filter { it.error != null })
            }
        }
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
        placeholders: List<AnnotatedString.Range<Placeholder>> = emptyList(),
    ): GridText =
        gridText(
            intrinsics(plain, spans, family, bold, fontSizePt, boxUnits, g, textAlign, placeholders),
            plain,
            boxUnits,
            g,
            widthPt,
        )

    private fun intrinsics(
        plain: String,
        spans: List<AnnotatedString.Range<SpanStyle>>,
        family: TextFontFamily,
        bold: Boolean,
        fontSizePt: Float,
        boxUnits: Int,
        g: Geometry,
        textAlign: TextAlign,
        placeholders: List<AnnotatedString.Range<Placeholder>>,
    ): ParagraphIntrinsics {
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
        return ParagraphIntrinsics(
            text = plain,
            style = style,
            spanStyles = spans,
            placeholders = placeholders,
            density = UNIT_DENSITY,
            fontFamilyResolver = resolver,
        )
    }

    private fun gridText(
        intrinsics: ParagraphIntrinsics,
        plain: String,
        boxUnits: Int,
        g: Geometry,
        widthPt: Float,
    ): GridText {
        val lineHeightPx = (boxUnits * g.pitchPx).toFloat()
        val paragraph = Paragraph(intrinsics, Constraints(maxWidth = max(1, ceil(widthPt * g.refScale).toInt())))
        return GridText(paragraph, plain, g.refScale, lineHeightPx - g.liftPx - paragraph.getLineBaseline(0))
    }

    private fun spanStyles(
        inline: InlineText,
        offset: Int,
        roleCap: Float,
        g: Geometry,
        style: FlowTextStyle,
        family: TextFontFamily,
        formulas: List<InlineFormula>,
    ): MutableList<AnnotatedString.Range<SpanStyle>> {
        val out = ArrayList<AnnotatedString.Range<SpanStyle>>(inline.spans.size + 2)
        if (inline.spans.isEmpty()) return out
        val length = inline.plain.length
        // Inline code (and invalid LaTeX): the monospace family at the code-to-body cap ratio of the surrounding role.
        val mono by lazy { monoStyle(family, roleCap, g) }
        val code by lazy { mono.copy(background = style.palette.codeBackground) }
        val invalidMath by lazy { mono.copy(color = style.palette.mathError) }
        for (span in inline.spans) {
            val start = span.plainStart.coerceIn(0, length)
            val end = span.plainEnd.coerceIn(start, length)
            val spanStyle =
                when (span.kind) {
                    SpanKind.CODE -> {
                        code
                    }

                    SpanKind.MATH -> {
                        mathStyle(formulas.firstOrNull { it.span === span }) { invalidMath }
                    }

                    SpanKind.DOC_LINK -> {
                        if (span.docId in
                            style.brokenLinks
                        ) {
                            brokenLink(style.palette)
                        } else {
                            markStyle(span.kind, style.palette)
                        }
                    }

                    else -> {
                        markStyle(span.kind, style.palette)
                    }
                }
            if (start < end && spanStyle != null) out += AnnotatedString.Range(spanStyle, start + offset, end + offset)
        }
        return out
    }

    // Inline code: the monospace family at the code-to-body cap ratio of the surrounding role ([roleCap]).
    private fun monoStyle(
        family: TextFontFamily,
        roleCap: Float,
        g: Geometry,
    ): SpanStyle {
        val mono = registry.codeFamily(family.name)
        val cap = roleCap * TextRole.CODE.capFractionAtM / TextRole.BODY.capFractionAtM
        val sizePt = TextSizing.fontSizePt(metrics.proportions(mono).capRatio, cap, g.unitPt)
        return SpanStyle(fontFamily = mono.fontFamily, fontSize = (sizePt * g.refScale).sp)
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
        private const val CELL_PAD_UNITS = 0.4f

        // Block math (07-text-engine.md#math): 1.15 x body size, fit to the width down to 0.6, half a line of air around it.
        private const val BLOCK_MATH_SIZE = 1.15f
        private const val MIN_BLOCK_SCALE = 0.6f
        private const val BLOCK_MATH_PAD_UNITS = 0.5f

        /** 1 sp = 1 px so styles are given in layout px. */
        private val UNIT_DENSITY = Density(density = 1f, fontScale = 1f)

        /** One body line box without text; a [rule] is drawn at its center. */
        private fun empty(
            g: Geometry,
            style: FlowTextStyle,
            rule: Boolean,
        ): BlockLayout {
            val units = TextSizing.boxUnits(TextRole.BODY, TextSizing.capFraction(TextRole.BODY, style.bodyCap))
            val decoration = if (rule) BlockDecoration.Rule(yPt = units * g.unitPt / 2f) else BlockDecoration.None
            return BlockLayout(null, null, g.unitPt, units, 1, 0f, 0f, 0f, decoration, liftPt = g.liftPt)
        }

        /** Math span style: italic source without a renderer, none where a placeholder takes the characters, [invalid] on error. */
        private inline fun mathStyle(
            formula: InlineFormula?,
            invalid: () -> SpanStyle,
        ): SpanStyle? =
            when {
                formula == null -> SpanStyle(fontStyle = FontStyle.Italic)
                formula.layout != null -> null
                else -> invalid()
            }

        /** [style] with only the broken links that [content] links to: blocks without them share one cache entry. */
        private fun styleFor(
            content: BlockContent,
            style: FlowTextStyle,
        ): FlowTextStyle {
            // The text color is applied when painting, so boxes in different colors share their layouts.
            val plain = if (style.textArgb == null) style else style.copy(textArgb = null)
            if (plain.brokenLinks.isEmpty()) return plain
            val linked = content.linkedDocIds()
            val broken = if (linked.isEmpty()) emptySet() else linked.filterTo(HashSet()) { it in plain.brokenLinks }
            return plain.copy(brokenLinks = broken)
        }

        /** A note link to a missing document: muted and struck through. */
        private fun brokenLink(palette: TextPalette): SpanStyle =
            SpanStyle(color = palette.brokenLink, textDecoration = TextDecoration.LineThrough)

        /** Style of the Markdown marks (bold, italic, strike, highlight, links); null for code and math. */
        private fun markStyle(
            kind: SpanKind,
            palette: TextPalette,
        ): SpanStyle? =
            when (kind) {
                SpanKind.BOLD -> SpanStyle(fontWeight = FontWeight.Bold)
                SpanKind.ITALIC -> SpanStyle(fontStyle = FontStyle.Italic)
                SpanKind.STRIKE -> SpanStyle(textDecoration = TextDecoration.LineThrough)
                SpanKind.HIGHLIGHT -> SpanStyle(background = palette.highlight)
                SpanKind.LINK, SpanKind.DOC_LINK -> SpanStyle(color = palette.link, textDecoration = TextDecoration.Underline)
                SpanKind.CODE, SpanKind.MATH -> null
            }

        private fun headingRole(level: Int): TextRole =
            when (level) {
                1 -> TextRole.H1
                2 -> TextRole.H2
                else -> TextRole.H3
            }

        private fun calloutTitle(type: CalloutType): String = type.name.lowercase().replaceFirstChar { it.uppercase() }
    }
}

// The text alignment of a block in a flow aligned [alignment]; [justify] false for blocks that read JUSTIFIED as start.
private fun align(
    alignment: FlowAlignment,
    justify: Boolean,
): TextAlign =
    when (alignment) {
        FlowAlignment.START -> TextAlign.Start
        FlowAlignment.CENTER -> TextAlign.Center
        FlowAlignment.END -> TextAlign.End
        FlowAlignment.JUSTIFIED -> if (justify) TextAlign.Justify else TextAlign.Start
    }

private fun cellAlign(align: TableAlign): TextAlign =
    when (align) {
        TableAlign.NONE, TableAlign.LEFT -> TextAlign.Start
        TableAlign.CENTER -> TextAlign.Center
        TableAlign.RIGHT -> TextAlign.End
    }

// How far a list item's label ([hasMarker]) or checkbox moves right to stay just before its first line of [text]:
// that line's indent when [textAlign] centers or end-aligns it (P07-T16), else 0.
private fun labelShiftPt(
    text: GridText,
    textAlign: TextAlign,
    hasMarker: Boolean,
    decoration: BlockDecoration,
): Float {
    val labelled = hasMarker || decoration is BlockDecoration.Checkbox
    val indented = textAlign == TextAlign.Center || textAlign == TextAlign.End
    return if (labelled && indented) text.xPt(0) else 0f
}

// A checkbox moved right by [dxPt]; other decorations stay.
private fun BlockDecoration.shiftedBy(dxPt: Float): BlockDecoration =
    if (this is BlockDecoration.Checkbox && dxPt != 0f) copy(leftPt = leftPt + dxPt) else this
