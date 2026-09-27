package dev.folio.core.text.spike

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.layout.BaselineMethod
import dev.folio.core.text.layout.GridTextLayouter
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P01-S3 on Robolectric native graphics (host minikin/Skia). Writes the probe table to
 * build/reports/p01-s3/ for ADR-008; the device run (BaselineProbeInstrumentedTest) is authoritative.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProbeTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val metrics = FontMetricsCache(resolver)
    private val layouter = GridTextLayouter(resolver, metrics)

    @Test
    fun gridPitch_everyFontAtZoom4_baselinesWithinHalfPixel() {
        val results =
            BaselineMethod.entries.flatMap { method ->
                FontRegistry.families.map { BaselineProbe.run(layouter, metrics, it, method) }
            }
        File("build/reports/p01-s3").mkdirs()
        File("build/reports/p01-s3/baseline-robolectric.md").writeText(BaselineProbe.table(results))

        for (result in results.filter { it.method == BaselineMethod.GRID_PITCH }) {
            assertWithMessage(result.family).that(result.lines).isAtLeast(BaselineProbe.PARAGRAPHS.size)
            assertWithMessage(result.family).that(result.maxBaselineErrorPx(zoom = 4f)).isAtMost(BaselineProbe.MAX_ERROR_PX)
        }
    }
}
