package dev.folio.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The alignment of a text box and what it does to the borders (P07-T16). */
class BoxAlignTest {
    private val zone = Borders(40f, 560f)
    private val fixed = Borders(150f, 300f)
    private val min = TextZones.BOX_MIN_WIDTH_PT

    @Test
    fun center_fixedBorders_symmetricAroundTheAnchor_halfWidthTheLargerDistance() {
        val box = FlowBox(anchorXPt = 200f)

        val change = BoxAlign.aligned(box, TextAlign.START, TextAlign.CENTER, fixed, zone)

        // The anchor is 50 pt from the left border and 100 pt from the right one.
        assertThat(change.borders).isEqualTo(Borders(100f, 300f))
        assertThat(change.box.centerSaved).isEqualTo(fixed)
    }

    @Test
    fun center_nearTheZoneEdge_isLimitedByThePage() {
        val box = FlowBox(anchorXPt = 100f)

        val change = BoxAlign.aligned(box, TextAlign.START, TextAlign.CENTER, Borders(100f, 400f), zone)

        // 60 pt to the zone's left edge: the borders keep symmetric and inside the zone.
        assertThat(change.borders).isEqualTo(Borders(40f, 160f))
    }

    @Test
    fun center_anchorAtTheZoneEdge_keepsTheMinimumWidthInsideTheZone() {
        val box = FlowBox(anchorXPt = 45f)

        val change = BoxAlign.aligned(box, TextAlign.START, TextAlign.CENTER, Borders(45f, 300f), zone)

        assertThat(change.borders.leftPt).isEqualTo(40f)
        assertThat(change.borders.widthPt).isWithin(1e-3f).of(min)
    }

    @Test
    fun centerThenLeft_restoresTheSavedBorders() {
        val box = FlowBox(anchorXPt = 160f)
        val centered = BoxAlign.aligned(box, TextAlign.START, TextAlign.CENTER, fixed, zone)

        val left = BoxAlign.aligned(centered.box, TextAlign.CENTER, TextAlign.START, centered.borders, zone)

        assertThat(left.borders).isEqualTo(fixed)
        assertThat(left.box.centerSaved).isNull()
        assertThat(left.box).isEqualTo(box)
    }

    @Test
    fun centerThenRightOrJustify_restoresTheSavedBordersToo() {
        val centered = BoxAlign.aligned(FlowBox(anchorXPt = 160f), TextAlign.START, TextAlign.CENTER, fixed, zone)

        for (to in listOf(TextAlign.END, TextAlign.JUSTIFIED)) {
            assertThat(BoxAlign.aligned(centered.box, TextAlign.CENTER, to, centered.borders, zone).borders).isEqualTo(fixed)
        }
    }

    @Test
    fun leftRightJustify_keepTheBorders() {
        val box = FlowBox(anchorXPt = 160f)
        val aligns = listOf(TextAlign.START, TextAlign.END, TextAlign.JUSTIFIED)

        for (from in aligns) {
            for (to in aligns) {
                val change = BoxAlign.aligned(box, from, to, fixed, zone)
                assertThat(change.borders).isEqualTo(fixed)
                assertThat(change.box).isEqualTo(box)
            }
        }
    }

    @Test
    fun centerAgain_changesNothing() {
        val box = FlowBox(anchorXPt = 160f, centerSaved = fixed)
        val borders = Borders(20f, 300f)

        assertThat(BoxAlign.aligned(box, TextAlign.CENTER, TextAlign.CENTER, borders, zone)).isEqualTo(BoxAlign.Change(box, borders))
    }

    @Test
    fun center_withoutAnchor_centersOnTheMiddle() {
        val change = BoxAlign.aligned(FlowBox.FIXED, TextAlign.START, TextAlign.CENTER, fixed, zone)

        assertThat(change.borders).isEqualTo(fixed)
        assertThat(change.box.centerSaved).isEqualTo(fixed)
    }

    @Test
    fun center_inFillWidth_keepsTheFillBorders() {
        val box = FlowBox(anchorXPt = 160f, fill = true, custom = fixed)

        val change = BoxAlign.aligned(box, TextAlign.START, TextAlign.CENTER, zone, zone)

        assertThat(change.borders).isEqualTo(zone)
        assertThat(change.box).isEqualTo(box)
    }

    @Test
    fun leavingCenter_inFillWidth_givesTheSavedBordersBackToTheCustomSet() {
        val box = FlowBox(anchorXPt = 160f, fill = true, custom = Borders(20f, 300f), centerSaved = fixed)

        val change = BoxAlign.aligned(box, TextAlign.CENTER, TextAlign.START, zone, zone)

        assertThat(change.borders).isEqualTo(zone)
        assertThat(change.box.custom).isEqualTo(fixed)
        assertThat(change.box.centerSaved).isNull()
    }

    @Test
    fun autoWidth_keepsTheBorders_theReflowGrowsTheAlignedSide() {
        val box = FlowBox.created(160f)
        val auto = Borders(160f, 220f)

        for (to in TextAlign.entries) {
            val change = BoxAlign.aligned(box, TextAlign.START, to, auto, zone)
            assertThat(change).isEqualTo(BoxAlign.Change(box, auto))
        }
    }

    @Test
    fun anchorFollowsAMove_centerUsesTheMovedAnchor() {
        val moved = FlowBox(anchorXPt = 160f + 50f)

        val change = BoxAlign.aligned(moved, TextAlign.START, TextAlign.CENTER, Borders(200f, 350f), zone)

        assertThat((change.borders.leftPt + change.borders.rightPt) / 2f).isEqualTo(210f)
        assertThat(change.borders).isEqualTo(Borders(70f, 350f))
    }

    @Test
    fun symmetricKnob_movesBothBordersAroundTheMiddle() {
        val centered = Borders(100f, 300f)

        assertThat(BoxAlign.symmetricKnob(zone, centered, 250f)).isEqualTo(Borders(150f, 250f))
        assertThat(BoxAlign.symmetricKnob(zone, centered, 80f)).isEqualTo(Borders(80f, 320f))
        // The left knob dragged past the zone edge: limited there on both sides.
        assertThat(BoxAlign.symmetricKnob(zone, centered, 0f)).isEqualTo(Borders(40f, 360f))
        // Never narrower than the minimum.
        assertThat(BoxAlign.symmetricKnob(zone, centered, 200f).widthPt).isWithin(1e-3f).of(min)
    }
}
