package dev.folio.core.model

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test

class GridUnitTest {
    private fun template(
        kind: TemplateKind,
        spacingPt: Float = 17f,
        marginLeftPt: Float = 0f,
        marginTopPt: Float = 0f,
        customGridPt: Float? = null,
    ) = Template(kind, spacingPt, LINE, marginLeftPt, marginTopPt, null, customGridPt)

    @Test
    fun of_spacingKinds_useTemplateSpacing() {
        for (kind in listOf(TemplateKind.LINED, TemplateKind.GRID, TemplateKind.DOTTED)) {
            assertWithMessage("$kind").that(GridUnit.of(template(kind, spacingPt = 17f)).unitPt).isEqualTo(17f)
        }
    }

    @Test
    fun of_fixedKinds_useSpecUnitsAndIgnoreSpacing() {
        val expectedMm =
            mapOf(
                TemplateKind.BLANK to 7.0f,
                TemplateKind.CORNELL to 7.1f,
                TemplateKind.GRAPH_AXES to 5.0f,
                TemplateKind.MUSIC_STAFF to 7.0f,
                TemplateKind.PLANNER_DAILY to 7.1f,
                TemplateKind.PLANNER_WEEKLY to 7.1f,
            )
        for ((kind, mm) in expectedMm) {
            assertWithMessage("$kind").that(GridUnit.of(template(kind, spacingPt = 30f)).unitPt).isWithin(EPS).of(mm * MM)
        }
    }

    @Test
    fun of_blankUnit_is19Point84Pt() {
        assertThat(GridUnit.of(template(TemplateKind.BLANK)).unitPt).isWithin(0.005f).of(19.84f)
    }

    @Test
    fun of_custom_usesUserValueOrDefault() {
        assertThat(GridUnit.of(template(TemplateKind.CUSTOM, customGridPt = 14.17f)).unitPt).isEqualTo(14.17f)
        assertThat(GridUnit.of(template(TemplateKind.CUSTOM, customGridPt = null)).unitPt).isWithin(EPS).of(7.1f * MM)
    }

    @Test
    fun of_origin_isLeftAndTopMargin() {
        val grid = GridUnit.of(template(TemplateKind.LINED, spacingPt = 20f, marginLeftPt = 70f, marginTopPt = 71f))

        assertThat(grid.originXPt).isEqualTo(70f)
        assertThat(grid.originYPt).isEqualTo(71f)
        assertThat(grid.ruleYPt(3)).isEqualTo(131f)
        assertThat(grid.columnXPt(-1)).isEqualTo(50f)
    }

    @Test
    fun of_columnSnapping_onlyForLatticeKinds() {
        val snapping = TemplateKind.entries.filter { GridUnit.of(template(it)).snapsColumns }

        assertThat(snapping).containsExactly(TemplateKind.GRID, TemplateKind.DOTTED, TemplateKind.GRAPH_AXES)
    }

    @Test
    fun of_invalidDecodedValues_fallBackOrClamp() {
        for (bad in listOf(0f, -3f, Float.NaN, Float.POSITIVE_INFINITY)) {
            val grid = GridUnit.of(template(TemplateKind.GRID, spacingPt = bad, marginLeftPt = bad, marginTopPt = bad))
            assertWithMessage("$bad").that(grid.unitPt).isWithin(EPS).of(7.1f * MM)
            assertWithMessage("$bad").that(grid.originXPt).isEqualTo(0f)
            assertWithMessage("$bad").that(grid.originYPt).isEqualTo(0f)
        }
        assertThat(GridUnit.of(template(TemplateKind.LINED, spacingPt = 0.01f)).unitPt).isEqualTo(GridUnit.MIN_UNIT_PT)
        assertThat(GridUnit.of(template(TemplateKind.LINED, spacingPt = 1e9f)).unitPt).isEqualTo(GridUnit.MAX_UNIT_PT)
        assertThat(GridUnit.of(template(TemplateKind.CUSTOM, customGridPt = Float.NaN)).unitPt).isWithin(EPS).of(7.1f * MM)
        assertThat(GridUnit.of(template(TemplateKind.LINED, marginTopPt = 1e9f)).originYPt).isEqualTo(GridUnit.MAX_MARGIN_PT)
    }

    private companion object {
        const val LINE = 0xFFC9D3E0.toInt()
        const val MM = 72f / 25.4f
        const val EPS = 1e-4f
    }
}
