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
    fun movedBox_grid_topOnNearestRule_xUnsnapped_sizeKept() {
        val u = grid.spacingPt
        val box = RectPt(0f, 0f, 30f * u / 5f, 2f * u)
        val moved = TextZones.movedBox(a4, grid, box, 10.6f * u, 20.4f * u)
        assertThat(moved.left).isWithin(1e-3f).of(10.6f * u)
        assertThat(moved.top).isWithin(1e-3f).of(20f * u)
        assertThat(moved.widthPt).isWithin(1e-3f).of(box.widthPt)
        assertThat(moved.heightPt).isWithin(1e-3f).of(box.heightPt)
    }

    @Test
    fun movedBox_lined_topInWholeUnitsOnARule_leftWhereDragged() {
        val u = lined.spacingPt
        val box = RectPt(0f, 0f, 100f, u)
        val moved = TextZones.movedBox(a4, lined, box, 40.4f * PT_PER_MM, lined.marginTopPt + 3.3f * u)
        assertThat(moved.left).isWithin(1e-3f).of(40.4f * PT_PER_MM)
        assertThat(onGrid(moved.top, lined.marginTopPt, u)).isTrue()
        assertThat(moved.top).isWithin(1e-3f).of(lined.marginTopPt + 3f * u)
        val down = TextZones.movedBox(a4, lined, box, 40.4f * PT_PER_MM, lined.marginTopPt + 3.6f * u)
        assertThat(down.top).isWithin(1e-3f).of(lined.marginTopPt + 4f * u)
    }

    @Test
    fun movedBox_pastTheZoneEdges_staysInsideTheTextZone_firstLineOnARule() {
        val u = grid.spacingPt
        val zone = TextZones.boxZone(a4, grid)
        val box = RectPt(0f, 0f, 100f, 3f * u)
        val moved = TextZones.movedBox(a4, grid, box, a4.widthPt, a4.heightPt + 50f)
        assertThat(moved.right).isWithin(1e-3f).of(zone.right)
        assertThat(moved.top + u).isAtMost(zone.bottom + 1e-3f)
        assertThat(onGrid(moved.top, 0f, u)).isTrue()
        val negative = TextZones.movedBox(a4, grid, box, -40f, -40f)
        assertThat(negative.left).isWithin(1e-3f).of(zone.left)
        assertThat(negative.top).isAtLeast(zone.top - 1e-3f)
        assertThat(onGrid(negative.top, 0f, u)).isTrue()
        val wide = TextZones.movedBox(a4, grid, RectPt(0f, 0f, a4.widthPt, u), 30f, 10f * u)
        assertThat(wide.left).isWithin(1e-3f).of(zone.left)
    }

    @Test
    fun keyedBox_keepsTheTopBetweenTheLines_insideTheZone() {
        val u = lined.spacingPt
        val zone = TextZones.boxZone(a4, lined)
        val box = RectPt(0f, 0f, 100f, u)
        val shifted = TextZones.keyedBox(a4, lined, box, 120f, lined.marginTopPt + 3.4f * u)
        assertThat(shifted.top).isWithin(1e-3f).of(lined.marginTopPt + 3.4f * u)
        assertThat(shifted.left).isWithin(1e-3f).of(120f)
        assertThat(shifted.heightPt).isWithin(1e-3f).of(u)
        val low = TextZones.keyedBox(a4, lined, box, 120f, a4.heightPt)
        assertThat(onGrid(low.top, lined.marginTopPt, u)).isTrue()
        assertThat(low.top + u).isAtMost(zone.bottom + 1e-3f)
        val high = TextZones.keyedBox(a4, lined, box, -50f, -50f)
        assertThat(high.top).isAtLeast(zone.top - 1e-3f)
        assertThat(high.left).isWithin(1e-3f).of(zone.left)
    }

    @Test
    fun lineStepTop_onARule_movesOneLine_betweenLines_snapsToTheNearestLineThatWay() {
        val u = lined.spacingPt
        val rule = lined.marginTopPt + 5f * u
        assertThat(TextZones.lineStepTop(lined, rule, down = true)).isWithin(1e-3f).of(rule + u)
        assertThat(TextZones.lineStepTop(lined, rule, down = false)).isWithin(1e-3f).of(rule - u)
        // Shift+Down left the box 0.3 U below the rule: Up snaps back onto it, Down onto the next one.
        val shifted = rule + 0.3f * u
        assertThat(TextZones.lineStepTop(lined, shifted, down = false)).isWithin(1e-3f).of(rule)
        assertThat(TextZones.lineStepTop(lined, shifted, down = true)).isWithin(1e-3f).of(rule + u)
        // Shift+Up left it 0.2 U above the rule: Down snaps back onto it.
        assertThat(TextZones.lineStepTop(lined, rule - 0.2f * u, down = true)).isWithin(1e-3f).of(rule)
    }

    @Test
    fun offsetOf_isTheDistanceFromTheNearestRule_zeroOnARule() {
        val u = lined.spacingPt
        val rule = lined.marginTopPt + 5f * u
        assertThat(TextZones.offsetOf(lined, rule)).isEqualTo(0f)
        assertThat(TextZones.offsetOf(lined, rule + 0.3f * u)).isWithin(1e-3f).of(0.3f * u)
        assertThat(TextZones.offsetOf(lined, rule - 0.2f * u)).isWithin(1e-3f).of(-0.2f * u)
    }

    @Test
    fun resizedBox_grid_movedEdgeOnColumn_otherEdgeKept_minWidth() {
        val u = grid.spacingPt
        val box = RectPt(10f * u, 4f * u, 30f * u, 5f * u)
        val wider = TextZones.resizedBox(a4, grid, box, box.left, 33.4f * u)
        assertThat(wider.left).isEqualTo(box.left)
        assertThat(wider.right).isWithin(1e-3f).of(33f * u)
        assertThat(wider.top).isEqualTo(box.top)
        val narrowed = TextZones.resizedBox(a4, grid, box, 29f * u, box.right)
        assertThat(narrowed.right).isWithin(1e-3f).of(box.right)
        assertThat(narrowed.widthPt).isAtLeast(TextZones.BOX_MIN_WIDTH_PT - 1e-3f)
        assertThat(onGrid(narrowed.left, 0f, u)).isTrue()
        val clamped = TextZones.resizedBox(a4, grid, box, box.left, a4.widthPt + 80f)
        assertThat(clamped.right).isAtMost(a4.widthPt + 1e-3f)
        assertThat(onGrid(clamped.right, 0f, u)).isTrue()
    }

    @Test
    fun resizedBox_lined_snapsToMillimeters() {
        val box = RectPt(40f * PT_PER_MM, 100f, 120f * PT_PER_MM, 120f)
        val resized = TextZones.resizedBox(a4, lined, box, 35.3f * PT_PER_MM, box.right)
        assertThat(resized.left).isWithin(1e-3f).of(35f * PT_PER_MM)
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

    @Test
    fun newBoxAt_midPage_onTheLineHoldingTheTap_firstGlyphAtTheTap_caretWide() {
        val u = lined.spacingPt
        val tapY = lined.marginTopPt + 10.4f * u
        val rect = TextZones.newBoxAt(a4, lined, 250f, tapY, 0f, TextAlign.START)

        assertThat(rect.top).isWithin(1e-3f).of(lined.marginTopPt + 10f * u)
        assertThat(rect.heightPt).isWithin(1e-3f).of(u)
        assertThat(rect.left).isEqualTo(250f)
        assertThat(rect.widthPt).isWithin(1e-3f).of(TextZones.AUTO_MIN_WIDTH_PT)
        assertThat(TextZones.anchorOf(rect, TextAlign.START)).isEqualTo(250f)
    }

    @Test
    fun newBoxAt_withLift_tapOnTheRaisedText_takesTheLineBelow() {
        val u = lined.spacingPt
        // Text with a 0.3 U lift sits in the top part of the line above its rule: a tap 0.8 U into line 10 is on line 11.
        val rect = TextZones.newBoxAt(a4, lined, 250f, lined.marginTopPt + 10.8f * u, 0.3f, TextAlign.START)

        assertThat(rect.top).isWithin(1e-3f).of(lined.marginTopPt + 11f * u)
    }

    @Test
    fun newBoxAt_outsideTheZone_keptInside() {
        val zone = TextZones.boxZone(a4, lined)
        val low = TextZones.newBoxAt(a4, lined, a4.widthPt - 1f, a4.heightPt - 2f, 0f, TextAlign.START)
        val high = TextZones.newBoxAt(a4, lined, 2f, 3f, 0f, TextAlign.END)

        assertThat(low.right).isAtMost(zone.right + 1e-3f)
        assertThat(low.bottom).isWithin(1e-3f).of(zone.bottom)
        assertThat(high.left).isWithin(1e-3f).of(zone.left)
        assertThat(high.top).isWithin(1e-3f).of(zone.top)
    }

    @Test
    fun autoBorders_growOnTheAlignmentSide_stopAtTheZone() {
        val zone = RectPt(50f, 0f, 550f, 700f)

        assertThat(TextZones.autoBorders(zone, 100f, TextAlign.START, 120f)).isEqualTo(Borders(100f, 220f))
        assertThat(TextZones.autoBorders(zone, 500f, TextAlign.START, 120f)).isEqualTo(Borders(500f, 550f))
        assertThat(TextZones.autoBorders(zone, 300f, TextAlign.END, 120f)).isEqualTo(Borders(180f, 300f))
        assertThat(TextZones.autoBorders(zone, 100f, TextAlign.END, 120f)).isEqualTo(Borders(50f, 100f))
        assertThat(TextZones.autoBorders(zone, 300f, TextAlign.CENTER, 120f)).isEqualTo(Borders(240f, 360f))
        assertThat(TextZones.autoBorders(zone, 500f, TextAlign.CENTER, 400f)).isEqualTo(Borders(450f, 550f))
        assertThat(TextZones.autoBorders(zone, 100f, TextAlign.START, 0f).widthPt).isEqualTo(TextZones.AUTO_MIN_WIDTH_PT)
        assertThat(TextZones.autoBorders(zone, 100f, TextAlign.START, Float.POSITIVE_INFINITY)).isEqualTo(Borders(100f, 550f))
    }

    @Test
    fun boxZone_structuredTemplate_isThePageInsideMargins_linedIsTheBody() {
        val cornell = lined.copy(kind = TemplateKind.CORNELL)
        val zone = TextZones.boxZone(a4, cornell)

        assertThat(zone.left).isWithin(1e-3f).of(12f * PT_PER_MM)
        assertThat(zone.right).isWithin(1e-3f).of(a4.widthPt - 12f * PT_PER_MM)
        assertThat(onGrid(zone.top, lined.marginTopPt, lined.spacingPt)).isTrue()
        assertThat(TextZones.boxZone(a4, lined)).isEqualTo(TextZones.bodyRect(a4, lined))
    }

    @Test
    fun boxOutline_paddedHalfUnit_atLeastOneLine() {
        val rect = RectPt(100f, 200f, 180f, 220f)

        assertThat(TextZones.boxOutline(rect, 0, 20f)).isEqualTo(RectPt(90f, 200f, 190f, 220f))
        assertThat(TextZones.boxOutline(rect, 3, 20f)).isEqualTo(RectPt(90f, 200f, 190f, 260f))
    }
}
