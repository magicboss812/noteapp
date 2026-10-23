package dev.folio.core.text.fonts

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class TextSizingTest {
    @Test
    fun capFraction_bodyM_matchesSpecTable() {
        val m = BodySize.M.capFraction

        assertThat(TextSizing.capFraction(TextRole.BODY, m)).isWithin(EPS).of(0.45f)
        assertThat(TextSizing.capFraction(TextRole.H1, m)).isWithin(EPS).of(0.95f)
        assertThat(TextSizing.capFraction(TextRole.H2, m)).isWithin(EPS).of(0.75f)
        assertThat(TextSizing.capFraction(TextRole.H3, m)).isWithin(EPS).of(0.55f)
        assertThat(TextSizing.capFraction(TextRole.CODE, m)).isWithin(EPS).of(0.40f)
    }

    @Test
    fun capFraction_bodyPresets_bodyRoleUsesPreset() {
        for (size in BodySize.entries) {
            assertThat(TextSizing.capFraction(TextRole.BODY, size.capFraction)).isWithin(EPS).of(size.capFraction)
        }
    }

    @Test
    fun boxUnits_presets_keepSpecBoxes() {
        for (size in BodySize.entries) {
            for (role in TextRole.entries) {
                val cap = TextSizing.capFraction(role, size.capFraction)
                assertWithMessage("$role at $size").that(TextSizing.boxUnits(role, cap)).isEqualTo(role.minBoxUnits)
            }
        }
    }

    @Test
    fun boxUnits_freeBodySize_growsInWholeUnits() {
        assertThat(TextSizing.boxUnits(TextRole.BODY, 0.66f)).isEqualTo(1)
        assertThat(TextSizing.boxUnits(TextRole.BODY, 0.70f)).isEqualTo(2)
        assertThat(TextSizing.boxUnits(TextRole.BODY, 1.32f)).isEqualTo(2)
        assertThat(TextSizing.boxUnits(TextRole.BODY, 1.40f)).isEqualTo(3)
        assertThat(TextSizing.boxUnits(TextRole.H1, TextSizing.capFraction(TextRole.H1, 0.9f))).isEqualTo(3)
    }

    @Test
    fun clampBodyCap_outOfRangeOrNaN_clamped() {
        assertThat(TextSizing.clampBodyCap(0.1f)).isEqualTo(TextSizing.MIN_BODY_CAP)
        assertThat(TextSizing.clampBodyCap(9f)).isEqualTo(TextSizing.MAX_BODY_CAP)
        assertThat(TextSizing.clampBodyCap(Float.NaN)).isEqualTo(BodySize.M.capFraction)
        assertThat(TextSizing.boxUnits(TextRole.BODY, TextSizing.MAX_BODY_CAP)).isEqualTo(3)
    }

    @Test
    fun fontSizePt_capRatio_scalesInversely() {
        assertThat(TextSizing.fontSizePt(capRatio = 0.7f, capFraction = 0.45f, unitPt = 20f)).isWithin(EPS).of(9f / 0.7f)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
