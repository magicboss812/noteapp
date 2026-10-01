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
    val display: TextStyle,
    val headline: TextStyle,
    val title: TextStyle,
    val titleSmall: TextStyle,
    val cardTitle: TextStyle,
    val body: TextStyle,
    val bodyMedium: TextStyle,
    val label: TextStyle,
    val labelSmall: TextStyle,
    val caption: TextStyle,
    val toolbarValue: TextStyle,
) {
    companion object {
        /** The token table, fonts bundled in res/font (Inter and Literata, OFL). */
        val Default: FolioTypography by lazy {
            FolioTypography(
                display = literata(32.sp, 42.sp),
                headline = inter(22.sp, 30.sp, FontWeight.Bold),
                title = inter(20.sp, 26.sp, FontWeight.Normal),
                titleSmall = inter(18.sp, 24.sp, FontWeight.Medium),
                cardTitle = literata(15.sp, 17.sp),
                body = inter(16.sp, 22.sp, FontWeight.Normal),
                bodyMedium = inter(15.sp, 20.sp, FontWeight.Medium),
                label = inter(15.sp, 19.sp, FontWeight.Normal),
                labelSmall = inter(14.sp, 18.sp, FontWeight.Medium),
                caption = inter(12.sp, 14.sp, FontWeight.Normal),
                toolbarValue = inter(16.sp, 20.sp, FontWeight.Medium),
            )
        }
    }
}

private val Inter: FontFamily by lazy {
    FontFamily(
        listOf(FontWeight.Normal, FontWeight.Medium, FontWeight.SemiBold, FontWeight.Bold).map { weight ->
            Font(R.font.inter_var, weight, variationSettings = FontVariation.Settings(FontVariation.weight(weight.weight)))
        },
    )
}

private fun inter(
    size: TextUnit,
    lineHeight: TextUnit,
    weight: FontWeight,
) = TextStyle(fontFamily = Inter, fontWeight = weight, fontSize = size, lineHeight = lineHeight)

/** Bold Literata with its optical size axis pinned to the style's size (Compose applies no automatic opsz). */
private fun literata(
    size: TextUnit,
    lineHeight: TextUnit,
): TextStyle {
    val weight = FontWeight.Bold
    val axes = FontVariation.Settings(FontVariation.weight(weight.weight), FontVariation.Setting("opsz", size.value))
    val family = FontFamily(Font(R.font.literata_var, weight, variationSettings = axes))
    return TextStyle(fontFamily = family, fontWeight = weight, fontSize = size, lineHeight = lineHeight)
}
