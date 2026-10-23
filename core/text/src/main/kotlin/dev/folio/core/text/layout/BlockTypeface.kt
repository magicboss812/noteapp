package dev.folio.core.text.layout

import android.graphics.Typeface
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontSynthesis
import androidx.compose.ui.text.font.FontWeight

/** The platform typeface of this layout's text (what the live field of the focused block sets), null for blocks without text. */
fun BlockLayout.typeface(resolver: FontFamily.Resolver): Typeface? = face?.typeface(resolver)

/** The platform typeface of this face (a table cell field sets the header's or the body's). */
fun BlockFace.typeface(resolver: FontFamily.Resolver): Typeface? {
    val weight = if (bold) FontWeight.Bold else FontWeight.Normal
    return resolver.resolve(family, weight, FontStyle.Normal, FontSynthesis.All).value as? Typeface
}
