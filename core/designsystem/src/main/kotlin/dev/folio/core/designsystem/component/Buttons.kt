package dev.folio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme

private const val TOOL_ICON_START_SCALE = 0.92f
private val PRIMARY_BUTTON_HEIGHT = 48.dp
private val CLOSE_GLYPH = 14.dp

/** Visual style of [FolioIconButton]. */
enum class FolioIconButtonStyle {
    /** Icon only; used inside pills and toolbars. */
    Plain,

    /** Round `surface` capsule with a border (library and top pills). */
    Outlined,
}

/** Icon button: 24 dp glyph on a 36 dp visual cell with a 44 dp touch target. */
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
                    indication = ripple(bounded = false, radius = space.toolbarCell / 2),
                    enabled = enabled,
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        val visual =
            when (style) {
                FolioIconButtonStyle.Plain -> {
                    Modifier.size(space.toolbarCell)
                }

                FolioIconButtonStyle.Outlined -> {
                    Modifier
                        .size(space.touchTarget)
                        .background(colors.surface, shape)
                        .border(space.borderWidth, colors.border, shape)
                }
            }
        Box(visual, contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(space.iconToolbar),
                tint = if (enabled) tint.takeOrElse { colors.iconToolbar } else colors.textDisabled,
            )
        }
    }
}

/**
 * Toolbar tool: a 36 dp `accentContainerStrong` circle behind the icon when selected; the icon scales 0.92 -> 1 on
 * selection. Tools of one group are mutually exclusive, so the semantics role is a radio button.
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
    val background by animateColorAsState(
        if (selected) colors.accentContainerStrong else Color.Transparent,
        motion.fast(),
        label = "toolBg",
    )
    val tint by animateColorAsState(
        targetValue =
            when {
                !enabled -> colors.textDisabled
                selected -> colors.onAccentContainerStrong
                else -> colors.iconToolbar
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
                    indication = ripple(bounded = false, radius = space.toolbarCell / 2),
                    enabled = enabled,
                    role = Role.RadioButton,
                    onClick = onClick,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(space.toolbarCell).background(background, FolioTheme.shapes.full), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(space.iconToolbar).scale(scale.value),
                tint = tint,
            )
        }
    }
}

/** Visual style of [FolioButton]. */
enum class FolioButtonStyle {
    /** Filled `accentFill` stadium, 48 dp (primary action of a screen or popover). */
    Primary,

    /** Label only (dialog actions). */
    Text,
}

/** Text button; [destructive] paints a [FolioButtonStyle.Text] label in `danger` (never a danger fill). */
@Composable
fun FolioButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: FolioButtonStyle = FolioButtonStyle.Primary,
    enabled: Boolean = true,
    destructive: Boolean = false,
    textColor: Color = Color.Unspecified,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val primary = style == FolioButtonStyle.Primary
    val labelColor =
        when {
            !enabled -> colors.textDisabled
            primary -> colors.onAccent
            destructive -> colors.danger
            else -> textColor.takeOrElse { colors.accent }
        }
    Box(
        modifier =
            modifier
                .heightIn(min = if (primary) PRIMARY_BUTTON_HEIGHT else space.touchTarget)
                .clip(FolioTheme.shapes.stadium)
                .background(
                    if (primary && enabled) {
                        colors.accentFill
                    } else if (primary) {
                        colors.surfaceGroup
                    } else {
                        Color.Transparent
                    },
                ).clickable(enabled = enabled, role = Role.Button, onClick = onClick)
                .padding(horizontal = if (primary) space.s24 else space.s12),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = FolioTheme.type.bodyMedium, color = labelColor)
    }
}

/** 24 dp close chip (popover and panel headers) in a 44 dp target. */
@Composable
fun CloseChip(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    contentDescription: String = "Close",
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .clickable(
                    interactionSource = null,
                    indication = ripple(bounded = false, radius = space.closeChip / 2),
                    role = Role.Button,
                    onClick = onClick,
                ).semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(space.closeChip).background(colors.closeChip, FolioTheme.shapes.full), contentAlignment = Alignment.Center) {
            Icon(FolioIcons.X, contentDescription = null, modifier = Modifier.size(CLOSE_GLYPH), tint = colors.closeChipGlyph)
        }
    }
}
