package dev.folio.feature.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.PillOrientation
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.EditorTool

/** Row 1 height: a 44 dp pill with 8 dp chrome insets (10-editor-ui.md#screen-structure), also the side rail width. */
internal val TOOLBAR_ROW1_HEIGHT = 60.dp
internal val SCROLL_FADE = 24.dp

/**
 * Toolbar row 1 (10-editor-ui.md#toolbar): document pill, tools pill, split view toggle and the [grip] that
 * moves the toolbar. [vertical] lays it out as a side rail (10-editor-ui.md#toolbar-docking). The tools pill
 * scrolls with fade edges when the window is too small. Buttons of features that arrive in later tasks
 * show disabled.
 */
@Composable
internal fun EditorToolbarRow1(
    tool: EditorTool,
    onHome: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    grip: @Composable () -> Unit = {},
) {
    val space = FolioTheme.space
    val orientation = if (vertical) PillOrientation.Vertical else PillOrientation.Horizontal
    val content: @Composable (Modifier) -> Unit = { toolsModifier ->
        PillGroup(orientation = orientation) {
            FolioIconButton(FolioIcons.House, "Home", onHome)
            FolioIconButton(FolioIcons.PanelLeft, "Pages", onClick = {}, enabled = false)
            FolioIconButton(FolioIcons.LayoutGrid, "Page overview", onClick = {}, enabled = false)
            FolioIconButton(FolioIcons.FilePlus, "Add page", onClick = {}, enabled = false)
            FolioIconButton(FolioIcons.Ellipsis, "More", onClick = {}, enabled = false)
        }
        val scroll = rememberScrollState()
        Box(toolsModifier, contentAlignment = Alignment.Center) {
            val scrolling =
                if (vertical) {
                    Modifier.edgeFade(scroll, SCROLL_FADE, vertical = true).verticalScroll(scroll)
                } else {
                    Modifier.edgeFade(scroll, SCROLL_FADE).horizontalScroll(scroll)
                }
            Box(scrolling) {
                PillGroup(orientation = orientation) { ToolsPill(tool, onSelectTool, vertical) }
            }
        }
        FolioIconButton(FolioIcons.Columns2, "Split view", onClick = {}, enabled = false)
        grip()
    }
    if (vertical) {
        Column(
            modifier = modifier.fillMaxHeight().width(TOOLBAR_ROW1_HEIGHT).padding(vertical = space.chromeInset),
            verticalArrangement = Arrangement.spacedBy(space.chromeInset),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) { content(Modifier.weight(1f)) }
    } else {
        Row(
            modifier = modifier.fillMaxWidth().height(TOOLBAR_ROW1_HEIGHT).padding(horizontal = space.chromeInset),
            horizontalArrangement = Arrangement.spacedBy(space.chromeInset),
            verticalAlignment = Alignment.CenterVertically,
        ) { content(Modifier.weight(1f)) }
    }
}

/** Tools of row 1; the lasso sits apart behind a divider like the fixed Notewise lasso (DESIGN.md section 7). */
@Composable
internal fun ToolsPill(
    selected: EditorTool,
    onSelectTool: (EditorTool) -> Unit,
    vertical: Boolean = false,
) {
    EditorTool.entries.forEach { tool ->
        if (tool == EditorTool.ATTACHMENT) {
            // The Ruler is a toggle between Sticky note and Attachment (P07).
            FolioIconButton(FolioIcons.Ruler, "Ruler", onClick = {}, enabled = false)
        }
        ToolButton(
            icon = tool.icon,
            contentDescription = tool.label,
            selected = tool == selected,
            onClick = { onSelectTool(tool) },
            enabled = tool.available,
        )
        if (tool == EditorTool.LASSO) PillDivider(vertical = vertical)
    }
}

internal val EditorTool.icon: ImageVector
    get() =
        when (this) {
            EditorTool.LASSO -> FolioIcons.Lasso
            EditorTool.PEN -> FolioIcons.Pen
            EditorTool.HIGHLIGHTER -> FolioIcons.Highlighter
            EditorTool.ERASER -> FolioIcons.Eraser
            EditorTool.SHAPE -> FolioIcons.Shapes
            EditorTool.TEXT -> FolioIcons.Type
            EditorTool.TABLE -> FolioIcons.Table
            EditorTool.IMAGE -> FolioIcons.Image
            EditorTool.STICKY -> FolioIcons.StickyNote
            EditorTool.ATTACHMENT -> FolioIcons.Paperclip
        }

internal val EditorTool.label: String
    get() =
        when (this) {
            EditorTool.LASSO -> "Lasso"
            EditorTool.PEN -> "Pen"
            EditorTool.HIGHLIGHTER -> "Highlighter"
            EditorTool.ERASER -> "Eraser"
            EditorTool.SHAPE -> "Shape"
            EditorTool.TEXT -> "Text"
            EditorTool.TABLE -> "Table"
            EditorTool.IMAGE -> "Image"
            EditorTool.STICKY -> "Sticky note"
            EditorTool.ATTACHMENT -> "Attachment"
        }

// Fades the content out toward an edge that has more content beyond it (left/right, or top/bottom when
// [vertical]). Black and transparent are alpha masks here (DstIn keeps the content where the mask is
// opaque), not visible colors.
internal fun Modifier.edgeFade(
    state: ScrollState,
    width: Dp,
    vertical: Boolean = false,
): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val length = if (vertical) size.height else size.width
        val fadePx = width.toPx().coerceAtMost(length / 2)
        val band = if (vertical) Size(size.width, fadePx) else Size(fadePx, size.height)
        if (state.canScrollBackward) {
            val fade = listOf(Color.Transparent, Color.Black)
            drawRect(
                brush = if (vertical) Brush.verticalGradient(fade, 0f, fadePx) else Brush.horizontalGradient(fade, 0f, fadePx),
                size = band,
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            val fade = listOf(Color.Black, Color.Transparent)
            val start = length - fadePx
            drawRect(
                brush =
                    if (vertical) Brush.verticalGradient(fade, start, length) else Brush.horizontalGradient(fade, start, length),
                topLeft = if (vertical) Offset(0f, start) else Offset(start, 0f),
                size = band,
                blendMode = BlendMode.DstIn,
            )
        }
    }
