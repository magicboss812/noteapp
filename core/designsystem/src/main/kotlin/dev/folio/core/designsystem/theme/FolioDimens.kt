// Values are the spacing, radius and shadow tables of 11-design-system.md.
@file:Suppress("MagicNumber")

package dev.folio.core.designsystem.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.dropShadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Spacing scale in dp (11-design-system.md#spacing-and-shapes); names carry the dp value. */
@Immutable
data class FolioSpacing(
    val s2: Dp = 2.dp,
    val s4: Dp = 4.dp,
    val s8: Dp = 8.dp,
    val s12: Dp = 12.dp,
    val s16: Dp = 16.dp,
    val s20: Dp = 20.dp,
    val s24: Dp = 24.dp,
    val s32: Dp = 32.dp,
    val s40: Dp = 40.dp,
    val s48: Dp = 48.dp,
    val screenPaddingLandscape: Dp = 24.dp,
    val screenPaddingPortrait: Dp = 20.dp,
    /** Minimum touch target for pen and finger. */
    val touchTarget: Dp = 44.dp,
    /** Visual size of icon and tool buttons inside their touch target. */
    val buttonVisual: Dp = 40.dp,
    val iconToolbar: Dp = 22.dp,
    val iconMenu: Dp = 20.dp,
    val iconChip: Dp = 18.dp,
    val borderWidth: Dp = 1.dp,
    /** Selected card outline. */
    val selectedBorderWidth: Dp = 2.dp,
)

/** Corner radii: xs 6, s 10 (chips), m 14 (toolbar pills), l 16 (cards), xl 24 (sheets), full. */
@Immutable
data class FolioShapes(
    val xs: Shape = RoundedCornerShape(6.dp),
    val s: Shape = RoundedCornerShape(10.dp),
    val m: Shape = RoundedCornerShape(14.dp),
    val l: Shape = RoundedCornerShape(16.dp),
    val xl: Shape = RoundedCornerShape(24.dp),
    /** Sheets: only the top corners are rounded. */
    val sheet: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    val full: Shape = CircleShape,
)

/** A soft drop shadow: vertical offset, blur radius and black opacity. */
@Immutable
data class FolioShadow(
    val offsetY: Dp,
    val blur: Dp,
    val alpha: Float,
)

/** Shadow tokens (11-design-system.md#elevation). */
@Immutable
data class FolioElevation(
    val card: FolioShadow = FolioShadow(1.dp, 3.dp, 0.04f),
    val cardPressed: FolioShadow = FolioShadow(4.dp, 12.dp, 0.08f),
    val floating: FolioShadow = FolioShadow(6.dp, 20.dp, 0.10f),
    val page: FolioShadow = FolioShadow(2.dp, 8.dp, 0.06f),
    val sheet: FolioShadow = FolioShadow((-4).dp, 24.dp, 0.12f),
)

/** Draws [shadow] behind the content in [shape]. */
fun Modifier.folioShadow(
    shadow: FolioShadow,
    shape: Shape,
    color: Color = Color.Black,
): Modifier = dropShadow(shape, Shadow(radius = shadow.blur, color = color, offset = DpOffset(0.dp, shadow.offsetY), alpha = shadow.alpha))
