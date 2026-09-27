package dev.folio.core.text.spike

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.fonts.FontRegistry
import dev.folio.core.text.layout.BaselineMethod
import dev.folio.core.text.layout.GridTextLayouter
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.ceil

@RunWith(AndroidJUnit4::class)
class FontSpecimenScreenshotTest {
    @Test
    fun compactSpecimen_gridPitch_matchesGolden() {
        val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
        val specimen =
            FontSpecimen(
                GridTextLayouter(resolver, FontMetricsCache(resolver)),
                FontRegistry.families,
                BaselineMethod.GRID_PITCH,
                compact = true,
            )
        val bitmap =
            Bitmap.createBitmap(
                ceil(FontSpecimen.WIDTH_PT * SCALE).toInt(),
                ceil(specimen.heightPt * SCALE).toInt(),
                Bitmap.Config.ARGB_8888,
            )

        specimen.draw(Canvas(bitmap), SCALE)

        bitmap.captureRoboImage("src/test/screenshots/FontSpecimen_compact.png")
    }

    private companion object {
        const val SCALE = 2f
    }
}
