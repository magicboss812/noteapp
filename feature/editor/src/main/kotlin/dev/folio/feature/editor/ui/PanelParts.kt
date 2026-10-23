package dev.folio.feature.editor.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.ink.brush.PressureCurve
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.LassoFilter
import dev.folio.feature.editor.state.LassoOptions
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.PEN_KINDS
import dev.folio.feature.editor.state.ToolOptions
import java.util.Locale
import kotlin.math.roundToInt

// Tool settings panel parts (DESIGN.md section 7): stroke preview, kind cards, filter chips.
private val PREVIEW_HEIGHT = 90.dp
private val KIND_CARD_HEIGHT = 60.dp
private val FILTER_CHIP_HEIGHT = 36.dp

// Preview stroke thickness per pt of brush width (about the fit-width page on the tablet), clamped to the preview.
private val PREVIEW_DP_PER_PT = 2.dp
private val PREVIEW_MIN = 1.dp
private val PREVIEW_MAX = 32.dp
private const val HIGHLIGHTER_PREVIEW_ALPHA = 0.45f

// A sample stroke in the tool's color and width on paper.
@Composable
internal fun StrokePreview(
    color: Color,
    widthPt: Float,
    highlighter: Boolean,
) {
    val colors = FolioTheme.colors
    val thickness: Dp = (PREVIEW_DP_PER_PT * widthPt).coerceIn(PREVIEW_MIN, PREVIEW_MAX)
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(PREVIEW_HEIGHT)
            .clip(FolioTheme.shapes.tile)
            .background(colors.page)
            .border(FolioTheme.space.borderWidth, colors.border, FolioTheme.shapes.tile)
            .semantics { contentDescription = "Stroke preview" },
    ) {
        val inset = PREVIEW_MAX.toPx() / 2
        val w = size.width - 2 * inset
        val midY = size.height / 2
        val path =
            Path().apply {
                if (highlighter) {
                    moveTo(inset, midY)
                    lineTo(inset + w, midY)
                } else {
                    // One soft wave, like a handwritten stroke.
                    moveTo(inset, midY + size.height / 6)
                    cubicTo(
                        inset + w / 3,
                        midY - size.height / 2,
                        inset + 2 * w / 3,
                        midY + size.height / 2,
                        inset + w,
                        midY - size.height / 6,
                    )
                }
            }
        drawPath(
            path,
            color = if (highlighter) color.copy(alpha = HIGHLIGHTER_PREVIEW_ALPHA) else color,
            style = Stroke(width = thickness.toPx(), cap = if (highlighter) StrokeCap.Butt else StrokeCap.Round),
        )
    }
}

// Brush kind card: icon over label; selected = 2 dp accent border, accent tile fill, accent icon and label.
@Composable
internal fun KindCard(
    kind: BrushKind,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val shape = FolioTheme.shapes.tile
    val tint = if (selected) colors.accent else colors.textPrimary
    Column(
        modifier
            .height(KIND_CARD_HEIGHT)
            .clip(shape)
            .background(if (selected) colors.accentTile else Color.Transparent)
            .border(if (selected) space.selectedBorderWidth else space.borderWidth, if (selected) colors.accent else colors.border, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick)
            .semantics { contentDescription = kind.label },
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(kind.icon, contentDescription = null, tint = tint, modifier = Modifier.size(space.iconToolbar))
        Text(kind.shortLabel, style = FolioTheme.type.labelSmall, color = tint, maxLines = 1)
    }
}

// 44 dp setting row: label left, switch right; the whole row toggles.
@Composable
internal fun SwitchRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val colors = FolioTheme.colors
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = FolioTheme.space.touchTarget)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onCheckedChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = FolioTheme.type.body, color = colors.textPrimary, modifier = Modifier.weight(1f))
        Switch(
            checked = checked,
            onCheckedChange = null,
            colors =
                SwitchDefaults.colors(
                    checkedThumbColor = colors.onAccent,
                    checkedTrackColor = colors.accent,
                    checkedBorderColor = colors.accent,
                    uncheckedThumbColor = colors.textTertiary,
                    uncheckedTrackColor = colors.surfaceGroup,
                    uncheckedBorderColor = colors.textTertiary,
                ),
        )
    }
}

// Filter chip (DESIGN.md section 7): 36 dp stadium in a 44 dp target; only the selected one is filled.
@Composable
internal fun FilterChip(
    label: String,
    selected: Boolean,
    onToggle: () -> Unit,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    Box(
        Modifier
            .heightIn(min = space.touchTarget)
            .toggleable(value = selected, role = Role.Checkbox, onValueChange = { onToggle() }),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .height(FILTER_CHIP_HEIGHT)
                .clip(FolioTheme.shapes.stadium)
                .background(if (selected) colors.accentContainer else Color.Transparent)
                .padding(horizontal = space.s16),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = FolioTheme.type.label, color = if (selected) colors.accent else colors.textPrimary)
        }
    }
}

private val BrushKind.shortLabel: String
    get() =
        when (this) {
            BrushKind.BALLPOINT -> "Ballpoint"
            BrushKind.FOUNTAIN -> "Fountain"
            BrushKind.PENCIL -> "Pencil"
            BrushKind.MARKER -> "Marker"
            BrushKind.HIGHLIGHTER -> "Highlighter"
        }
