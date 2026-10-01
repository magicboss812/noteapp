// Values are the spacing, size, radius and shadow tables of 11-design-system.md.
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

/** Spacing scale and component sizes in dp (11-design-system.md#spacing-and-shapes); spacing names carry the dp value. */
@Immutable
data class FolioSpacing(
    val s2: Dp = 2.dp,
    val s4: Dp = 4.dp,
    val s8: Dp = 8.dp,
    val s12: Dp = 12.dp,
    val s16: Dp = 16.dp,
    val s20: Dp = 20.dp,
    val s24: Dp = 24.dp,
    val s28: Dp = 28.dp,
    val s32: Dp = 32.dp,
    val s40: Dp = 40.dp,
    val s48: Dp = 48.dp,
    /** Edge distance and gap of floating chrome. */
    val chromeInset: Dp = 8.dp,
    /** Minimum touch target for pen and finger. */
    val touchTarget: Dp = 44.dp,
    /** Height (or rail width) of a toolbar pill. */
    val toolbarPill: Dp = 44.dp,
    /** Visual toolbar cell: the selected tool circle. */
    val toolbarCell: Dp = 36.dp,
    val iconToolbar: Dp = 24.dp,
    val iconMenu: Dp = 20.dp,
    val iconSmall: Dp = 16.dp,
    val colorDot: Dp = 22.dp,
    /** Selection dot under a width or color cell. */
    val indicatorDot: Dp = 4.dp,
    /** Toolbar divider thickness and length. */
    val dividerWidth: Dp = 2.dp,
    val dividerLength: Dp = 26.dp,
    val popoverWidth: Dp = 340.dp,
    val dialogWidth: Dp = 560.dp,
    val closeChip: Dp = 24.dp,
    val borderWidth: Dp = 1.dp,
    /** Focus and selection outline. */
    val selectedBorderWidth: Dp = 2.dp,
)

/** Corner radii (11-design-system.md#spacing-and-shapes). */
@Immutable
data class FolioShapes(
    /** Pills, chips, buttons, bars: half the height. */
    val stadium: Shape = RoundedCornerShape(50),
    val card: Shape = RoundedCornerShape(16.dp),
    val popover: Shape = RoundedCornerShape(18.dp),
    val panel: Shape = RoundedCornerShape(24.dp),
    val menu: Shape = RoundedCornerShape(12.dp),
    val tile: Shape = RoundedCornerShape(10.dp),
    val settings: Shape = RoundedCornerShape(8.dp),
    val field: Shape = RoundedCornerShape(4.dp),
    val checkbox: Shape = RoundedCornerShape(2.dp),
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
    /** Popovers, menus, panels and dialogs, both themes. */
    val popover: FolioShadow = FolioShadow(4.dp, 8.dp, 0.16f),
    /** Toolbar pills, light theme only. */
    val toolbar: FolioShadow = FolioShadow(2.dp, 4.dp, 0.10f),
    val page: FolioShadow = FolioShadow(2.dp, 8.dp, 0.06f),
)

/** Draws [shadow] behind the content in [shape]. */
fun Modifier.folioShadow(
    shadow: FolioShadow,
    shape: Shape,
    color: Color = Color.Black,
): Modifier = dropShadow(shape, Shadow(radius = shadow.blur, color = color, offset = DpOffset(0.dp, shadow.offsetY), alpha = shadow.alpha))
