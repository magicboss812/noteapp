package dev.folio.core.text.fonts

import android.content.Context
import android.graphics.Typeface
import androidx.annotation.FontRes
import androidx.compose.ui.text.font.AndroidFont
import androidx.compose.ui.text.font.FontLoadingStrategy
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import android.graphics.fonts.Font as PlatformFont
import android.graphics.fonts.FontFamily as PlatformFontFamily
import android.graphics.fonts.FontStyle as PlatformFontStyle

/**
 * One weight and style of a variable font resource, built with its `wght` axis set in the platform font itself.
 * Compose's own variation path (`Font(resId, weight, style, variationSettings)`) sets the axis on a Paint and reads
 * the Paint's typeface back; on the Pad 7 (HyperOS, SDK 36) that typeface stays the default instance, so every bold
 * run drew at regular weight. Glyphs the font lacks still come from the system fallback.
 */
internal class VariableResourceFont(
    @param:FontRes val resId: Int,
    override val weight: FontWeight,
    override val style: FontStyle,
) : AndroidFont(FontLoadingStrategy.Blocking, Loader, FontVariation.Settings()) {
    override fun equals(other: Any?): Boolean =
        other is VariableResourceFont && other.resId == resId && other.weight == weight && other.style == style

    override fun hashCode(): Int = (resId * HASH + weight.hashCode()) * HASH + style.hashCode()

    override fun toString(): String = "VariableResourceFont($resId, $weight, $style)"

    private object Loader : TypefaceLoader {
        override fun loadBlocking(
            context: Context,
            font: AndroidFont,
        ): Typeface = load(context, font as VariableResourceFont)

        override suspend fun awaitLoad(
            context: Context,
            font: AndroidFont,
        ): Typeface = load(context, font as VariableResourceFont)

        private fun load(
            context: Context,
            font: VariableResourceFont,
        ): Typeface {
            val italic = font.style == FontStyle.Italic
            val slant = if (italic) PlatformFontStyle.FONT_SLANT_ITALIC else PlatformFontStyle.FONT_SLANT_UPRIGHT
            val platform =
                PlatformFont
                    .Builder(context.resources, font.resId)
                    .setFontVariationSettings("'wght' ${font.weight.weight}")
                    .setWeight(font.weight.weight)
                    .setSlant(slant)
                    .build()
            return Typeface
                .CustomFallbackBuilder(PlatformFontFamily.Builder(platform).build())
                .setStyle(PlatformFontStyle(font.weight.weight, slant))
                .setSystemFallback(SYSTEM_FALLBACK)
                .build()
        }
    }

    private companion object {
        const val HASH = 31
        const val SYSTEM_FALLBACK = "sans-serif"
    }
}
