package dev.folio.core.text.fonts

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily

/**
 * Em-box proportions of a typeface as fractions of the font size: cap height from the outline of
 * "H" (float, unhinted), ascent and descent from the font metrics (positive down, like Paint).
 */
data class FontProportions(
    val capRatio: Float,
    val ascentRatio: Float,
    val descentRatio: Float,
)

/**
 * Measures and caches [FontProportions] per bundled family (07-text-engine.md#font-normalization).
 * Thread-safe; resource fonts load synchronously through [resolver].
 */
class FontMetricsCache(
    private val resolver: FontFamily.Resolver,
) {
    private val cache = HashMap<String, FontProportions>()

    /** Proportions of the regular style of [family]. */
    @Synchronized
    fun proportions(family: BundledFontFamily): FontProportions = cache.getOrPut(family.name) { measure(typeface(family)) }

    /** The platform typeface of the regular style of [family]. */
    fun typeface(family: BundledFontFamily): Typeface =
        checkNotNull(resolver.resolve(family.fontFamily).value as? Typeface) { "${family.name} did not resolve to a Typeface" }

    companion object {
        private const val MEASURE_SIZE_PX = 1000f

        /** Measures [typeface] at a large size with linear metrics so hinting cannot round the result. */
        fun measure(typeface: Typeface): FontProportions {
            val paint = measurePaint(typeface, MEASURE_SIZE_PX)
            val metrics = paint.fontMetrics
            return FontProportions(
                capRatio = capHeightPx(paint) / MEASURE_SIZE_PX,
                ascentRatio = metrics.ascent / MEASURE_SIZE_PX,
                descentRatio = metrics.descent / MEASURE_SIZE_PX,
            )
        }

        /** Height of the "H" outline above the baseline for [paint] (float, from the glyph path). */
        fun capHeightPx(paint: Paint): Float {
            val path = Path()
            paint.getTextPath("H", 0, 1, 0f, 0f, path)
            val bounds = RectF()
            path.computeBounds(bounds, true)
            return -bounds.top
        }

        /** An unhinted, subpixel-positioned paint like the one text layout uses at the reference scale. */
        fun measurePaint(
            typeface: Typeface,
            sizePx: Float,
        ): Paint =
            Paint(Paint.ANTI_ALIAS_FLAG or Paint.LINEAR_TEXT_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
                this.typeface = typeface
                textSize = sizePx
                isLinearText = true
            }
    }
}
