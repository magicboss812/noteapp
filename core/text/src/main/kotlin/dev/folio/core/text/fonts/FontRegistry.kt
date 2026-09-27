package dev.folio.core.text.fonts

import androidx.annotation.FontRes
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import dev.folio.core.text.R

/** Font groups offered in the font picker (07-text-engine.md#fonts). */
enum class FontGroup { SANS, SERIF, MONO, HANDWRITING }

/** One bundled OFL family; [name] is the family name documents reference. */
class BundledFontFamily(
    val name: String,
    val group: FontGroup,
    val fontFamily: FontFamily,
)

/** The 20 bundled text font families (07-text-engine.md#fonts); Inter is the default. */
object FontRegistry {
    /** Default text family. */
    const val DEFAULT_FAMILY = "Inter"

    /** All bundled families in picker order. */
    val families: List<BundledFontFamily> =
        listOf(
            variable("Inter", FontGroup.SANS, R.font.inter_var, R.font.inter_italic_var),
            variable("IBM Plex Sans", FontGroup.SANS, R.font.ibm_plex_sans_var, R.font.ibm_plex_sans_italic_var),
            variable("Source Sans 3", FontGroup.SANS, R.font.source_sans_3_var, R.font.source_sans_3_italic_var),
            variable("Nunito", FontGroup.SANS, R.font.nunito_var, R.font.nunito_italic_var),
            variable("Lexend", FontGroup.SANS, R.font.lexend_var, italic = null),
            variable(
                "Atkinson Hyperlegible Next",
                FontGroup.SANS,
                R.font.atkinson_hyperlegible_next_var,
                R.font.atkinson_hyperlegible_next_italic_var,
            ),
            variable("Noto Sans", FontGroup.SANS, R.font.noto_sans_var, R.font.noto_sans_italic_var),
            variable("Source Serif 4", FontGroup.SERIF, R.font.source_serif_4_var, R.font.source_serif_4_italic_var),
            variable("Lora", FontGroup.SERIF, R.font.lora_var, R.font.lora_italic_var),
            variable("Merriweather", FontGroup.SERIF, R.font.merriweather_var, R.font.merriweather_italic_var),
            variable("EB Garamond", FontGroup.SERIF, R.font.eb_garamond_var, R.font.eb_garamond_italic_var),
            variable("Crimson Pro", FontGroup.SERIF, R.font.crimson_pro_var, R.font.crimson_pro_italic_var),
            variable("Literata", FontGroup.SERIF, R.font.literata_var, R.font.literata_italic_var),
            variable("JetBrains Mono", FontGroup.MONO, R.font.jetbrains_mono_var, R.font.jetbrains_mono_italic_var),
            static(
                "IBM Plex Mono",
                FontGroup.MONO,
                StaticFiles(
                    R.font.ibm_plex_mono_regular,
                    R.font.ibm_plex_mono_italic,
                    R.font.ibm_plex_mono_bold,
                    R.font.ibm_plex_mono_bold_italic,
                ),
            ),
            variable("Caveat", FontGroup.HANDWRITING, R.font.caveat_var, italic = null),
            static("Patrick Hand", FontGroup.HANDWRITING, StaticFiles(R.font.patrick_hand_regular)),
            static("Kalam", FontGroup.HANDWRITING, StaticFiles(R.font.kalam_regular, bold = R.font.kalam_bold)),
            static("Architects Daughter", FontGroup.HANDWRITING, StaticFiles(R.font.architects_daughter_regular)),
            static("Shadows Into Light", FontGroup.HANDWRITING, StaticFiles(R.font.shadows_into_light_regular)),
        )

    /** The family called [name], or the default family when it is not bundled. */
    fun byName(name: String): BundledFontFamily = families.firstOrNull { it.name == name } ?: families.first { it.name == DEFAULT_FAMILY }

    /** Variable fonts: regular and bold are instances of the wght axis of one file per style. */
    private fun variable(
        name: String,
        group: FontGroup,
        @FontRes upright: Int,
        @FontRes italic: Int?,
    ): BundledFontFamily {
        val fonts =
            buildList {
                for (weight in listOf(FontWeight.Normal, FontWeight.Bold)) {
                    val axes = FontVariation.Settings(FontVariation.weight(weight.weight))
                    add(Font(upright, weight, FontStyle.Normal, variationSettings = axes))
                    if (italic != null) add(Font(italic, weight, FontStyle.Italic, variationSettings = axes))
                }
            }
        return BundledFontFamily(name, group, FontFamily(fonts))
    }

    private fun static(
        name: String,
        group: FontGroup,
        files: StaticFiles,
    ): BundledFontFamily {
        val fonts =
            listOfNotNull(
                Font(files.regular, FontWeight.Normal, FontStyle.Normal),
                files.italic?.let { Font(it, FontWeight.Normal, FontStyle.Italic) },
                files.bold?.let { Font(it, FontWeight.Bold, FontStyle.Normal) },
                files.boldItalic?.let { Font(it, FontWeight.Bold, FontStyle.Italic) },
            )
        return BundledFontFamily(name, group, FontFamily(fonts))
    }

    private class StaticFiles(
        @FontRes val regular: Int,
        @FontRes val italic: Int? = null,
        @FontRes val bold: Int? = null,
        @FontRes val boldItalic: Int? = null,
    )
}
