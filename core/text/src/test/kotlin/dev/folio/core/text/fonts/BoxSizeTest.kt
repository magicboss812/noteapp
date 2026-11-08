package dev.folio.core.text.fonts

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.FlowStyle
import dev.folio.core.model.GridUnit
import dev.folio.core.model.TextAlign
import dev.folio.core.text.flow.toTextStyle
import dev.folio.core.text.layout.BlockLayout
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.layout.GridInvariantSuite
import dev.folio.core.text.layout.LayoutGrid
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** The box toolbar's Font size choices (P07-T17): the steps and the grid invariant at every size it can reach. */
@RunWith(AndroidJUnit4::class)
class BoxSizeTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = BlockLayouter(resolver, FontMetricsCache(resolver))

    @Test
    fun steppedBodyCap_movesOneStep_roundedAndKeptInTheFreeRange() {
        assertThat(TextSizing.steppedBodyCap(BodySize.M.capFraction, up = true)).isEqualTo(0.5f)
        assertThat(TextSizing.steppedBodyCap(BodySize.M.capFraction, up = false)).isEqualTo(0.4f)
        assertThat(TextSizing.steppedBodyCap(0.433f, up = true)).isEqualTo(0.48f)
        assertThat(TextSizing.steppedBodyCap(1.78f, up = true)).isEqualTo(TextSizing.MAX_BODY_CAP)
        assertThat(TextSizing.steppedBodyCap(0.32f, up = false)).isEqualTo(TextSizing.MIN_BODY_CAP)
        assertThat(TextSizing.steppedBodyCap(Float.NaN, up = true)).isEqualTo(0.5f)
    }

    @Test
    fun everyBundledFont_everyToolbarSize_lineBoxesWholeUnitsAndBaselinesOnTheRules() {
        val grid = LayoutGrid.of(GridUnit(GridUnit.COLLEGE_UNIT_PT, 0f, TOP_MARGIN_PT, snapsColumns = false))
        val blocks = MarkdownDocument.parse(CORPUS).blocks
        val violations = ArrayList<String>()
        var checked = 0
        for (family in FontRegistry.bundled) {
            for (size in toolbarSizes()) {
                val style = FlowStyle(family.name, size, 0, TextAlign.START).toTextStyle()
                for (block in blocks) {
                    val layout = layouter.build(block.content, GridInvariantSuite.WIDTH_PT, style, grid)
                    checked += check(layout, grid, "${family.name} size $size ${block.content::class.simpleName}", violations)
                }
            }
        }

        assertWithMessage(violations.take(REPORTED).joinToString("\n")).that(violations).isEmpty()
        assertThat(checked).isGreaterThan(FontRegistry.bundled.size * toolbarSizes().size * blocks.size)
    }

    // Adds the grid invariant violations of [layout] to [violations]; returns the number of lines checked.
    private fun check(
        layout: BlockLayout,
        grid: LayoutGrid,
        where: String,
        violations: MutableList<String>,
    ): Int {
        val maxErrorPt = GridInvariantSuite.MAX_ERROR_PX / (GridInvariantSuite.PAD7_FIT_SCALE * GridInvariantSuite.ZOOMS.last())
        if (layout.boxUnits < 1 || abs(layout.heightPt - layout.lineCount * layout.boxUnits * grid.unitPt) > EPSILON_PT) {
            violations += "$where: box ${layout.boxUnits}U, height ${layout.heightPt} for ${layout.lineCount} lines"
        }
        for (line in 0 until layout.lineCount) {
            val error = abs(layout.baselinePt(line) - (layout.lineTopPt(line + 1) - layout.liftPt))
            if (error > maxErrorPt) violations += "$where line $line: baseline $error pt off its rule"
        }
        return layout.lineCount
    }

    @Test
    fun flowColor_layoutIgnoresIt_sameCachedLayout() {
        val grid = LayoutGrid.of(GridUnit(GridUnit.COLLEGE_UNIT_PT, 0f, TOP_MARGIN_PT, snapsColumns = false))
        val block = MarkdownDocument.parse(CORPUS).blocks.last()
        val style = FlowStyle("Inter", BodySize.M.capFraction, 0, TextAlign.START).toTextStyle()

        val auto = layouter.layout(block, GridInvariantSuite.WIDTH_PT, style, grid)
        val blue = layouter.layout(block, GridInvariantSuite.WIDTH_PT, style.copy(textArgb = BLUE_ARGB), grid)

        assertThat(blue).isSameInstanceAs(auto)
        assertThat(FlowStyle("Inter", 0.45f, 0, TextAlign.START, textArgb = BLUE_ARGB).toTextStyle().textArgb).isEqualTo(BLUE_ARGB)
    }

    // S, M, L and every size the smaller and larger buttons reach from the smallest free size up.
    private fun toolbarSizes(): List<Float> {
        val steps = generateSequence(TextSizing.MIN_BODY_CAP) { TextSizing.steppedBodyCap(it, up = true).takeIf { next -> next > it } }
        return (BodySize.entries.map { it.capFraction } + steps).distinct()
    }

    private companion object {
        const val TOP_MARGIN_PT = 36f
        const val EPSILON_PT = 1e-3f
        const val REPORTED = 20
        const val BLUE_ARGB = 0xFF2563EB.toInt()
        const val CORPUS =
            "## A heading that scales with the box\n\n" +
                "A paragraph long enough to wrap onto more lines of the box, with **bold**, *italic* and descenders gjpqy."
    }
}
