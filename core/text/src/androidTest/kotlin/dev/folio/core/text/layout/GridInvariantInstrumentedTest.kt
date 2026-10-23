package dev.folio.core.text.layout

import android.util.Log
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P06-T03 on the tablet (device fonts and minikin): 20 fonts x every block style x zoom 1/2/4, every
 * drawn baseline within 0.5 px of its rule. Logs the table under tag FolioProbe (`logcat.sh`).
 */
@RunWith(AndroidJUnit4::class)
class GridInvariantInstrumentedTest {
    @Test
    fun everyFontAndBlockStyle_atZoom124_baselinesWithinHalfPixelOfRules() {
        val resolver = createFontFamilyResolver(InstrumentationRegistry.getInstrumentation().targetContext)
        val layouter = BlockLayouter(resolver, FontMetricsCache(resolver))

        val results = FontRegistry.bundled.map { GridInvariantSuite.run(layouter, it.name) }
        GridInvariantSuite.table(results).lines().forEach { Log.i(TAG, it) }

        assertWithMessage("families").that(results).hasSize(BUNDLED_FAMILIES)
        for (result in results) {
            result.violations.take(LOGGED_VIOLATIONS).forEach { Log.e(TAG, "${result.family}: $it") }
            assertWithMessage(result.family).that(result.violations).isEmpty()
            assertWithMessage("${result.family} runs").that(result.runs).isAtLeast(result.blocks)
        }
    }

    private companion object {
        const val TAG = "FolioProbe"
        const val BUNDLED_FAMILIES = 20
        const val LOGGED_VIOLATIONS = 20
    }
}
