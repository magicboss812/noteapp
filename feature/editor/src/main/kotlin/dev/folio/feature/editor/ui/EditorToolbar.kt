package dev.folio.feature.editor.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.FolioIconButton
import dev.folio.core.designsystem.component.PillDivider
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.PillOrientation
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.DockMode
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.NoteChoice
import kotlinx.collections.immutable.ImmutableList

/** Side rail width: a 44 dp pill with 8 dp chrome insets (10-editor-ui.md#toolbar-docking). */
internal val TOOLBAR_RAIL_WIDTH = 60.dp
internal val SCROLL_FADE = 24.dp

// Room row 1 keeps for its divider, chevron and grip when the tools scroll (two 44 dp cells, divider, pill padding).
private val ROW1_TAIL = 112.dp

// Fraction rule of the info pill and the gap around it.
private val FRACTION_RULE = 14.dp
private val FRACTION_RULE_HEIGHT = 1.dp

/**
 * Toolbar row 1 (10-editor-ui.md#toolbar): one pill with the tools (the lasso apart behind a divider), then
 * the chevron that hides or shows row 2 ([optionsHidden]) and the [grip] that moves the toolbox. [vertical]
 * lays it out for a side rail. The tools scroll with fade edges when the pill does not fit; the chevron
 * points to where row 2 appears or goes for dock [mode].
 */
@Composable
internal fun ToolsRow(
    tool: EditorTool,
    onSelectTool: (EditorTool) -> Unit,
    mode: DockMode,
    optionsHidden: Boolean,
    onToggleOptions: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    grip: @Composable () -> Unit = {},
) {
    val orientation = if (vertical) PillOrientation.Vertical else PillOrientation.Horizontal
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val scroll = rememberScrollState()
        PillGroup(orientation = orientation) {
            val tools = Modifier.edgeFade(scroll, SCROLL_FADE, vertical)
            if (vertical) {
                Column(
                    tools.heightIn(max = (maxHeight - ROW1_TAIL).coerceAtLeast(0.dp)).verticalScroll(scroll),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) { ToolsPill(tool, onSelectTool, vertical = true) }
            } else {
                Row(
                    tools.widthIn(max = (maxWidth - ROW1_TAIL).coerceAtLeast(0.dp)).horizontalScroll(scroll),
                    verticalAlignment = Alignment.CenterVertically,
                ) { ToolsPill(tool, onSelectTool) }
            }
            PillDivider(vertical = vertical)
            FolioIconButton(
                icon = mode.chevron(optionsHidden),
                contentDescription = if (optionsHidden) "Show tool options" else "Hide tool options",
                onClick = onToggleOptions,
            )
            grip()
        }
    }
}

// Points where row 2 appears ([hidden]) or where it goes (shown): row 2 sits above row 1 when docked
// bottom, below it when docked top or floating, inside the rail when docked to a side.
internal fun DockMode.chevron(hidden: Boolean): ImageVector =
    when (this) {
        DockMode.BOTTOM -> if (hidden) FolioIcons.ChevronUp else FolioIcons.ChevronDown
        DockMode.TOP, DockMode.FLOATING -> if (hidden) FolioIcons.ChevronDown else FolioIcons.ChevronUp
        DockMode.LEFT -> if (hidden) FolioIcons.ChevronRight else FolioIcons.ChevronLeft
        DockMode.RIGHT -> if (hidden) FolioIcons.ChevronLeft else FolioIcons.ChevronRight
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

/** Nav pill (top-left, 10-editor-ui.md#toolbar): Home, page panel, page overview, add page. */
@Composable
internal fun NavPill(
    onHome: () -> Unit,
    pages: PageChrome,
    modifier: Modifier = Modifier,
    pagesEnabled: Boolean = true,
) {
    PillGroup(modifier) {
        FolioIconButton(FolioIcons.House, "Home", onHome)
        FolioIconButton(FolioIcons.PanelLeft, "Pages", pages.onPanel, enabled = pagesEnabled)
        FolioIconButton(FolioIcons.LayoutGrid, "Page overview", pages.onOverview, enabled = pagesEnabled)
        FolioIconButton(FolioIcons.FilePlus, "Add page", pages.onAdd, enabled = pagesEnabled)
    }
}

/**
 * Mode pill (top-right): read mode, split view (a later task, shown disabled) and the More menu, which lists the
 * notes that link here ([linkedFrom], open while not null).
 */
@Composable
internal fun ModePill(
    onReadMode: () -> Unit,
    modifier: Modifier = Modifier,
    linkedFrom: ImmutableList<NoteChoice>? = null,
    onLinkedFrom: (Boolean) -> Unit = {},
    onOpenBacklink: (NoteChoice) -> Unit = {},
) {
    PillGroup(modifier) {
        FolioIconButton(FolioIcons.BookOpen, "Read mode", onReadMode)
        FolioIconButton(FolioIcons.Columns2, "Split view", onClick = {}, enabled = false)
        Box {
            FolioIconButton(FolioIcons.Ellipsis, "More", onClick = { onLinkedFrom(true) })
            LinkedFromMenu(linkedFrom, onDismiss = { onLinkedFrom(false) }, onOpen = onOpenBacklink)
        }
    }
}

/** The only chrome left in read mode (top-right): the selected read mode button leaves it. */
@Composable
internal fun ReadModeExitPill(
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PillGroup(modifier) {
        ToolButton(icon = FolioIcons.BookOpen, contentDescription = "Leave read mode", selected = true, onClick = onLeave)
    }
}

/**
 * Info pill (bottom-left, DESIGN.md section 7): the current page over a short rule over the page count
 * (dimmed), a divider, the zoom in percent of fit width. [page] is 1-based.
 */
@Composable
internal fun InfoPill(
    page: Int,
    pageCount: Int,
    zoomPercent: Int,
    modifier: Modifier = Modifier,
) {
    val colors = FolioTheme.colors
    val space = FolioTheme.space
    val type = FolioTheme.type
    PillGroup(modifier.semantics(mergeDescendants = true) { contentDescription = "Page $page of $pageCount, zoom $zoomPercent%" }) {
        Box(Modifier.height(space.toolbarPill).padding(horizontal = space.s12), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("$page", style = type.labelSmall, color = colors.textPrimary)
                Box(Modifier.width(FRACTION_RULE).height(FRACTION_RULE_HEIGHT).background(colors.textTertiary))
                Text("$pageCount", style = type.labelSmall, color = colors.textTertiary)
            }
        }
        PillDivider()
        Box(Modifier.height(space.toolbarPill).padding(horizontal = space.s12), contentAlignment = Alignment.Center) {
            Text("$zoomPercent%", style = type.label, color = colors.textPrimary)
        }
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
