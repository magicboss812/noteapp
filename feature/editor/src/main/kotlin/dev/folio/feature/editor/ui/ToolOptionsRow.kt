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
import dev.folio.core.designsystem.component.FolioButton
import dev.folio.core.designsystem.component.FolioButtonStyle
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
import dev.folio.core.text.fonts.BodySize
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.LassoMode
import dev.folio.feature.editor.state.LassoOptions
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.TextOptions
import dev.folio.feature.editor.state.ToolOptions
import dev.folio.feature.editor.state.hasSettings
import kotlinx.collections.immutable.persistentListOf

private val OPTIONS_SLIDE = 8.dp

/** Gap between the toolbox and a popover (DESIGN.md section 7: ~6 dp from row 2). */
internal val POPOVER_GAP = 6.dp

/** Names of the three width presets. */
internal val WIDTH_LABELS = listOf("Small", "Medium", "Large")
private val ERASER_MODES = persistentListOf("Stroke", "Partial")

/** Labels of the body size presets S, M, L ([BodySize] order). */
internal val SIZE_LABELS = persistentListOf("S", "M", "L")

/** Edits the tool options: receives the change to apply to the current options. */
internal typealias OptionsChange = ((ToolOptions) -> ToolOptions) -> Unit

/** Whether [this] tool has a row 2 (10-editor-ui.md#tool-options): the tools with a settings panel and the Image tool. */
internal val EditorTool.hasOptions: Boolean
    get() = hasSettings || this == EditorTool.IMAGE

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
 * Toolbar row 2 (10-editor-ui.md#tool-options): the options pill of [tool] (mode or kind | widths | colors |
 * details), floating over the canvas. It scrolls with fade edges when it does not fit. Tapping the selected
 * width or color again (or a long-press) edits it; the details button calls [onSettings], which toggles the
 * tool settings panel like tapping the selected tool again. [vertical] lays it out as the inner rail of a
 * side-docked toolbar (10-editor-ui.md#toolbar-docking). [pillModifier] places the pill itself, so the space
 * around it stays free for canvas touches.
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
    onSettings: (EditorTool) -> Unit = {},
    onImage: (ImageSourceKind) -> Unit = {},
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
                    ToolContent(shown, options, onChange, onPopover, vertical, { onSettings(shown) }, onImage)
                    if (shown.hasSettings) {
                        Divider(vertical)
                        FolioIconButton(FolioIcons.SlidersHorizontal, "${shown.label} settings", onClick = { onSettings(shown) })
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

// The pill items of [tool] before its details button.
@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun ToolContent(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    vertical: Boolean,
    onFonts: () -> Unit,
    onImage: (ImageSourceKind) -> Unit,
) {
    when (tool) {
        EditorTool.PEN, EditorTool.HIGHLIGHTER -> StrokeOptionsContent(tool, options, onChange, onPopover, vertical)
        EditorTool.LASSO -> LassoOptionsContent(options.lasso, onChange)
        EditorTool.TEXT -> TextOptionsContent(options.text, onChange, onFonts, vertical)
        EditorTool.SHAPE -> ShapeOptionsContent(options, onChange, onPopover, vertical)
        EditorTool.IMAGE -> ImageOptionsContent(onImage, vertical)
        else -> EraserOptionsContent(options.eraser, onChange, vertical)
    }
}

// Pen and highlighter: widths | colors.
@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun StrokeOptionsContent(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    vertical: Boolean,
) {
    WidthPresetChips(tool, options, onChange, onPopover)
    Divider(vertical)
    ColorDots(tool, options, onChange, onPopover)
}

@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun EraserOptionsContent(
    eraser: EraserOptions,
    onChange: OptionsChange,
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
}

// Font family (opens the panel's picker) | body size presets; a free size from the panel selects none.
@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun TextOptionsContent(
    text: TextOptions,
    onChange: OptionsChange,
    onFonts: () -> Unit,
    vertical: Boolean,
) {
    if (vertical) {
        FolioIconButton(FolioIcons.Type, "Font ${text.fontFamily}", onClick = onFonts)
    } else {
        FolioButton(label = text.fontFamily, onClick = onFonts, style = FolioButtonStyle.Text)
    }
    Divider(vertical)
    SegmentedTabs(
        tabs = SIZE_LABELS,
        selectedIndex = text.preset?.ordinal ?: -1,
        onSelect = { index -> onChange { it.copy(text = it.text.withBodyCap(BodySize.entries[index].capFraction)) } },
        vertical = vertical,
    )
}

// Kind | outline widths | colors; dashes, fill and the constraint live in the panel (A-064).
@Suppress("MultipleEmitters") // Pill items for the caller's Row or Column, which differ by dock mode.
@Composable
private fun ShapeOptionsContent(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    vertical: Boolean,
) {
    ShapeKindButtons(options.shape.kind) { kind -> onChange { it.copy(shape = it.shape.copy(kind = kind)) } }
    Divider(vertical)
    WidthPresetChips(EditorTool.SHAPE, options, onChange, onPopover)
    Divider(vertical)
    ColorDots(EditorTool.SHAPE, options, onChange, onPopover)
}

@Composable
private fun LassoOptionsContent(
    lasso: LassoOptions,
    onChange: OptionsChange,
) {
    LassoMode.entries.forEach { mode ->
        ToolButton(
            icon = if (mode == LassoMode.FREEFORM) FolioIcons.Lasso else FolioIcons.Square,
            contentDescription = if (mode == LassoMode.FREEFORM) "Freeform lasso" else "Rectangle lasso",
            selected = mode == lasso.mode,
            onClick = { onChange { it.copy(lasso = it.lasso.copy(mode = mode)) } },
        )
    }
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
        val editor = OptionsPopover.WidthEditor(tool, index)
        WidthChip(
            strokeWidth = WidthScale.glyph(widthPt, presets.widthsPt[1]),
            contentDescription = "${WIDTH_LABELS[index]} width",
            selected = index == presets.selected,
            // Tapping the selected preset again edits it (DESIGN.md section 7, popover).
            onClick = {
                if (index == presets.selected) onPopover(editor) else onChange { it.withWidths(tool, it.widths(tool).select(index)) }
            },
            onLongClick = { onPopover(editor) },
        )
    }
}

@Composable
internal fun ColorDots(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    val swatches = options.swatches(tool)
    swatches.colors.forEachIndexed { index, argb ->
        val picker = OptionsPopover.ColorPicker(tool, index)
        ColorDot(
            color = Color(argb),
            contentDescription = "Color ${index + 1} #${hexOf(argb)}",
            selected = index == swatches.selected,
            // Tapping the selected color again edits it (DESIGN.md section 7, popover).
            onClick = {
                if (index == swatches.selected) onPopover(picker) else onChange { it.withSwatches(tool, it.swatches(tool).select(index)) }
            },
            onLongClick = { onPopover(picker) },
        )
    }
    FolioIconButton(FolioIcons.CirclePlus, "Add color", onClick = { onPopover(OptionsPopover.ColorPicker(tool, null)) })
}

@Composable
private fun Divider(vertical: Boolean) = PillDivider(vertical = vertical)

internal val BrushKind.icon
    get() =
        when (this) {
            BrushKind.BALLPOINT -> FolioIcons.Pen
            BrushKind.FOUNTAIN -> FolioIcons.PenTool
            BrushKind.PENCIL -> FolioIcons.Pencil
            BrushKind.MARKER -> FolioIcons.Brush
            BrushKind.HIGHLIGHTER -> FolioIcons.Highlighter
        }

internal val BrushKind.label: String
    get() =
        when (this) {
            BrushKind.BALLPOINT -> "Ballpoint pen"
            BrushKind.FOUNTAIN -> "Fountain pen"
            BrushKind.PENCIL -> "Pencil"
            BrushKind.MARKER -> "Marker"
            BrushKind.HIGHLIGHTER -> "Highlighter"
        }
