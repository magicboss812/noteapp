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

// Eraser radius slider range and step (pt); the row 2 sizes sit inside it.
private const val ERASER_MIN_PT = 2f
private const val ERASER_MAX_PT = 32f
private val ERASER_STEPS = (ERASER_MAX_PT - ERASER_MIN_PT).toInt() - 1

/**
 * Tool settings panel of [tool] (10-editor-ui.md#tool-options): everything row 2 leaves out. Centered title
 * with a close chip; the content scrolls when the panel is taller than the room beside the toolbox. Colors come
 * last; "Clear page" asks in a dialog ([OptionsPopover.ClearPage]).
 */
@Composable
internal fun ToolSettingsPanel(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    modifier: Modifier = Modifier,
) {
    PopoverCard(tool.label, modifier, onClose = { onPopover(null) }) {
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s12),
        ) {
            when (tool) {
                EditorTool.PEN -> PenSettings(options, onChange, onPopover)
                EditorTool.HIGHLIGHTER -> HighlighterSettings(options, onChange, onPopover)
                EditorTool.ERASER -> EraserSettings(options.eraser, onChange, onPopover)
                EditorTool.LASSO -> LassoSettings(options.lasso, onChange)
                else -> Unit
            }
        }
    }
}

@Suppress("MultipleEmitters") // Sections of the panel's scrolling column.
@Composable
private fun PenSettings(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    val pen = options.pen
    StrokePreview(Color(pen.swatches.argb), pen.kindWidths.widthPt, highlighter = false)
    Row(horizontalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
        PEN_KINDS.forEach { kind ->
            KindCard(
                kind,
                selected = kind == pen.kind,
                onClick = { onChange { it.copy(pen = it.pen.copy(kind = kind)) } },
                Modifier.weight(1f),
            )
        }
    }
    WidthSlider("Width", pen.kindWidths.widthPt) { widthPt ->
        onChange { it.withWidths(EditorTool.PEN, it.widths(EditorTool.PEN).run { set(selected, widthPt) }) }
    }
    PressureSlider(pen.pressureGamma) { gamma -> onChange { it.copy(pen = it.pen.withPressureGamma(gamma)) } }
    PanelDivider()
    ColorSection(EditorTool.PEN, options, onChange, onPopover)
}

@Suppress("MultipleEmitters") // Sections of the panel's scrolling column.
@Composable
private fun HighlighterSettings(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    val highlighter = options.highlighter
    StrokePreview(Color(highlighter.swatches.argb), highlighter.widths.widthPt, highlighter = true)
    WidthSlider("Width", highlighter.widths.widthPt) { widthPt ->
        onChange { it.withWidths(EditorTool.HIGHLIGHTER, it.widths(EditorTool.HIGHLIGHTER).run { set(selected, widthPt) }) }
    }
    PanelDivider()
    SwitchRow("Always straight", highlighter.alwaysStraight) { on ->
        onChange { it.copy(highlighter = it.highlighter.copy(alwaysStraight = on)) }
    }
    PanelDivider()
    ColorSection(EditorTool.HIGHLIGHTER, options, onChange, onPopover)
}

@Suppress("MultipleEmitters") // Sections of the panel's scrolling column.
@Composable
private fun EraserSettings(
    eraser: EraserOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    LabeledSlider(
        label = "Size",
        valueText = "%.0f pt".format(Locale.ROOT, eraser.radiusPt),
        value = eraser.radiusPt.coerceIn(ERASER_MIN_PT, ERASER_MAX_PT),
        onValueChange = { radius -> onChange { it.copy(eraser = it.eraser.copy(radiusPt = radius.roundToInt().toFloat())) } },
        valueRange = ERASER_MIN_PT..ERASER_MAX_PT,
        steps = ERASER_STEPS,
    )
    PanelDivider()
    SwitchRow("Erase highlighter only", eraser.highlighterOnly) { on ->
        onChange { it.copy(eraser = it.eraser.copy(highlighterOnly = on)) }
    }
    PanelDivider()
    FolioButton(
        label = "Clear page",
        onClick = { onPopover(OptionsPopover.ClearPage) },
        style = FolioButtonStyle.Text,
        destructive = true,
    )
}

@Suppress("MultipleEmitters") // Sections of the panel's scrolling column.
@Composable
private fun LassoSettings(
    lasso: LassoOptions,
    onChange: OptionsChange,
) {
    SectionLabel("Selects")
    FlowRow {
        LassoFilter.entries.forEach { filter ->
            FilterChip(filter.label, selected = filter in lasso.filters) { onChange { it.copy(lasso = it.lasso.toggle(filter)) } }
        }
    }
}

/** Pressure curve slider with its explanation (shared by the pen panel). */
@Suppress("MultipleEmitters") // Slider and caption for the caller's column.
@Composable
private fun PressureSlider(
    gamma: Float,
    onGamma: (Float) -> Unit,
) {
    LabeledSlider(
        label = "Pressure curve",
        valueText = "%.2f".format(Locale.ROOT, gamma),
        value = gamma,
        onValueChange = onGamma,
        valueRange = PressureCurve.MIN_GAMMA..PressureCurve.MAX_GAMMA,
        steps = GAMMA_STEPS,
    )
    Text(
        text = "Below 1, light pressure already draws thick lines. Above 1, press harder for thick lines.",
        style = FolioTheme.type.caption,
        color = FolioTheme.colors.textSecondary,
    )
}

// Colors last (DESIGN.md section 7): the tool's dots and the add button, wrapping.
@Suppress("MultipleEmitters") // Label and dots for the caller's column.
@Composable
private fun ColorSection(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    SectionLabel("Colors")
    FlowRow(verticalArrangement = Arrangement.Center) { ColorDots(tool, options, onChange, onPopover) }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = FolioTheme.type.label, color = FolioTheme.colors.textSecondary)
}

@Composable
private fun PanelDivider() {
    HorizontalDivider(thickness = FolioTheme.space.borderWidth, color = FolioTheme.colors.divider)
}

private val LassoFilter.label: String
    get() =
        when (this) {
            LassoFilter.INK -> "Ink"
            LassoFilter.SHAPES -> "Shapes"
            LassoFilter.TEXT -> "Text"
            LassoFilter.IMAGES -> "Images"
        }
