package dev.folio.feature.editor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.ColorDot
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.PillOrientation
import dev.folio.core.designsystem.component.SegmentedTabs
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.component.WidthChip
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.PEN_KINDS
import dev.folio.feature.editor.state.ToolOptions
import kotlinx.collections.immutable.persistentListOf

private val OPTIONS_SLIDE = 8.dp

/** Row 2 pill (44 dp) plus the ~6 dp popover gap of DESIGN.md section 7: popovers open below (or beside) it. */
internal val POPOVER_TOP = 50.dp

/** Names of the three width presets. */
internal val WIDTH_LABELS = listOf("Small", "Medium", "Large")
private val ERASER_MODES = persistentListOf("Stroke", "Partial")

/** Edits the tool options: receives the change to apply to the current options. */
internal typealias OptionsChange = ((ToolOptions) -> ToolOptions) -> Unit

/** Whether [this] tool has a row 2 (10-editor-ui.md#tool-options). */
internal val EditorTool.hasOptions: Boolean
    get() = this == EditorTool.PEN || this == EditorTool.HIGHLIGHTER || this == EditorTool.ERASER

/** Row 2 left pill: undo and redo, disabled while there is no step (10-editor-ui.md#toolbar). */
@Composable
internal fun UndoRedoPill(
    canUndo: Boolean,
    canRedo: Boolean,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    PillGroup(modifier, orientation = if (vertical) PillOrientation.Vertical else PillOrientation.Horizontal) {
        FolioIconButton(FolioIcons.Undo2, "Undo", onUndo, enabled = canUndo)
        FolioIconButton(FolioIcons.Redo2, "Redo", onRedo, enabled = canRedo)
    }
}

/**
 * Toolbar row 2 (10-editor-ui.md#tool-options): the options pill of [tool], floating over the canvas. It
 * scrolls with fade edges when it does not fit. Long-press a width or color to edit it. [vertical] lays it
 * out as the inner rail of a side-docked toolbar (10-editor-ui.md#toolbar-docking). [pillModifier] places
 * the pill itself, so the space around it stays free for canvas touches.
 */
@Composable
internal fun ToolOptionsRow(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    modifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
    vertical: Boolean = false,
) {
    val motion = FolioTheme.motion
    val slidePx = with(LocalDensity.current) { OPTIONS_SLIDE.roundToPx() }
    AnimatedContent(
        targetState = tool,
        modifier = modifier,
        transitionSpec = {
            val enter =
                if (vertical) slideInVertically(motion.standard()) { slidePx } else slideInHorizontally(motion.standard()) { slidePx }
            val exit =
                if (vertical) slideOutVertically(motion.standard()) { -slidePx } else slideOutHorizontally(motion.standard()) { -slidePx }
            ((fadeIn(motion.standard()) + enter) togetherWith (fadeOut(motion.standard()) + exit)).using(SizeTransform(clip = false))
        },
        contentAlignment = if (vertical) Alignment.TopStart else Alignment.TopCenter,
        label = "optionsRow",
    ) { shown ->
        if (shown.hasOptions) {
            // The scroll clips inside the pill, so the floating shadow stays whole and only the pill takes touches.
            val orientation = if (vertical) PillOrientation.Vertical else PillOrientation.Horizontal
            PillGroup(pillModifier, orientation = orientation) {
                val scroll = rememberScrollState()
                val content: @Composable () -> Unit = {
                    when (shown) {
                        EditorTool.PEN -> PenOptionsContent(options, onChange, onPopover, vertical)
                        EditorTool.HIGHLIGHTER -> HighlighterOptionsContent(options, onChange, onPopover, vertical)
                        else -> EraserOptionsContent(options.eraser, onChange, onPopover, vertical)
                    }
                }
                if (vertical) {
                    Column(
                        Modifier.edgeFade(scroll, SCROLL_FADE, vertical = true).verticalScroll(scroll),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) { content() }
                } else {
                    Row(Modifier.edgeFade(scroll, SCROLL_FADE).horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                        content()
                    }
                }
            }
        }
    }
}

@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun PenOptionsContent(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    vertical: Boolean,
) {
    val pen = options.pen
    PEN_KINDS.forEach { kind ->
        ToolButton(
            icon = kind.icon,
            contentDescription = kind.label,
            selected = kind == pen.kind,
            onClick = { onChange { it.copy(pen = it.pen.copy(kind = kind)) } },
        )
    }
    Divider(vertical)
    WidthPresetChips(EditorTool.PEN, options, onChange, onPopover)
    Divider(vertical)
    ColorDots(EditorTool.PEN, options, onChange, onPopover)
    FolioIconButton(FolioIcons.SlidersHorizontal, "Pen settings", onClick = { onPopover(OptionsPopover.PenSettings) })
}

@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun HighlighterOptionsContent(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    vertical: Boolean,
) {
    WidthPresetChips(EditorTool.HIGHLIGHTER, options, onChange, onPopover)
    Divider(vertical)
    ColorDots(EditorTool.HIGHLIGHTER, options, onChange, onPopover)
    Divider(vertical)
    ToolButton(
        icon = FolioIcons.Minus,
        contentDescription = "Always straight",
        selected = options.highlighter.alwaysStraight,
        onClick = { onChange { it.copy(highlighter = it.highlighter.copy(alwaysStraight = !it.highlighter.alwaysStraight)) } },
    )
}

@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun EraserOptionsContent(
    eraser: EraserOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    vertical: Boolean,
) {
    SegmentedTabs(
        tabs = ERASER_MODES,
        selectedIndex = eraser.mode.ordinal,
        onSelect = { index -> onChange { it.copy(eraser = it.eraser.copy(mode = EraserMode.entries[index])) } },
        vertical = vertical,
    )
    Divider(vertical)
    EraserOptions.SIZES_PT.forEachIndexed { index, radiusPt ->
        WidthChip(
            strokeWidth = WidthScale.glyph(radiusPt, EraserOptions.SIZES_PT[1]),
            contentDescription = "${WIDTH_LABELS[index]} eraser",
            selected = radiusPt == eraser.radiusPt,
            onClick = { onChange { it.copy(eraser = it.eraser.copy(radiusPt = radiusPt)) } },
            ring = true,
        )
    }
    Divider(vertical)
    ToolButton(
        icon = FolioIcons.Highlighter,
        contentDescription = "Erase highlighter only",
        selected = eraser.highlighterOnly,
        onClick = { onChange { it.copy(eraser = it.eraser.copy(highlighterOnly = !it.eraser.highlighterOnly)) } },
    )
    FolioIconButton(FolioIcons.BrushCleaning, "Clear page", onClick = { onPopover(OptionsPopover.ClearPage) })
}

@Composable
private fun WidthPresetChips(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    val presets = options.widths(tool)
    presets.widthsPt.forEachIndexed { index, widthPt ->
        WidthChip(
            strokeWidth = WidthScale.glyph(widthPt, presets.widthsPt[1]),
            contentDescription = "${WIDTH_LABELS[index]} width",
            selected = index == presets.selected,
            onClick = { onChange { it.withWidths(tool, it.widths(tool).select(index)) } },
            onLongClick = { onPopover(OptionsPopover.WidthEditor(tool, index)) },
        )
    }
}

@Composable
private fun ColorDots(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    val swatches = options.swatches(tool)
    swatches.colors.forEachIndexed { index, argb ->
        ColorDot(
            color = Color(argb),
            contentDescription = "Color ${index + 1} #${hexOf(argb)}",
            selected = index == swatches.selected,
            onClick = { onChange { it.withSwatches(tool, it.swatches(tool).select(index)) } },
            onLongClick = { onPopover(OptionsPopover.ColorPicker(tool, index)) },
        )
    }
    FolioIconButton(FolioIcons.CirclePlus, "Add color", onClick = { onPopover(OptionsPopover.ColorPicker(tool, null)) })
}

@Composable
private fun Divider(vertical: Boolean) = PillDivider(vertical = vertical)

private val BrushKind.icon
    get() =
        when (this) {
            BrushKind.BALLPOINT -> FolioIcons.Pen
            BrushKind.FOUNTAIN -> FolioIcons.PenTool
            BrushKind.PENCIL -> FolioIcons.Pencil
            BrushKind.MARKER -> FolioIcons.Brush
            BrushKind.HIGHLIGHTER -> FolioIcons.Highlighter
        }

private val BrushKind.label: String
    get() =
        when (this) {
            BrushKind.BALLPOINT -> "Ballpoint pen"
            BrushKind.FOUNTAIN -> "Fountain pen"
            BrushKind.PENCIL -> "Pencil"
            BrushKind.MARKER -> "Marker"
            BrushKind.HIGHLIGHTER -> "Highlighter"
        }
