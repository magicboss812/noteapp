package dev.folio.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/** The two border sets of a text box, the fill width button and the width knobs (P07-T15). */
class BoxWidthTest {
    private val zone = Borders(40f, 560f)
    private val custom = Borders(100f, 300f)
    private val min = TextZones.BOX_MIN_WIDTH_PT

    @Test
    fun toggled_customFillAdjustCustomFill_returnsLastCustom_thenAdjustedFill() {
        val start = FlowBox.FIXED

        val toFill = BoxWidth.toggled(start, custom, zone)
        assertThat(toFill.box.fill).isTrue()
        assertThat(toFill.borders).isEqualTo(zone)

        val adjusted = BoxWidth.knob(zone, toFill.borders, left = true, xPt = 120f)
        val fillBox = BoxWidth.knobbed(toFill.box)
        assertThat(fillBox).isEqualTo(toFill.box)

        val toCustom = BoxWidth.toggled(fillBox, adjusted, zone)
        assertThat(toCustom.box.fill).isFalse()
        assertThat(toCustom.borders).isEqualTo(custom)

        val again = BoxWidth.toggled(toCustom.box, toCustom.borders, zone)
        assertThat(again.box.fill).isTrue()
        assertThat(again.borders).isEqualTo(adjusted)
        assertThat(again.borders).isNotEqualTo(zone)
    }

    @Test
    fun toggled_unadjustedFill_isStoredAsTheZoneEdges() {
        val toFill = BoxWidth.toggled(FlowBox.FIXED, custom, zone)
        val back = BoxWidth.toggled(toFill.box, toFill.borders, zone)

        assertThat(back.box.fillBorders).isNull()
        // On a page with another zone the fill set follows that page's edges.
        val other = Borders(20f, 400f)
        assertThat(BoxWidth.toggled(back.box, back.borders, other).borders).isEqualTo(other)
    }

    @Test
    fun toggled_autoWidthBox_returnsToAutoWidth() {
        val auto = FlowBox.created(anchorXPt = 110f)

        val toFill = BoxWidth.toggled(auto, custom, zone)
        val back = BoxWidth.toggled(toFill.box, toFill.borders, zone)

        assertThat(back.box.autoWidth).isTrue()
        assertThat(back.box.fill).isFalse()
        assertThat(back.box.anchorXPt).isEqualTo(110f)
        assertThat(back.borders).isEqualTo(custom)
    }

    @Test
    fun toggled_bodyFlowWithoutCustomSet_keepsItsBorders() {
        val back = BoxWidth.toggled(FlowBox.FILL, zone, zone)

        assertThat(back.box.fill).isFalse()
        assertThat(back.borders).isEqualTo(zone)
    }

    @Test
    fun knobbed_endsAutoWidth_onlyForTheCustomSet() {
        val auto = FlowBox.created(anchorXPt = 110f)

        assertThat(BoxWidth.knobbed(auto).autoWidth).isFalse()
        val inFill = BoxWidth.toggled(auto, custom, zone).box
        val knobbed = BoxWidth.knobbed(inFill)
        assertThat(knobbed).isEqualTo(inFill)
        // The custom set is still auto width once fill width is switched off again.
        assertThat(BoxWidth.toggled(knobbed, zone, zone).box.autoWidth).isTrue()
    }

    @Test
    fun knob_isUnsnapped_otherBorderStays() {
        assertThat(BoxWidth.knob(zone, custom, left = false, xPt = 257.37f)).isEqualTo(Borders(100f, 257.37f))
        assertThat(BoxWidth.knob(zone, custom, left = true, xPt = 61.2f)).isEqualTo(Borders(61.2f, 300f))
    }

    @Test
    fun knob_keepsTheMinimumWidth_andStaysInsideTheZone() {
        assertThat(BoxWidth.knob(zone, custom, left = false, xPt = 0f)).isEqualTo(Borders(100f, 100f + min))
        assertThat(BoxWidth.knob(zone, custom, left = true, xPt = 900f)).isEqualTo(Borders(300f - min, 300f))
        assertThat(BoxWidth.knob(zone, custom, left = false, xPt = 900f)).isEqualTo(Borders(100f, zone.rightPt))
        assertThat(BoxWidth.knob(zone, custom, left = true, xPt = -50f)).isEqualTo(Borders(zone.leftPt, 300f))
        val narrow = Borders(10f, 10f + min / 2)
        assertThat(BoxWidth.knob(narrow, custom, left = true, xPt = 0f)).isEqualTo(narrow)
    }
}
