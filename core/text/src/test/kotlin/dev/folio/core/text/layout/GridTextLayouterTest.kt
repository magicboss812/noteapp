package dev.folio.core.text.layout

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import org.junit.Test
import org.junit.runner.RunWith

/** Grid invariant of the line-box layout (07-text-engine.md#line-box, A-006) for every template grid unit. */
@RunWith(AndroidJUnit4::class)
class GridTextLayouterTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = GridTextLayouter(resolver, FontMetricsCache(resolver))

    @Test
    fun gridPitch_everyTemplateUnit_everyBaselineOnItsRule() {
        for (unitMm in TEMPLATE_UNITS_MM) {
            val unitPt = unitMm * PT_PER_MM
            val block = layouter.layout(TEXT, FontRegistry.byName("Inter"), unitPt, BODY_M_CAP, WIDTH_PT, BaselineMethod.GRID_PITCH)

            assertWithMessage("U=$unitMm mm lines").that(block.lineCount).isAtLeast(MIN_LINES)
            assertWithMessage("U=$unitMm mm height").that(block.heightPt).isEqualTo(block.lineCount * unitPt)
            for (line in 0 until block.lineCount) {
                assertWithMessage("U=$unitMm mm line $line").that(block.baselinePt(line)).isWithin(TOLERANCE_PT).of((line + 1) * unitPt)
            }
        }
    }

    @Test
    fun gridPitch_referenceScale_oneUnitIsWholePixels() {
        for (unitMm in TEMPLATE_UNITS_MM) {
            val unitPt = unitMm * PT_PER_MM
            val block = layouter.layout("x", FontRegistry.byName("Inter"), unitPt, BODY_M_CAP, WIDTH_PT, BaselineMethod.GRID_PITCH)

            // The line height is chosen as whole px; refScale is derived from it and only approximates it back.
            assertWithMessage("U=$unitMm mm").that(block.lineHeightPx).isEqualTo(Math.round(block.lineHeightPx).toFloat())
            assertWithMessage("U=$unitMm mm").that(unitPt * block.refScale).isWithin(1e-3f).of(block.lineHeightPx)
            assertThat(block.refScale).isWithin(REF_SCALE_SLACK).of(GridTextLayouter.REFERENCE_SCALE)
        }
    }

    private companion object {
        const val PT_PER_MM = 72f / 25.4f

        /** Lined 6.0/7.1/8.7, blank 7.0, grid and dotted 4/5/7 mm (05-canvas-rendering.md#templates). */
        val TEMPLATE_UNITS_MM = floatArrayOf(4f, 5f, 6f, 7f, 7.1f, 8.7f)
        const val BODY_M_CAP = 0.45f
        const val WIDTH_PT = 150f
        const val MIN_LINES = 5

        /** 0.5 px at zoom 4 on the Pad 7 is about 0.024 pt. */
        const val TOLERANCE_PT = 1e-3f
        const val REF_SCALE_SLACK = 0.2f
        const val TEXT =
            "Every line of this paragraph must sit exactly on a rule of the template, whatever the grid unit, " +
                "font, or zoom level, including the last line with descenders gjpqy and a hard break.\nAfter the break."
    }
}
