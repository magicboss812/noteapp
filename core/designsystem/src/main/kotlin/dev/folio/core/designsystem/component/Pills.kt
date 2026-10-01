package dev.folio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.movableContentOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.designsystem.theme.folioShadow
import kotlinx.collections.immutable.ImmutableList

private val WIDTH_GLYPH_LENGTH = 20.dp

/** Layout direction of a [PillGroup]. */
enum class PillOrientation { Horizontal, Vertical }

/**
 * Toolbar pill: 44 dp stadium in `surfaceToolbar` with a 1 dp border; the toolbar shadow shows in the light theme only
 * (11-design-system.md#components).
 */
@Composable
fun PillGroup(
    modifier: Modifier = Modifier,
    orientation: PillOrientation = PillOrientation.Horizontal,
    content: @Composable () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.stadium
    val pill =
        modifier
            .then(if (colors.isDark) Modifier else Modifier.folioShadow(FolioTheme.elevation.toolbar, shape, colors.shadow))
            .background(colors.surfaceToolbar, shape)
            .border(space.borderWidth, colors.border, shape)
            .padding(
                horizontal = if (orientation == PillOrientation.Horizontal) space.s4 else 0.dp,
                vertical = if (orientation == PillOrientation.Vertical) space.s4 else 0.dp,
            )
    // Movable so control state survives a dock change that flips the orientation (P04-T04).
    val controls = remember(content) { movableContentOf(content) }
    when (orientation) {
        PillOrientation.Horizontal -> Row(pill, verticalAlignment = Alignment.CenterVertically) { controls() }
        PillOrientation.Vertical -> Column(pill, horizontalAlignment = Alignment.CenterHorizontally) { controls() }
    }
}

/** 2 x 26 dp separator between control groups of a pill; [vertical] turns it for side rails. */
@Composable
fun PillDivider(
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val space = FolioTheme.space
    val size =
        if (vertical) {
            Modifier.size(
                space.dividerLength,
                space.dividerWidth,
            )
        } else {
            Modifier.size(space.dividerWidth, space.dividerLength)
        }
    Box(
        modifier
            .padding(horizontal = if (vertical) 0.dp else space.s4, vertical = if (vertical) space.s4 else 0.dp)
            .then(size)
            .background(FolioTheme.colors.divider, FolioTheme.shapes.stadium),
    )
}

/** Ink color swatch: 22 dp dot with a 4 dp accent dot below when selected; [onLongClick] (edit the color) is optional. */
@Composable
fun ColorDot(
    color: Color,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.full
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .selectableOption(selected, space.toolbarCell / 2, onClick, onLongClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(space.colorDot).background(color, shape).border(space.borderWidth, colors.border, shape))
        SelectionDot(selected)
    }
}

/**
 * Stroke width preset drawn as a bar of [strokeWidth]; selected shows a 4 dp accent dot below, or a 2 dp accent ring
 * around the cell when [ring] (eraser sizes). [onLongClick] (edit the preset) is optional.
 */
@Composable
fun WidthChip(
    strokeWidth: Dp,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ring: Boolean = false,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val ringColor by animateColorAsState(
        if (ring && selected) colors.accent else Color.Transparent,
        FolioTheme.motion.fast(),
        label = "widthRing",
    )
    val line = colors.iconToolbar
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .selectableOption(selected, space.toolbarCell / 2, onClick, onLongClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(space.toolbarCell).border(space.selectedBorderWidth, ringColor, FolioTheme.shapes.full),
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(WIDTH_GLYPH_LENGTH, space.iconToolbar)) {
                val y = size.height / 2
                val inset = strokeWidth.toPx() / 2
                drawLine(line, Offset(inset, y), Offset(size.width - inset, y), strokeWidth.toPx(), StrokeCap.Round)
            }
        }
        if (!ring) SelectionDot(selected)
    }
}

// The 4 dp accent dot under a selected width or color cell.
@Composable
private fun BoxScope.SelectionDot(selected: Boolean) {
    val dot by animateColorAsState(
        if (selected) FolioTheme.colors.accent else Color.Transparent,
        FolioTheme.motion.fast(),
        label = "selectionDot",
    )
    Box(
        Modifier
            .align(Alignment.BottomCenter)
            .padding(bottom = FolioTheme.space.s2)
            .size(FolioTheme.space.indicatorDot)
            .background(dot, FolioTheme.shapes.full),
    )
}

// One option of a radio group with an unbounded ripple of [rippleRadius]; a long press needs combinedClickable.
private fun Modifier.selectableOption(
    selected: Boolean,
    rippleRadius: Dp,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
): Modifier =
    if (onLongClick == null) {
        selectable(
            selected = selected,
            interactionSource = null,
            indication = ripple(bounded = false, radius = rippleRadius),
            role = Role.RadioButton,
            onClick = onClick,
        )
    } else {
        combinedClickable(
            interactionSource = null,
            indication = ripple(bounded = false, radius = rippleRadius),
            role = Role.RadioButton,
            onLongClick = onLongClick,
            onClick = onClick,
        ).semantics { this.selected = selected }
    }

/**
 * Segmented filter chips: 36 dp stadiums in a 44 dp target; the selected chip gets `accentContainer` and an accent
 * label. [vertical] stacks them (side-docked toolbar rails).
 */
@Composable
fun SegmentedTabs(
    tabs: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val space = FolioTheme.space
    val chips: @Composable () -> Unit = {
        tabs.forEachIndexed { index, label ->
            SegmentedTab(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
    if (vertical) {
        Column(modifier.selectableGroup(), horizontalAlignment = Alignment.CenterHorizontally) { chips() }
    } else {
        Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(space.s4)) { chips() }
    }
}

@Composable
private fun SegmentedTab(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val motion = FolioTheme.motion
    val background by animateColorAsState(if (selected) colors.accentContainer else Color.Transparent, motion.fast(), label = "tabBg")
    val text by animateColorAsState(if (selected) colors.accent else colors.textSecondary, motion.fast(), label = "tabText")
    Box(
        modifier =
            Modifier
                .height(space.touchTarget)
                .padding(vertical = (space.touchTarget - space.toolbarCell) / 2)
                .clip(FolioTheme.shapes.stadium)
                .background(background)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = space.s16),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = FolioTheme.type.labelSmall, color = text)
    }
}
