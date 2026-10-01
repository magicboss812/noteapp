package dev.folio.core.designsystem.component

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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

private val DOT_SIZE = 24.dp
private val DOT_RING_SIZE = 32.dp
private val DOT_RING_WIDTH = 2.dp
private val WIDTH_GLYPH_LENGTH = 18.dp

/** Layout direction of a [PillGroup]. */
enum class PillOrientation { Horizontal, Vertical }

/**
 * Rounded group of toolbar controls. Floating pills cast the floating shadow and carry a border in light mode only
 * (11-design-system.md#elevation).
 */
@Composable
fun PillGroup(
    modifier: Modifier = Modifier,
    orientation: PillOrientation = PillOrientation.Horizontal,
    floating: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.m
    val pill =
        modifier
            .then(if (floating) Modifier.folioShadow(FolioTheme.elevation.floating, shape, colors.shadow) else Modifier)
            .background(colors.surfaceMuted, shape)
            .then(if (floating && !colors.isDark) Modifier.border(space.borderWidth, colors.border, shape) else Modifier)
            .padding(space.s2)
    // Movable so control state survives a dock change that flips the orientation (P04-T04).
    val controls = remember(content) { movableContentOf(content) }
    when (orientation) {
        PillOrientation.Horizontal -> Row(pill, verticalAlignment = Alignment.CenterVertically) { controls() }
        PillOrientation.Vertical -> Column(pill, horizontalAlignment = Alignment.CenterHorizontally) { controls() }
    }
}

/** Ink color swatch with an accent ring when selected; [onLongClick] (edit the color) is optional. */
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
    val ring by animateColorAsState(if (selected) colors.accent else Color.Transparent, FolioTheme.motion.fast(), label = "dotRing")
    val shape = FolioTheme.shapes.full
    Box(
        modifier =
            modifier
                .size(FolioTheme.space.touchTarget)
                .selectableOption(selected, DOT_RING_SIZE / 2, onClick, onLongClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(DOT_RING_SIZE).border(DOT_RING_WIDTH, ring, shape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(DOT_SIZE).background(color, shape).border(FolioTheme.space.borderWidth, colors.border, shape))
        }
    }
}

/** Stroke width preset drawn as a horizontal line of [strokeWidth]; [onLongClick] (edit the preset) is optional. */
@Composable
fun WidthChip(
    strokeWidth: Dp,
    contentDescription: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val motion = FolioTheme.motion
    val background by animateColorAsState(if (selected) colors.accentSoft else Color.Transparent, motion.fast(), label = "widthBg")
    val line by animateColorAsState(if (selected) colors.accent else colors.textPrimary, motion.fast(), label = "widthLine")
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .selectableOption(selected, space.buttonVisual / 2, onClick, onLongClick)
                .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(space.buttonVisual).background(background, FolioTheme.shapes.s), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(WIDTH_GLYPH_LENGTH, space.iconToolbar)) {
                val y = size.height / 2
                val inset = strokeWidth.toPx() / 2
                drawLine(line, Offset(inset, y), Offset(size.width - inset, y), strokeWidth.toPx(), StrokeCap.Round)
            }
        }
    }
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

/** Segmented chips (library tabs): the selected chip gets the accentSoft background and accent text. */
@Composable
fun SegmentedTabs(
    tabs: ImmutableList<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = FolioTheme.space
    Row(modifier.selectableGroup(), horizontalArrangement = Arrangement.spacedBy(space.s4)) {
        tabs.forEachIndexed { index, label ->
            SegmentedTab(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
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
    val background by animateColorAsState(if (selected) colors.accentSoft else Color.Transparent, motion.fast(), label = "tabBg")
    val text by animateColorAsState(if (selected) colors.accent else colors.textSecondary, motion.fast(), label = "tabText")
    Box(
        modifier =
            Modifier
                .height(space.touchTarget)
                .padding(vertical = space.s2)
                .clip(FolioTheme.shapes.s)
                .background(background)
                .selectable(selected = selected, role = Role.Tab, onClick = onClick)
                .padding(horizontal = space.s16),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = label, style = FolioTheme.type.label, color = text)
    }
}
