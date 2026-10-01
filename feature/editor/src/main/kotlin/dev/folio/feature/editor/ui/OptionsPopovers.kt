package dev.folio.feature.editor.ui

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioDialog
import dev.folio.core.designsystem.component.FolioPopover
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.ink.brush.PressureCurve
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.ToolOptions
import java.util.Locale

// Flyouts next to a side rail start level with the options rail (its top gap).
private val POPOVER_RAIL_TOP = 8.dp

// Slider stops between the pressure curve ends (0.05 apart).
private val GAMMA_STEPS = ((PressureCurve.MAX_GAMMA - PressureCurve.MIN_GAMMA) * PressureCurve.GAMMA_STEPS_PER_UNIT).toInt() - 1

/**
 * Where options popovers open: below row 2 when docked top, as a flyout next to the options rail when
 * docked to a side, in the canvas center when floating (10-editor-ui.md#toolbar-docking).
 */
internal enum class PopoverAnchor(
    val alignment: Alignment,
    val padding: PaddingValues,
) {
    BelowRow(Alignment.TopCenter, PaddingValues(top = POPOVER_TOP)),
    RightOfRail(Alignment.TopStart, PaddingValues(start = POPOVER_TOP, top = POPOVER_RAIL_TOP)),
    LeftOfRail(Alignment.TopEnd, PaddingValues(end = POPOVER_TOP, top = POPOVER_RAIL_TOP)),
    Center(Alignment.Center, PaddingValues()),
}

/**
 * Popovers of the options row (10-editor-ui.md#tool-options) at [anchor]; a tap outside closes them and
 * the canvas takes no input meanwhile. "Clear page" asks in a dialog.
 */
@Composable
internal fun OptionsPopoverLayer(
    popover: OptionsPopover?,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    onClearPage: () -> Unit,
    modifier: Modifier = Modifier,
    anchor: PopoverAnchor = PopoverAnchor.BelowRow,
) {
    val close by rememberUpdatedState { onPopover(null) }
    when (popover) {
        null -> {
            Unit
        }

        OptionsPopover.ClearPage -> {
            FolioDialog(
                title = "Clear this page?",
                confirmLabel = "Clear",
                onConfirm = {
                    onClearPage()
                    close()
                },
                onDismissRequest = close,
                text =
                    if (options.eraser.highlighterOnly) {
                        "Removes the highlighter ink on this page. Undo brings it back."
                    } else {
                        "Removes all ink on this page. Undo brings it back."
                    },
                destructive = true,
            )
        }

        else -> {
            Box(
                modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { close() } },
                contentAlignment = anchor.alignment,
            ) {
                val card = Modifier.padding(anchor.padding)
                when (popover) {
                    OptionsPopover.PenSettings -> {
                        PenSettingsPopover(options, onChange, card)
                    }

                    is OptionsPopover.WidthEditor -> {
                        WidthEditorPopover(popover.tool, popover.index, options, onChange, card)
                    }

                    is OptionsPopover.ColorPicker -> {
                        ColorPickerPopover(popover, options, onChange, close, card)
                    }

                    OptionsPopover.ClearPage -> {
                        Unit
                    }
                }
            }
        }
    }
}

/** Pen settings: pressure curve and the selected width preset. Tilt has no switch yet (D-014). */
@Composable
internal fun PenSettingsPopover(
    options: ToolOptions,
    onChange: OptionsChange,
    modifier: Modifier = Modifier,
) {
    val pen = options.pen
    PopoverCard("Pen settings", modifier) {
        LabeledSlider(
            label = "Pressure curve",
            valueText = "%.2f".format(Locale.ROOT, pen.pressureGamma),
            value = pen.pressureGamma,
            onValueChange = { gamma -> onChange { it.copy(pen = it.pen.withPressureGamma(gamma)) } },
            valueRange = PressureCurve.MIN_GAMMA..PressureCurve.MAX_GAMMA,
            steps = GAMMA_STEPS,
        )
        Text(
            text = "Below 1, light pressure already draws thick lines. Above 1, press harder for thick lines.",
            style = FolioTheme.type.caption,
            color = FolioTheme.colors.textSecondary,
        )
        WidthSlider("Width", pen.kindWidths.widthPt) { widthPt ->
            onChange { it.withWidths(EditorTool.PEN, it.widths(EditorTool.PEN).run { set(selected, widthPt) }) }
        }
    }
}

/** Edits width preset [index] of [tool] (long-press on a width preset) and selects it. */
@Composable
internal fun WidthEditorPopover(
    tool: EditorTool,
    index: Int,
    options: ToolOptions,
    onChange: OptionsChange,
    modifier: Modifier = Modifier,
) {
    val widthPt = options.widths(tool).widthsPt.getOrNull(index) ?: return
    PopoverCard("${WIDTH_LABELS[index]} width", modifier) {
        WidthSlider("Width", widthPt) { next -> onChange { it.withWidths(tool, it.widths(tool).set(index, next).select(index)) } }
    }
}

/** Card of an options popover ([FolioPopover]); taps on it never reach the close-on-tap layer behind it. */
@Composable
internal fun PopoverCard(
    title: String,
    modifier: Modifier = Modifier,
    width: Dp = FolioTheme.space.popoverWidth,
    content: @Composable ColumnScope.() -> Unit,
) {
    FolioPopover(title, modifier.pointerInput(Unit) { detectTapGestures {} }, width = width, content = content)
}

@Composable
private fun WidthSlider(
    label: String,
    widthPt: Float,
    onWidth: (Float) -> Unit,
) {
    LabeledSlider(
        label = label,
        valueText = "%.2f pt".format(Locale.ROOT, widthPt),
        value = WidthScale.fraction(widthPt),
        onValueChange = { onWidth(WidthScale.widthPt(it)) },
    )
}

@Composable
private fun LabeledSlider(
    label: String,
    valueText: String,
    value: Float,
    onValueChange: (Float) -> Unit,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
) {
    val colors = FolioTheme.colors
    Column {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(text = label, style = FolioTheme.type.label, color = colors.textPrimary)
            Text(text = valueText, style = FolioTheme.type.label, color = colors.textSecondary)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            colors =
                SliderDefaults.colors(
                    thumbColor = colors.accent,
                    activeTrackColor = colors.accent,
                    inactiveTrackColor = colors.accentTrack,
                    activeTickColor = Color.Transparent,
                    inactiveTickColor = Color.Transparent,
                ),
            modifier = Modifier.semantics { contentDescription = label },
        )
    }
}
