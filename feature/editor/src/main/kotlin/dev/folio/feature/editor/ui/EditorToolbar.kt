package dev.folio.feature.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.EditorTool

/** Row 1 height (10-editor-ui.md#screen-structure). */
internal val TOOLBAR_ROW1_HEIGHT = 52.dp
internal val SCROLL_FADE = 24.dp

/**
 * Toolbar row 1 (10-editor-ui.md#toolbar): document pill, tools pill, split view toggle. The tools pill
 * scrolls horizontally with fade edges when the window is too narrow (portrait). Buttons of features
 * that arrive in later tasks show disabled.
 */
@Composable
internal fun EditorToolbarRow1(
    tool: EditorTool,
    onHome: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
) {
    val space = FolioTheme.space
    Row(
        modifier = modifier.fillMaxWidth().height(TOOLBAR_ROW1_HEIGHT).padding(horizontal = space.s12),
        horizontalArrangement = Arrangement.spacedBy(space.s8),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PillGroup(floating = false) {
            FolioIconButton(FolioIcons.House, "Home", onHome)
            FolioIconButton(FolioIcons.PanelLeft, "Pages", onClick = {}, enabled = false)
            FolioIconButton(FolioIcons.LayoutGrid, "Page overview", onClick = {}, enabled = false)
            FolioIconButton(FolioIcons.FilePlus, "Add page", onClick = {}, enabled = false)
            FolioIconButton(FolioIcons.Ellipsis, "More", onClick = {}, enabled = false)
        }
        val scroll = rememberScrollState()
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Box(Modifier.horizontalFade(scroll, SCROLL_FADE).horizontalScroll(scroll)) {
                PillGroup(floating = false) { ToolsPill(tool, onSelectTool) }
            }
        }
        FolioIconButton(FolioIcons.Columns2, "Split view", onClick = {}, enabled = false)
    }
}

@Composable
private fun ToolsPill(
    selected: EditorTool,
    onSelectTool: (EditorTool) -> Unit,
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
    }
}

private val EditorTool.icon: ImageVector
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

private val EditorTool.label: String
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

// Fades the content out toward an edge that has more content beyond it. Black and transparent are
// alpha masks here (DstIn keeps the content where the mask is opaque), not visible colors.
internal fun Modifier.horizontalFade(
    state: ScrollState,
    width: Dp,
): Modifier =
    graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen).drawWithContent {
        drawContent()
        val fadePx = width.toPx().coerceAtMost(size.width / 2)
        if (state.canScrollBackward) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = fadePx),
                size = Size(fadePx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
        if (state.canScrollForward) {
            drawRect(
                brush = Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - fadePx, endX = size.width),
                topLeft = Offset(size.width - fadePx, 0f),
                size = Size(fadePx, size.height),
                blendMode = BlendMode.DstIn,
            )
        }
    }
