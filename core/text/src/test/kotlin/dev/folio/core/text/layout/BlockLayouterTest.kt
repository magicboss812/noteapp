package dev.folio.core.text.layout

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.GridUnit
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownBlock
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test
import org.junit.runner.RunWith

/** Line boxes, indents, decorations and the cache of [BlockLayouter] (07-text-engine.md#line-box, #blocks). */
@RunWith(AndroidJUnit4::class)
class BlockLayouterTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val metrics = FontMetricsCache(resolver)
    private val layouter = BlockLayouter(resolver, metrics)
    private val lined = LayoutGrid.of(GridUnit(GridUnit.COLLEGE_UNIT_PT, 0f, 36f, snapsColumns = false))
    private val graph = LayoutGrid.of(GridUnit(GridUnit.GRAPH_UNIT_PT, 0f, 36f, snapsColumns = true))
    private val style = FlowTextStyle()

    private fun block(markdown: String): MarkdownBlock = MarkdownDocument.parse(markdown).blocks.single()

    private fun layout(
        markdown: String,
        grid: LayoutGrid = lined,
        flow: FlowTextStyle = style,
        widthPt: Float = WIDTH_PT,
    ): BlockLayout = layouter.build(block(markdown).content, widthPt, flow, grid)

    @Test
    fun headings_h1H2TwoUnitBoxes_h3OneUnit() {
        assertThat(layout("# Title").boxUnits).isEqualTo(2)
        assertThat(layout("## Section").boxUnits).isEqualTo(2)
        assertThat(layout("### Sub").boxUnits).isEqualTo(1)
        assertThat(layout("#### Deeper").boxUnits).isEqualTo(1)
    }

    @Test
    fun paragraph_wrapsIntoWholeLineBoxes_baselinesOnRules() {
        val unit = lined.unitPt
        val result = layout("word ".repeat(80).trim())

        assertThat(result.lineCount).isAtLeast(3)
        assertThat(result.heightPt).isEqualTo(result.lineCount * unit)
        for (line in 0 until result.lineCount) {
            assertWithMessage("line $line").that(result.baselinePt(line)).isWithin(TOLERANCE_PT).of((line + 1) * unit)
            assertThat(result.lineTopPt(line)).isEqualTo(line * unit)
        }
    }

    // Left edge (from the text column start) and width of the one line of [markdown] laid out [alignment].
    private fun oneLine(
        markdown: String,
        alignment: FlowAlignment,
    ): Pair<Float, Float> {
        val text = checkNotNull(layout(markdown, flow = style.copy(alignment = alignment)).text)
        assertThat(text.lineCount).isEqualTo(1)
        return text.xPt(0) to text.maxLineWidthPt()
    }

    @Test
    fun alignment_lineXOffsets_startCenterEndJustified() {
        val (startLeft, width) = oneLine("Short line", FlowAlignment.START)
        val (centerLeft, _) = oneLine("Short line", FlowAlignment.CENTER)
        val (endLeft, _) = oneLine("Short line", FlowAlignment.END)
        val (justifiedLeft, _) = oneLine("Short line", FlowAlignment.JUSTIFIED)

        assertThat(startLeft).isWithin(TOLERANCE_PT).of(0f)
        assertThat(centerLeft).isWithin(ALIGN_TOLERANCE_PT).of((WIDTH_PT - width) / 2f)
        assertThat(endLeft).isWithin(ALIGN_TOLERANCE_PT).of(WIDTH_PT - width)
        // A justified paragraph's last line (here its only one) stays at the start.
        assertThat(justifiedLeft).isWithin(TOLERANCE_PT).of(0f)
    }

    @Test
    fun justified_everyLineButTheLastSpansTheColumn_lastStaysStart() {
        val result = layout("word ".repeat(40).trim(), flow = style.copy(alignment = FlowAlignment.JUSTIFIED))
        val text = checkNotNull(result.text)
        val last = text.lineCount - 1

        assertThat(last).isAtLeast(2)
        val start = checkNotNull(layout("word ".repeat(40).trim()).text)
        for (line in 0 until last) {
            assertWithMessage("line $line").that(text.xPt(text.lineStart(line))).isWithin(TOLERANCE_PT).of(0f)
            assertWithMessage("line $line").that(text.lineRightPt(line)).isWithin(JUSTIFY_TOLERANCE_PT).of(WIDTH_PT)
            assertWithMessage("line $line").that(start.lineRightPt(line)).isLessThan(WIDTH_PT - JUSTIFY_TOLERANCE_PT)
        }
        assertThat(text.xPt(text.lineStart(last))).isWithin(TOLERANCE_PT).of(0f)
        assertThat(text.lineRightPt(last)).isLessThan(WIDTH_PT - JUSTIFY_TOLERANCE_PT)
    }

    @Test
    fun alignment_listItemsAndQuotesTakeIt_headingsJustifyAsStart_codeStaysStart() {
        val center = style.copy(alignment = FlowAlignment.CENTER)
        val justified = style.copy(alignment = FlowAlignment.JUSTIFIED)
        for (markdown in listOf("- Item", "1. Item", "- [ ] Task", "> Quote")) {
            val result = layout(markdown, flow = center)
            val text = checkNotNull(result.text)
            val column = WIDTH_PT - result.textLeftPt
            assertWithMessage(markdown).that(text.xPt(0)).isWithin(ALIGN_TOLERANCE_PT).of((column - text.maxLineWidthPt()) / 2f)
        }
        val heading = checkNotNull(layout("## Heading", flow = center).text)
        assertThat(heading.xPt(0)).isWithin(ALIGN_TOLERANCE_PT).of((WIDTH_PT - heading.maxLineWidthPt()) / 2f)
        assertThat(checkNotNull(layout("## Heading", flow = justified).text).xPt(0)).isWithin(TOLERANCE_PT).of(0f)
        assertThat(checkNotNull(layout("```\ncode\n```", flow = center).text).xPt(0)).isWithin(TOLERANCE_PT).of(0f)
    }

    @Test
    fun freeSize_largeBodyCap_growsTheBoxInWholeUnits() {
        val result = layout("Large text", flow = style.copy(bodyCap = 1.2f))

        assertThat(result.boxUnits).isEqualTo(2)
        assertThat(result.baselinePt(0)).isWithin(TOLERANCE_PT).of(2 * lined.unitPt)
    }

    @Test
    fun baselineLift_raisesTextCheckboxAndEmptyLines_boxesUnchanged() {
        val lift = 0.25f
        val liftPt = lift * lined.unitPt
        val lifted = style.copy(baselineLiftUnits = lift)
        for (markdown in listOf("word ".repeat(80).trim(), "# Title", "- [ ] Task", "| a |\n| - |\n| 1 |")) {
            val flat = layout(markdown)
            val raised = layout(markdown, flow = lifted)
            assertWithMessage(markdown).that(raised.heightUnits).isEqualTo(flat.heightUnits)
            assertWithMessage(markdown).that(raised.boxUnits).isEqualTo(flat.boxUnits)
            for (line in 0 until raised.lineCount) {
                assertWithMessage(
                    "$markdown line $line",
                ).that(raised.baselinePt(line)).isWithin(TOLERANCE_PT).of(flat.baselinePt(line) - liftPt)
            }
        }
        val box = layout("- [ ] Task", flow = lifted).decoration as BlockDecoration.Checkbox
        assertThat(box.topPt).isWithin(TOLERANCE_PT).of((layout("- [ ] Task").decoration as BlockDecoration.Checkbox).topPt - liftPt)
        val cell = checkNotNull(layout("| a |\n| - |\n| 1 |", flow = lifted).table)
        assertThat(cell.cellBaselinePt(0, 0)).isWithin(TOLERANCE_PT).of(lined.unitPt - liftPt)
        assertThat(
            layouter.build(BlockContent.Empty, WIDTH_PT, lifted, lined).baselinePt(0),
        ).isWithin(TOLERANCE_PT).of(lined.unitPt - liftPt)
    }

    @Test
    fun emptyAndRule_oneBodyLine_ruleAtTheBoxCenter() {
        val empty = layouter.build(BlockContent.Empty, WIDTH_PT, style, lined)
        val rule = layout("---")

        assertThat(empty.lineCount).isEqualTo(1)
        assertThat(empty.heightPt).isEqualTo(lined.unitPt)
        assertThat(empty.decoration).isEqualTo(BlockDecoration.None)
        assertThat(rule.decoration).isEqualTo(BlockDecoration.Rule(yPt = lined.unitPt / 2f))
    }

    @Test
    fun listItems_indentByLevelInGridColumns_markerInTheGutter() {
        val nested = layout("    - third level", grid = graph)
        val ordered = layout("1. first")

        assertThat(nested.textLeftPt).isEqualTo(3 * graph.unitPt)
        assertThat(nested.markerLeftPt).isIn(Range.open(2 * graph.unitPt, 3 * graph.unitPt))
        assertThat(nested.hasMarker).isTrue()
        assertThat(nested.markerBaselinePt()).isWithin(TOLERANCE_PT).of(graph.unitPt)
        assertThat(ordered.textLeftPt).isEqualTo(LayoutGrid.LINED_INDENT_PT)
        assertThat(ordered.markerBaselinePt()).isWithin(TOLERANCE_PT).of(lined.unitPt)
    }

    @Test
    fun taskItem_checkboxSquareOnTheFirstBaseline_noMarker() {
        val result = layout("- [x] done")
        val box = result.decoration as BlockDecoration.Checkbox

        assertThat(result.hasMarker).isFalse()
        assertThat(box.checked).isTrue()
        assertThat(box.sizePt).isWithin(TOLERANCE_PT).of(0.7f * lined.unitPt)
        assertThat(box.topPt + box.sizePt).isWithin(TOLERANCE_PT).of(lined.unitPt)
    }

    @Test
    fun quoteCalloutAndCode_insetsDecorationsAndTitleLine() {
        val quote = layout("> quoted")
        val callout = layout("> [!WARNING]\n> careful")
        val code = layout("```\nval x = 1\nval y = 2\n```")

        assertThat(quote.textLeftPt).isEqualTo(LayoutGrid.LINED_INDENT_PT)
        assertThat(quote.decoration).isInstanceOf(BlockDecoration.QuoteBar::class.java)
        assertThat(quote.lineCount).isEqualTo(1)
        assertThat(callout.lineCount).isEqualTo(2) // title line + body
        assertThat(code.decoration).isEqualTo(BlockDecoration.CodeBackground)
        assertThat(code.textLeftPt).isEqualTo(0.5f * lined.unitPt)
        assertThat(code.lineCount).isEqualTo(2)
    }

    @Test
    fun orderedLabel_perLevelNumbersLettersRoman() {
        assertThat(ListLabels.ordered(3, 0)).isEqualTo("3.")
        assertThat(ListLabels.ordered(2, 1)).isEqualTo("b.")
        assertThat(ListLabels.ordered(28, 1)).isEqualTo("ab.")
        assertThat(ListLabels.ordered(4, 2)).isEqualTo("iv.")
        assertThat(ListLabels.ordered(9, 5)).isEqualTo("ix.")
        assertThat(ListLabels.bullet(4)).isEqualTo("◦")
    }

    @Test
    fun layout_brokenLinksElsewhere_keepTheCachedInstance() {
        val plain = block("No links here")
        val linked = block("See [a](folio://doc/aaa) and [b](folio://doc/bbb#page=p1)")
        val firstPlain = layouter.layout(plain, WIDTH_PT, style, lined)
        val firstLinked = layouter.layout(linked, WIDTH_PT, style, lined)

        assertThat(layouter.layout(plain, WIDTH_PT, style.copy(brokenLinks = setOf("aaa")), lined)).isSameInstanceAs(firstPlain)
        assertThat(layouter.layout(linked, WIDTH_PT, style.copy(brokenLinks = setOf("zzz")), lined)).isSameInstanceAs(firstLinked)
        val broken = layouter.layout(linked, WIDTH_PT, style.copy(brokenLinks = setOf("bbb", "zzz")), lined)
        assertThat(broken).isNotSameInstanceAs(firstLinked)
        assertThat(layouter.layout(linked, WIDTH_PT, style.copy(brokenLinks = setOf("bbb")), lined)).isSameInstanceAs(broken)
    }

    @Test
    fun layout_newFontRegistry_dropsCachedLayouts() {
        var registry = FontRegistry.Bundled
        val layouter = BlockLayouter(resolver, metrics, fonts = { registry })
        val paragraph = block("Some cached text")
        val first = layouter.layout(paragraph, WIDTH_PT, style, lined)

        assertThat(layouter.layout(paragraph, WIDTH_PT, style, lined)).isSameInstanceAs(first)
        registry = FontRegistry()
        assertThat(layouter.layout(paragraph, WIDTH_PT, style, lined)).isNotSameInstanceAs(first)
        assertThat(layouter.cachedCount).isEqualTo(1)
    }

    @Test
    fun layout_sameBlockAndSettings_returnsCachedInstance() {
        val paragraph = block("Some cached text")

        val first = layouter.layout(paragraph, WIDTH_PT, style, lined)

        assertThat(layouter.layout(paragraph.copy(id = 99), WIDTH_PT, style, lined)).isSameInstanceAs(first)
        assertThat(layouter.layout(paragraph, WIDTH_PT / 2, style, lined)).isNotSameInstanceAs(first)
        assertThat(layouter.layout(paragraph, WIDTH_PT, style.copy(bodyCap = 0.52f), lined)).isNotSameInstanceAs(first)
        assertThat(layouter.layout(paragraph, WIDTH_PT, style, graph)).isNotSameInstanceAs(first)
        assertThat(layouter.cachedCount).isEqualTo(4)
    }

    @Test
    fun layout_overCapacity_evictsLeastRecentlyUsed() {
        val small = BlockLayouter(resolver, metrics, maxCached = 2)
        val a = block("alpha")
        val first = small.layout(a, WIDTH_PT, style, lined)
        small.layout(block("beta"), WIDTH_PT, style, lined)
        small.layout(a, WIDTH_PT, style, lined) // a is now the most recent
        small.layout(block("gamma"), WIDTH_PT, style, lined) // evicts beta

        assertThat(small.cachedCount).isEqualTo(2)
        assertThat(small.layout(a, WIDTH_PT, style, lined)).isSameInstanceAs(first)
        small.clear()
        assertThat(small.cachedCount).isEqualTo(0)
    }

    private companion object {
        const val WIDTH_PT = 400f

        /** 0.5 px at zoom 4 on the Pad 7 is about 0.024 pt. */
        const val TOLERANCE_PT = 1e-3f

        /** Centered and end-aligned lines are placed in whole layout px (about 0.25 pt). */
        const val ALIGN_TOLERANCE_PT = 0.5f

        /** Justified lines end on the border within a glyph's side bearing. */
        const val JUSTIFY_TOLERANCE_PT = 1.5f
    }
}
