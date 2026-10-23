package dev.folio.core.text.layout

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Grid invariant suite on Robolectric native graphics (host minikin/Skia); the tablet run
 * (GridInvariantInstrumentedTest) is authoritative. Writes the table to build/reports/grid-invariant/.
 */
@RunWith(AndroidJUnit4::class)
class GridInvariantTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = BlockLayouter(resolver, FontMetricsCache(resolver))

    @Test
    fun everyFontAndBlockStyle_atZoom124_baselinesWithinHalfPixelOfRules() {
        val results = FontRegistry.bundled.map { GridInvariantSuite.run(layouter, it.name) }
        File("build/reports/grid-invariant").mkdirs()
        File("build/reports/grid-invariant/robolectric.md").writeText(GridInvariantSuite.table(results))

        for (result in results) {
            assertWithMessage(result.family).that(result.violations).isEmpty()
            assertWithMessage("${result.family} runs").that(result.runs).isAtLeast(result.blocks)
        }
    }
}
