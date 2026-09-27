package dev.folio.core.text.spike

import android.util.Log
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.layout.BaselineMethod
import dev.folio.core.text.layout.GridTextLayouter
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P01-S3 on the tablet (real fonts, device minikin): baseline error per font and method. Logs the
 * table under tag FolioProbe (`logcat.sh`); GRID_PITCH must stay within 0.5 px at zoom 1, 2 and 4.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProbeInstrumentedTest {
    @Test
    fun gridPitch_everyFontAtAllZooms_baselinesWithinHalfPixel() {
        val resolver = createFontFamilyResolver(InstrumentationRegistry.getInstrumentation().targetContext)
        val metrics = FontMetricsCache(resolver)
        val layouter = GridTextLayouter(resolver, metrics)
        // Warm-up so the first family does not carry class loading and font parsing in its layout time.
        BaselineProbe.run(layouter, metrics, FontRegistry.families.first(), BaselineMethod.GRID_PITCH)

        val results =
            BaselineMethod.entries.flatMap { method ->
                FontRegistry.families.map { BaselineProbe.run(layouter, metrics, it, method) }
            }
        BaselineProbe.table(results).lines().forEach { Log.i(TAG, it) }

        for (result in results.filter { it.method == BaselineMethod.GRID_PITCH }) {
            for (zoom in BaselineProbe.ZOOMS) {
                assertWithMessage("${result.family} zoom $zoom").that(result.maxBaselineErrorPx(zoom)).isAtMost(BaselineProbe.MAX_ERROR_PX)
            }
        }
    }

    private companion object {
        const val TAG = "FolioProbe"
    }
}
