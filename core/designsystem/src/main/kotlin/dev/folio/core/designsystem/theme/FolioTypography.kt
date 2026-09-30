// Sizes and weights are the type table of 11-design-system.md#typography.
@file:Suppress("MagicNumber")

package dev.folio.core.designsystem.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import dev.folio.core.designsystem.R

/** UI text styles. Document text uses core:text fonts and grid layout, never these. */
@Immutable
data class FolioTypography(
    val displayLarge: TextStyle,
    val displaySmall: TextStyle,
    val cardTitle: TextStyle,
    val titleMedium: TextStyle,
    val body: TextStyle,
    val bodySmall: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
) {
    companion object {
        /** The token table, fonts bundled in res/font (Inter and Fraunces, OFL). */
        val Default: FolioTypography by lazy {
            FolioTypography(
                displayLarge = fraunces(34.sp, 40.sp),
                displaySmall = fraunces(24.sp, 30.sp),
                cardTitle = fraunces(20.sp, 26.sp),
                titleMedium = inter(17.sp, 24.sp, FontWeight.SemiBold),
                body = inter(15.sp, 22.sp, FontWeight.Normal),
                bodySmall = inter(12.5.sp, 18.sp, FontWeight.Normal),
                label = inter(14.sp, 20.sp, FontWeight.Medium),
                caption = inter(12.sp, 16.sp, FontWeight.Normal),
            )
        }
    }
}

private val DisplayWeight = FontWeight(560)

private val Inter: FontFamily by lazy {
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold).map { weight ->
            Font(R.font.inter_var, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )
}

private fun inter(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
) = TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size, lineHeight = lineHeight)

/** Fraunces with its optical size axis pinned to the style's size ("opsz auto" in 11-design-system.md#typography). */
private fun fraunces(
    size: TextUnit,
    lineHeight: TextUnit,
): TextStyle {
    val axes = FontVariation.Settings(FontVariation.weight(DisplayWeight.weight), FontVariation.Setting("opsz", size.value))
    val family = FontFamily(Font(R.font.fraunces_var, DisplayWeight, variationSettings = axes))
    return TextStyle(fontFamily = family, fontWeight = DisplayWeight, fontSize = size, lineHeight = lineHeight)
}
