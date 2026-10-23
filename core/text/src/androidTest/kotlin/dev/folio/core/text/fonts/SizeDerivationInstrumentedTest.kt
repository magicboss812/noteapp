package dev.folio.core.text.fonts

import android.util.Log
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/** P06-T02 on the tablet (device font stack): derived sizes hit the target cap height within 1 %. */
@RunWith(AndroidJUnit4::class)
class SizeDerivationInstrumentedTest {
    @Test
    fun derive_everyBundledFamilyRoleAndSize_capHeightWithinOnePercent() {
        val metrics = FontMetricsCache(createFontFamilyResolver(InstrumentationRegistry.getInstrumentation().targetContext))
        var worst = 0f
        for (family in FontRegistry.bundled) {
            val unitPt = UNIT_MM * PT_PER_MM
            for (body in BodySize.entries) {
                for (role in TextRole.entries) {
                    val size = TextSizing.derive(metrics, family, role, body.capFraction, unitPt)
                    val paint = FontMetricsCache.measurePaint(metrics.typeface(family, role.bold), size.fontSizePt * REF_SCALE)
                    val targetPx = size.capFraction * unitPt * REF_SCALE
                    val error = abs(FontMetricsCache.capHeightPx(paint) - targetPx) / targetPx
                    worst = maxOf(worst, error)
                    assertWithMessage("${family.name} $role $body").that(error).isAtMost(MAX_ERROR)
                }
            }
        }
        Log.i(TAG, "size derivation worst cap height error ${worst * 100} %")
    }

    private companion object {
        const val TAG = "FolioProbe"
        const val PT_PER_MM = 72f / 25.4f
        const val UNIT_MM = 7.1f
        const val REF_SCALE = 4f
        const val MAX_ERROR = 0.01f
    }
}
