package dev.folio.core.model

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.GridUnit.Companion.PT_PER_MM
import dev.folio.core.model.geometry.RectPt
import org.junit.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class TextZonesTest {
    private val a4 = PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT)
    private val lined = Template(TemplateKind.LINED, 7.1f * PT_PER_MM, 0, 25f * PT_PER_MM, 25f * PT_PER_MM, null, null)
    private val grid = Template(TemplateKind.GRID, 5f * PT_PER_MM, 0, 0f, 0f, null, null)

    private fun onGrid(
        valuePt: Float,
        originPt: Float,
        unitPt: Float,
    ): Boolean {
        val n = (valuePt - originPt) / unitPt
        return abs(n - n.roundToInt()) < 1e-3f
    }

    @Test
    fun bodyRect_lined_marginLinePlusGap_topOnOrigin_bottomOnLastRuleAboveMargin() {
        val rect = TextZones.bodyRect(a4, lined)!!
        val u = lined.spacingPt
        assertThat(rect.left).isWithin(1e-3f).of(27f * PT_PER_MM)
        assertThat(rect.right).isWithin(1e-3f).of(a4.widthPt - 12f * PT_PER_MM)
        assertThat(rect.top).isEqualTo(lined.marginTopPt)
        assertThat(onGrid(rect.bottom, lined.marginTopPt, u)).isTrue()
        assertThat(rect.bottom).isAtMost(a4.heightPt - 12f * PT_PER_MM)
        assertThat(rect.bottom + u).isGreaterThan(a4.heightPt - 12f * PT_PER_MM)
        assertThat(TextZones.capacityUnits(rect.heightPt, u)).isEqualTo(36)
    }

    @Test
    fun bodyRect_lineless_usesTwelveMillimeters() {
        val blank = lined.copy(kind = TemplateKind.BLANK, marginLeftPt = 0f)
        assertThat(TextZones.bodyRect(a4, blank)!!.left).isWithin(1e-3f).of(12f * PT_PER_MM)
    }

    @Test
    fun bodyRect_grid_marginsSnapInwardToColumnsAndRules() {
        val rect = TextZones.bodyRect(a4, grid)!!
        val u = grid.spacingPt
        listOf(rect.left, rect.top, rect.right, rect.bottom).forEach { assertThat(onGrid(it, 0f, u)).isTrue() }
        assertThat(rect.left).isAtLeast(12f * PT_PER_MM - 1e-3f)
        assertThat(rect.left - u).isLessThan(12f * PT_PER_MM)
        assertThat(rect.right).isAtMost(a4.widthPt - 12f * PT_PER_MM + 1e-3f)
        assertThat(rect.top).isAtLeast(12f * PT_PER_MM - 1e-3f)
    }

    @Test
    fun bodyRect_cornell_isTheNotesColumnAboveTheSummary() {
        val cornell = lined.copy(kind = TemplateKind.CORNELL, marginLeftPt = 0f)
        val rect = TextZones.bodyRect(a4, cornell)!!
        assertThat(rect.left).isWithin(1e-3f).of(65.5f * PT_PER_MM)
        assertThat(rect.top).isEqualTo(cornell.marginTopPt)
        assertThat(rect.bottom).isAtMost(a4.heightPt - 50.8f * PT_PER_MM)
        assertThat(onGrid(rect.bottom, cornell.marginTopPt, GridUnit.COLLEGE_UNIT_PT)).isTrue()
    }

    @Test
    fun bodyRect_planners_areTheNotesAreas() {
        val daily = lined.copy(kind = TemplateKind.PLANNER_DAILY, marginLeftPt = 12f * PT_PER_MM)
        val dailyRect = TextZones.bodyRect(a4, daily)!!
        assertThat(dailyRect.left).isGreaterThan(a4.widthPt / 2f)
        val weekly = daily.copy(kind = TemplateKind.PLANNER_WEEKLY)
        val weeklyRect = TextZones.bodyRect(a4, weekly)!!
        assertThat(weeklyRect.left).isGreaterThan(a4.widthPt / 2f)
        assertThat(weeklyRect.top).isGreaterThan(a4.heightPt / 2f)
        assertThat(onGrid(weeklyRect.top, weekly.marginTopPt, GridUnit.COLLEGE_UNIT_PT)).isTrue()
    }

    @Test
    fun bodyRect_infinite_usesTheOriginFrame_tinyPage_isNull() {
        assertThat(TextZones.bodyRect(PageSpec.Infinite(a4), lined)).isEqualTo(TextZones.bodyRect(a4, lined))
        assertThat(TextZones.bodyRect(PageSpec.Custom(60f, 60f), lined)).isNull()
    }

    @Test
    fun newBoxRect_lined_topOnRuleAboveTap_freeLeft_sixtyPercentWide() {
        val rect = TextZones.newBoxRect(a4, lined, 200f, 300f)
        val body = TextZones.bodyRect(a4, lined)!!
        assertThat(rect.left).isEqualTo(200f)
        assertThat(rect.top).isAtMost(300f)
        assertThat(rect.top + lined.spacingPt).isGreaterThan(300f)
        assertThat(onGrid(rect.top, lined.marginTopPt, lined.spacingPt)).isTrue()
        assertThat(rect.widthPt).isWithin(1e-3f).of(body.widthPt * 0.6f)
        assertThat(rect.heightPt).isWithin(1e-3f).of(lined.spacingPt)
    }

    @Test
    fun newBoxRect_grid_snapsLeftToColumn_staysInsideThePage() {
        val snapped = TextZones.newBoxRect(a4, grid, 101f, 50f)
        assertThat(onGrid(snapped.left, 0f, grid.spacingPt)).isTrue()
        assertThat(snapped.left).isAtMost(101f)
        val edge = TextZones.newBoxRect(a4, grid, a4.widthPt - 5f, 50f)
        assertThat(edge.right).isAtMost(a4.widthPt)
        assertThat(onGrid(edge.left, 0f, grid.spacingPt)).isTrue()
    }

    @Test
    fun grownBox_wholeUnits_atLeastOne() {
        val rect = RectPt(10f, 100f, 200f, 120f)
        assertThat(TextZones.grownBox(rect, 3, 20f)).isEqualTo(RectPt(10f, 100f, 200f, 160f))
        assertThat(TextZones.grownBox(rect, 0, 20f).heightPt).isEqualTo(20f)
    }

    @Test
    fun stickyContentRect_insetHalfUnit_inStickyCoordinates() {
        val sticky = StickyNote(ObjectId("s"), FlowId("f"), RectPt(300f, 400f, 420f, 520f), 5f, 0)
        assertThat(TextZones.stickyContentRect(sticky, 20f)).isEqualTo(RectPt(10f, 10f, 110f, 110f))
    }
}
