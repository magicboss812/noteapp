package dev.folio.core.render.template

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.model.AssetId
import dev.folio.core.model.GridUnit
import dev.folio.core.model.TemplateKind
import org.junit.Test

/** Grid unit per template (05-canvas-rendering.md#templates, 07-text-engine.md#grid-unit). */
class TemplatePresetsTest {
    @Test
    fun gridUnit_everyDefaultTemplate_matchesSpecTable() {
        val expectedMm =
            mapOf(
                TemplateKind.BLANK to 7.0f,
                TemplateKind.LINED to 7.1f,
                TemplateKind.GRID to 5.0f,
                TemplateKind.DOTTED to 5.0f,
                TemplateKind.CORNELL to 7.1f,
                TemplateKind.GRAPH_AXES to 5.0f,
                TemplateKind.MUSIC_STAFF to 7.0f,
                TemplateKind.PLANNER_DAILY to 7.1f,
                TemplateKind.PLANNER_WEEKLY to 7.1f,
                TemplateKind.CUSTOM to 7.1f,
            )
        assertThat(expectedMm.keys).containsExactlyElementsIn(TemplateKind.entries)

        for ((kind, mm) in expectedMm) {
            val unitPt = GridUnit.of(TemplatePresets.default(kind, AssetId(HASH))).unitPt
            assertWithMessage("$kind").that(unitPt).isWithin(EPS).of(mm * MM)
        }
    }

    @Test
    fun gridUnit_linedPresets_areNarrowCollegeWide() {
        val units = TemplatePresets.spacingsPt(TemplateKind.LINED).map { GridUnit.of(lined(it)).unitPt / MM }

        assertThat(units).hasSize(3)
        assertThat(units[0]).isWithin(EPS).of(6.0f)
        assertThat(units[1]).isWithin(EPS).of(7.1f)
        assertThat(units[2]).isWithin(EPS).of(8.7f)
    }

    @Test
    fun gridUnit_gridAndDottedPresets_are4To7Mm() {
        for (kind in listOf(TemplateKind.GRID, TemplateKind.DOTTED)) {
            val units = TemplatePresets.spacingsPt(kind).map { GridUnit.of(TemplatePresets.default(kind).copy(spacingPt = it)).unitPt / MM }
            assertWithMessage("$kind").that(units.map { Math.round(it * 10) / 10f }).containsExactly(4f, 5f, 7f).inOrder()
        }
    }

    @Test
    fun spacingsPt_fixedKinds_haveNoPresets() {
        val fixed = TemplateKind.entries.filter { TemplatePresets.spacingsPt(it).isEmpty() }

        assertThat(fixed).containsNoneOf(TemplateKind.LINED, TemplateKind.GRID, TemplateKind.DOTTED)
        assertThat(fixed).hasSize(TemplateKind.entries.size - 3)
    }

    @Test
    fun default_origins_matchSpecMargins() {
        val lined = GridUnit.of(TemplatePresets.default(TemplateKind.LINED))
        val grid = GridUnit.of(TemplatePresets.default(TemplateKind.GRID))

        assertThat(lined.originYPt / MM).isWithin(EPS).of(25f)
        assertThat(lined.originXPt / MM).isWithin(EPS).of(25f)
        assertThat(lined.snapsColumns).isFalse()
        assertThat(grid.originXPt).isEqualTo(0f)
        assertThat(grid.originYPt).isEqualTo(0f)
        assertThat(grid.snapsColumns).isTrue()
    }

    @Test
    fun default_colors_lineAndDots() {
        assertThat(TemplatePresets.default(TemplateKind.LINED).lineArgb).isEqualTo(0xFFC9D3E0.toInt())
        assertThat(TemplatePresets.default(TemplateKind.DOTTED).lineArgb).isEqualTo(0xFFB8C0CC.toInt())
    }

    private fun lined(spacingPt: Float) = TemplatePresets.default(TemplateKind.LINED).copy(spacingPt = spacingPt)

    private companion object {
        const val MM = 72f / 25.4f
        const val EPS = 1e-4f
        const val HASH = "0000000000000000000000000000000000000000000000000000000000000000"
    }
}
