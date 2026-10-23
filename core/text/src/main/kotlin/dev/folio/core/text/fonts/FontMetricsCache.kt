package dev.folio.core.text.fonts

import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

/**
 * Em-box proportions of a typeface as fractions of the font size: cap height from the outline of
 * "H" and x-height from "x" (float, unhinted), ascent and descent from the font metrics (positive
 * down, like Paint).
 */
data class FontProportions(
    val capRatio: Float,
    val xHeightRatio: Float,
    val ascentRatio: Float,
    val descentRatio: Float,
)

/**
 * Measures and caches [FontProportions] per family and weight (07-text-engine.md#font-normalization).
 * Thread-safe; resource and file fonts load synchronously through [resolver].
 */
class FontMetricsCache(
    private val resolver: FontFamily.Resolver,
) {
    private val cache = HashMap<String, FontProportions>()

    /** Proportions of the upright style of [family], bold when [bold]. */
    @Synchronized
    fun proportions(
        family: TextFontFamily,
        bold: Boolean = false,
    ): FontProportions = cache.getOrPut(if (bold) "${family.name}\u0000b" else family.name) { measure(typeface(family, bold)) }

    /** The platform typeface of the upright style of [family], bold when [bold]. */
    fun typeface(
        family: TextFontFamily,
        bold: Boolean = false,
    ): Typeface {
        val weight = if (bold) FontWeight.Bold else FontWeight.Normal
        return checkNotNull(
            resolver.resolve(family.fontFamily, weight).value as? Typeface,
        ) { "${family.name} did not resolve to a Typeface" }
    }

    companion object {
        private const val MEASURE_SIZE_PX = 1000f

        /** Measures [typeface] at a large size with linear metrics so hinting cannot round the result. */
        fun measure(typeface: Typeface): FontProportions {
            val paint = measurePaint(typeface, MEASURE_SIZE_PX)
            val metrics = paint.fontMetrics
            return FontProportions(
                capRatio = capHeightPx(paint) / MEASURE_SIZE_PX,
                xHeightRatio = glyphTopPx(paint, "x") / MEASURE_SIZE_PX,
                ascentRatio = metrics.ascent / MEASURE_SIZE_PX,
                descentRatio = metrics.descent / MEASURE_SIZE_PX,
            )
        }

        /** Height of the "H" outline above the baseline for [paint] (float, from the glyph path). */
        fun capHeightPx(paint: Paint): Float = glyphTopPx(paint, "H")

        private fun glyphTopPx(
            paint: Paint,
            glyph: String,
        ): Float {
            val path = Path()
            paint.getTextPath(glyph, 0, glyph.length, 0f, 0f, path)
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
