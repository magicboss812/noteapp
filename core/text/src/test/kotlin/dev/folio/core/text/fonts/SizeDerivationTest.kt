package dev.folio.core.text.fonts

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * P06-T02 acceptance: the derived font size gives every bundled family the target cap height within
 * 1 %, for every role and body size, measured on the "H" outline at layout scale (about 4 px/pt).
 */
@RunWith(AndroidJUnit4::class)
class SizeDerivationTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val metrics = FontMetricsCache(resolver)

    @Test
    fun derive_everyBundledFamilyRoleAndSize_capHeightWithinOnePercent() {
        var worst = 0f
        for (family in FontRegistry.bundled) {
            for (unitMm in UNITS_MM) {
                val unitPt = unitMm * PT_PER_MM
                for (body in BODY_CAPS) {
                    for (role in TextRole.entries) {
                        val size = TextSizing.derive(metrics, family, role, body, unitPt)
                        val paint = FontMetricsCache.measurePaint(metrics.typeface(family, role.bold), size.fontSizePt * REF_SCALE)
                        val capPx = FontMetricsCache.capHeightPx(paint)
                        val targetPx = size.capFraction * unitPt * REF_SCALE
                        val error = abs(capPx - targetPx) / targetPx
                        worst = maxOf(worst, error)
                        assertWithMessage("${family.name} $role body=$body U=$unitMm mm").that(error).isAtMost(MAX_ERROR)
                    }
                }
            }
        }
        println("SizeDerivationTest worst cap height error ${"%.4f".format(worst * 100)} %")
    }

    private companion object {
        const val PT_PER_MM = 72f / 25.4f
        const val REF_SCALE = 4f
        const val MAX_ERROR = 0.01f
        val UNITS_MM = floatArrayOf(4f, 7.1f, 8.7f)
        val BODY_CAPS = BodySize.entries.map { it.capFraction } + listOf(TextSizing.MIN_BODY_CAP, 1.2f)
    }
}
