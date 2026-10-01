package dev.folio.feature.editor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.ColorDot
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillGroup
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
private val DIVIDER_HEIGHT = 24.dp

/** Row 2 pill height (10-editor-ui.md#screen-structure) plus its top gap: popovers open below it. */
internal val POPOVER_TOP = 72.dp

/** Names of the three width presets. */
internal val WIDTH_LABELS = listOf("Small", "Medium", "Large")
private val ERASER_MODES = persistentListOf("Stroke", "Partial")

/** Edits the tool options: receives the change to apply to the current options. */
internal typealias OptionsChange = ((ToolOptions) -> ToolOptions) -> Unit

/** Whether [this] tool has a row 2 (10-editor-ui.md#tool-options). */
internal val EditorTool.hasOptions: Boolean
    get() = this == EditorTool.PEN || this == EditorTool.HIGHLIGHTER || this == EditorTool.ERASER

/**
 * Toolbar row 2 (10-editor-ui.md#tool-options): the options pill of [tool], floating over the canvas. It
 * scrolls horizontally with fade edges when it does not fit. Long-press a width or color to edit it.
 */
@Composable
internal fun ToolOptionsRow(
    tool: EditorTool,
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = FolioTheme.motion
    val space = FolioTheme.space
    val slidePx = with(LocalDensity.current) { OPTIONS_SLIDE.roundToPx() }
    AnimatedContent(
        targetState = tool,
        modifier = modifier,
        transitionSpec = {
            (
                (fadeIn(motion.standard()) + slideInHorizontally(motion.standard()) { slidePx }) togetherWith
                    (fadeOut(motion.standard()) + slideOutHorizontally(motion.standard()) { -slidePx })
            ).using(SizeTransform(clip = false))
        },
        contentAlignment = Alignment.TopCenter,
        label = "optionsRow",
    ) { shown ->
        if (shown.hasOptions) {
            // The scroll clips inside the pill, so the floating shadow stays whole and only the pill takes touches.
            PillGroup(Modifier.padding(horizontal = space.s16).padding(top = space.s8)) {
                val scroll = rememberScrollState()
                Row(
                    Modifier.horizontalFade(scroll, SCROLL_FADE).horizontalScroll(scroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    when (shown) {
                        EditorTool.PEN -> PenOptionsContent(options, onChange, onPopover)
                        EditorTool.HIGHLIGHTER -> HighlighterOptionsContent(options, onChange, onPopover)
                        else -> EraserOptionsContent(options.eraser, onChange, onPopover)
                    }
                }
            }
        }
    }
}

@Composable
private fun RowScope.PenOptionsContent(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
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
    Divider()
    WidthPresetChips(EditorTool.PEN, options, onChange, onPopover)
    Divider()
    ColorDots(EditorTool.PEN, options, onChange, onPopover)
    FolioIconButton(FolioIcons.SlidersHorizontal, "Pen settings", onClick = { onPopover(OptionsPopover.PenSettings) })
}

@Composable
private fun RowScope.HighlighterOptionsContent(
    options: ToolOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    WidthPresetChips(EditorTool.HIGHLIGHTER, options, onChange, onPopover)
    Divider()
    ColorDots(EditorTool.HIGHLIGHTER, options, onChange, onPopover)
    Divider()
    ToolButton(
        icon = FolioIcons.Minus,
        contentDescription = "Always straight",
        selected = options.highlighter.alwaysStraight,
        onClick = { onChange { it.copy(highlighter = it.highlighter.copy(alwaysStraight = !it.highlighter.alwaysStraight)) } },
    )
}

@Composable
private fun RowScope.EraserOptionsContent(
    eraser: EraserOptions,
    onChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
) {
    SegmentedTabs(
        tabs = ERASER_MODES,
        selectedIndex = eraser.mode.ordinal,
        onSelect = { index -> onChange { it.copy(eraser = it.eraser.copy(mode = EraserMode.entries[index])) } },
    )
    Divider()
    EraserOptions.SIZES_PT.forEachIndexed { index, radiusPt ->
        WidthChip(
            strokeWidth = WidthScale.glyph(radiusPt, EraserOptions.SIZES_PT[1]),
            contentDescription = "${WIDTH_LABELS[index]} eraser",
            selected = radiusPt == eraser.radiusPt,
            onClick = { onChange { it.copy(eraser = it.eraser.copy(radiusPt = radiusPt)) } },
        )
    }
    Divider()
    ToolButton(
        icon = FolioIcons.Highlighter,
        contentDescription = "Erase highlighter only",
        selected = eraser.highlighterOnly,
        onClick = { onChange { it.copy(eraser = it.eraser.copy(highlighterOnly = !it.eraser.highlighterOnly)) } },
    )
    FolioIconButton(FolioIcons.BrushCleaning, "Clear page", onClick = { onPopover(OptionsPopover.ClearPage) })
}

@Composable
private fun RowScope.WidthPresetChips(
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
private fun RowScope.ColorDots(
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
private fun Divider() {
    val space = FolioTheme.space
    Box(
        Modifier
            .padding(horizontal = space.s4)
            .size(space.borderWidth, DIVIDER_HEIGHT)
            .background(FolioTheme.colors.border),
    )
}

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
