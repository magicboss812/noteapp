package dev.folio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import dev.folio.core.designsystem.theme.FolioTheme

private const val TOOL_ICON_START_SCALE = 0.92f

/** Visual style of [FolioIconButton]. */
enum class FolioIconButtonStyle {
    /** Icon only; used inside pills and toolbars. */
    Plain,

    /** Round surface with a border (library search button). */
    Outlined,
}

/** Icon button: 22 dp glyph in a 40 dp visual button with a 44 dp touch target. */
@Composable
fun FolioIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    style: FolioIconButtonStyle = FolioIconButtonStyle.Plain,
    tint: Color = Color.Unspecified,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.full
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = space.buttonVisual / 2),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val visual =
            when (style) {
                FolioIconButtonStyle.Plain -> {
                    Modifier.size(space.buttonVisual)
                }

                FolioIconButtonStyle.Outlined -> {
                    Modifier
                        .size(space.buttonVisual)
                        .background(colors.surface, shape)
                        .border(space.borderWidth, colors.border, shape)
                }
            }
        Box(visual, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(space.iconToolbar),
                tint = if (enabled) tint.takeOrElse { colors.textPrimary } else colors.textTertiary,
            )
        }
    }
}

/**
 * Toolbar tool: tinted rounded background and accent icon when selected; the icon scales 0.92 -> 1 on selection.
 * Tools of one group are mutually exclusive, so the semantics role is a radio button.
 */
@Composable
fun ToolButton(
    icon: ImageVector,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val motion = FolioTheme.motion
    val background by animateColorAsState(if (selected) colors.accentSoft else Color.Transparent, motion.fast(), label = "toolBg")
    val tint by animateColorAsState(
        targetValue =
            when {
                !enabled -> colors.textTertiary
                selected -> colors.accent
                else -> colors.textPrimary
            },
        animationSpec = motion.fast(),
        label = "toolTint",
    )
    val scale = remember { Animatable(1f) }
    LaunchedEffect(selected) {
        if (selected) {
            scale.snapTo(TOOL_ICON_START_SCALE)
            scale.animateTo(1f, motion.fast())
        }
    }
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .selectable(
                    selected = selected,
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = space.buttonVisual / 2),
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(space.buttonVisual).background(background, FolioTheme.shapes.s), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(space.iconToolbar).scale(scale.value),
                tint = tint,
            )
        }
    }
}
