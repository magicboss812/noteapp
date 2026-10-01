package dev.folio.feature.editor.ui

import android.content.res.Configuration
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.canvas.CanvasHost
import dev.folio.feature.editor.state.DockMode
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.EditorStatus
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.EditorUiState
import dev.folio.feature.editor.state.EditorViewModel
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.PageSettings
import dev.folio.feature.editor.state.PageSurface
import dev.folio.feature.editor.state.PagesUi
import dev.folio.feature.editor.state.ScreenOrientation
import dev.folio.feature.editor.state.ShortcutGroup
import dev.folio.feature.editor.state.ShortcutRegistry
import dev.folio.feature.editor.state.ToolOptions
import dev.folio.feature.editor.state.ToolbarPlacement

/** Editor destination for the document at library path [docPath]; [onBack] returns to the library. */
@Composable
fun EditorRoute(
    docPath: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    canvasListener: EditorCanvasListener? = null,
    density: Float = LocalDensity.current.density,
    viewModel: EditorViewModel =
        hiltViewModel<EditorViewModel, EditorViewModel.Factory>(key = docPath) { it.create(docPath, density) },
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by viewModel.session.collectAsStateWithLifecycle()
    EditorScreen(
        state = state,
        onBack = onBack,
        onSelectTool = viewModel::selectTool,
        modifier = modifier,
        onOptionsChange = viewModel::updateOptions,
        onPopover = viewModel::showPopover,
        onClearPage = viewModel::clearCurrentPage,
        onPlaceToolbar = viewModel::placeToolbar,
        onUndo = viewModel::undo,
        onRedo = viewModel::redo,
        onHelp = viewModel::showHelp,
        onKey = viewModel::onKey,
        pageChrome =
            PageChrome(
                onPanel = { viewModel.showPages(PageSurface.PANEL) },
                onOverview = { viewModel.showPages(PageSurface.OVERVIEW) },
                onAdd = viewModel::addPage,
            ),
        pageActions =
            PageActions(
                onGo = viewModel::goToPage,
                onDuplicate = viewModel::duplicatePages,
                onDelete = viewModel::deletePages,
                onInsert = viewModel::insertPage,
                onSettings = viewModel::showPageSettings,
                onMove = viewModel::movePage,
                onCloseSurface = { viewModel.showPages(PageSurface.NONE) },
                onApplySettings = viewModel::applyPageSettings,
                onCloseSettings = { viewModel.showPageSettings(null) },
            ),
        pageThumbnail = { page, thumbModifier -> session?.let { SessionPageThumbnail(it, page, thumbModifier) } },
        onRetrySave = viewModel::retrySave,
    ) { canvasModifier ->
        session?.let { EditorCanvas(it, canvasListener, canvasModifier) }
    }
}

@Composable
private fun EditorCanvas(
    session: EditorSession,
    listener: EditorCanvasListener?,
    modifier: Modifier = Modifier,
) {
    CanvasHost(session, modifier, onHost = { listener?.shown(session, it) })
    DisposableEffect(session, listener) { onDispose { listener?.gone(session) } }
}

/**
 * Stateless editor screen (10-editor-ui.md#screen-structure): the toolbar docked at the placement stored for
 * the current orientation (10-editor-ui.md#toolbar-docking) around the [canvas] slot, which is only
 * composed once the document is open; the tool options row and its popovers float over it. The canvas
 * keeps its place in the composition across dock changes, so its view is never recreated.
 */
@Composable
fun EditorScreen(
    state: EditorUiState,
    onBack: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    modifier: Modifier = Modifier,
    onOptionsChange: ((ToolOptions) -> ToolOptions) -> Unit = {},
    onPopover: (OptionsPopover?) -> Unit = {},
    onClearPage: () -> Unit = {},
    onPlaceToolbar: (ScreenOrientation, ToolbarPlacement) -> Unit = { _, _ -> },
    onUndo: () -> Unit = {},
    onRedo: () -> Unit = {},
    onHelp: (Boolean) -> Unit = {},
    onKey: (keyCode: Int, ctrl: Boolean, shift: Boolean, alt: Boolean) -> Boolean = { _, _, _, _ -> false },
    shortcutGroups: List<ShortcutGroup> = ShortcutRegistry.DEFAULT_GROUPS,
    pageChrome: PageChrome = PageChrome(),
    pageActions: PageActions = PageActions(),
    pageThumbnail: PageThumbnail = { page, thumbModifier -> PaperThumbnail(page, thumbModifier) },
    onRetrySave: () -> Unit = {},
    canvas: @Composable (Modifier) -> Unit,
) {
    val colors = FolioTheme.colors
    val orientation =
        if (LocalConfiguration.current.orientation ==
            Configuration.ORIENTATION_PORTRAIT
        ) {
            ScreenOrientation.PORTRAIT
        } else {
            ScreenOrientation.LANDSCAPE
        }
    val stored = state.docks.placement(orientation)
    val drag = remember { ToolbarDrag() }
    LaunchedEffect(stored) { drag.pending = null }
    val placement = drag.pending ?: stored
    val canvasPadding = canvasPadding(placement.mode)
    val gestures = rememberToolbarGestures(drag, placement, orientation, onPlaceToolbar)
    // Hardware keys reach the registry from the screen root; it holds focus while no popover or field does.
    val keyFocus = remember { FocusRequester() }
    val ready = state.status == EditorStatus.READY
    LaunchedEffect(ready, state.popover == null) { if (ready && state.popover == null) keyFocus.requestFocus() }
    // No app bar: the pills sit on bg.canvas like the pages (DESIGN.md section 8).
    Box(
        modifier
            .onKeyEvent { event ->
                event.type == KeyEventType.KeyDown &&
                    onKey(event.nativeKeyEvent.keyCode, event.isCtrlPressed, event.isShiftPressed, event.isAltPressed)
            }.focusRequester(keyFocus)
            .focusable()
            .fillMaxSize()
            .background(colors.canvas),
    ) {
        Box(Modifier.fillMaxSize().padding(canvasPadding), contentAlignment = Alignment.Center) {
            when (state.status) {
                EditorStatus.OPENING -> {
                    Unit
                }

                EditorStatus.READY -> {
                    canvas(Modifier.fillMaxSize())
                }

                EditorStatus.FAILED -> {
                    Text(
                        text = "This note cannot be opened: ${state.error}",
                        style = FolioTheme.type.body,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(FolioTheme.space.s24),
                    )
                }
            }
        }
        EditorChrome(state, placement, drag, gestures, onBack, onSelectTool, onOptionsChange, onPopover, onUndo, onRedo, pageChrome)
        if (state.status == EditorStatus.READY) {
            PageSurfaces(state.pages, pageThumbnail, pageActions, Modifier.padding(canvasPadding))
            OptionsPopoverLayer(
                state.popover,
                state.options,
                onOptionsChange,
                onPopover,
                onClearPage,
                modifier = Modifier.padding(canvasPadding),
                anchor = placement.mode.popoverAnchor,
            )
            if (state.showHelp) ShortcutHelp(shortcutGroups, onClose = { onHelp(false) })
            SaveIndicator(
                state.saveState,
                onRetrySave,
                Modifier
                    .align(Alignment.BottomStart)
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .padding(FolioTheme.space.s12),
            )
        }
    }
}

// The page panel, the overview and the settings sheet over the canvas (10-editor-ui.md#pages).
@Composable
private fun PageSurfaces(
    pages: PagesUi,
    thumbnail: PageThumbnail,
    actions: PageActions,
    modifier: Modifier = Modifier,
) {
    when (pages.surface) {
        PageSurface.NONE -> {
            Unit
        }

        PageSurface.PANEL -> {
            Box(modifier.fillMaxSize().pointerInput(Unit) { detectTapGestures { actions.onCloseSurface() } }) {
                PagePanel(
                    pages = pages.pages,
                    current = pages.current,
                    thumbnail = thumbnail,
                    actions = actions,
                    onClose = actions.onCloseSurface,
                    // Taps inside the card must not close it.
                    modifier =
                        Modifier
                            .align(Alignment.CenterStart)
                            .padding(FolioTheme.space.chromeInset)
                            .pointerInput(Unit) { detectTapGestures { } },
                )
            }
        }

        PageSurface.OVERVIEW -> {
            PageOverview(pages.pages, pages.current, thumbnail, actions, modifier)
        }
    }
    val settingsRef = pages.settingsPage?.let { id -> pages.pages.firstOrNull { it.id == id } }
    if (settingsRef != null) {
        PageSettingsSheet(PageSettings(settingsRef), actions.onApplySettings, actions.onCloseSettings)
    }
}

/** Grip callbacks of the toolbar in one orientation. */
private class ToolbarGestures(
    val startDocked: (gripInRoot: Offset) -> Unit,
    val startFloating: (Offset) -> Unit,
    val move: (Offset) -> Unit,
    val release: () -> Unit,
    val toggleCollapsed: () -> Unit,
)

@Composable
private fun rememberToolbarGestures(
    drag: ToolbarDrag,
    placement: ToolbarPlacement,
    orientation: ScreenOrientation,
    onPlaceToolbar: (ScreenOrientation, ToolbarPlacement) -> Unit,
): ToolbarGestures {
    val density = LocalDensity.current
    // The grip leads a horizontal pill, after its 4 dp end padding (PillGroup).
    val gripInset = with(density) { FolioTheme.space.s4.toPx() }
    val edgePx = with(density) { EDGE_SNAP.toPx() }
    val current by rememberUpdatedState(placement)
    val place by rememberUpdatedState { p: ToolbarPlacement -> onPlaceToolbar(orientation, p) }
    return remember(drag, gripInset, edgePx) {
        ToolbarGestures(
            // The preview pill starts with its grip under the finger (the grip leads the pill, inside its padding).
            startDocked = { gripInRoot -> drag.start(gripInRoot - drag.areaOrigin - Offset(gripInset, 0f)) },
            startFloating = { _ -> drag.start(drag.topLeft(current).let { Offset(it.x.toFloat(), it.y.toFloat()) }) },
            move = drag::move,
            release = { drag.end(current, edgePx)?.let { if (it != current) place(it) } },
            toggleCollapsed = { place(current.copy(collapsed = !current.collapsed)) },
        )
    }
}

@Composable
private fun EditorChrome(
    state: EditorUiState,
    placement: ToolbarPlacement,
    drag: ToolbarDrag,
    gestures: ToolbarGestures,
    onBack: () -> Unit,
    onSelectTool: (EditorTool) -> Unit,
    onOptionsChange: ((ToolOptions) -> ToolOptions) -> Unit,
    onPopover: (OptionsPopover?) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    pageChrome: PageChrome,
) {
    val space = FolioTheme.space
    val motion = FolioTheme.motion
    val slidePx = dockSlidePx()
    val mode = placement.mode
    val ready = state.status == EditorStatus.READY
    val dragging by remember(drag) { derivedStateOf { drag.offset != null } }
    // Row 2: the undo pill and the options pill, spread across the top, stacked on a rail, or side by side when floating.
    val options: @Composable (Modifier, Modifier, Row2Layout) -> Unit = { rowModifier, pillModifier, layout ->
        if (ready) ToolRow2(state, onOptionsChange, onPopover, onUndo, onRedo, layout, rowModifier, pillModifier)
    }
    val dockedGrip: @Composable (Boolean) -> Unit = { vertical ->
        ToolbarGrip(gestures.startDocked, gestures.move, gestures.release, vertical = vertical)
    }
    val safe = WindowInsets.safeDrawing
    // One root: the docked chrome, then the floating area above it.
    Box(Modifier.fillMaxSize()) {
        AnimatedContent(
            targetState = mode,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = if (dragging && mode != DockMode.FLOATING) DRAG_DIM else 1f },
            transitionSpec = { dockTransition(motion, slidePx) },
            label = "toolbarDock",
        ) { shown ->
            when (shown) {
                DockMode.TOP -> {
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        EditorToolbarRow1(
                            tool = state.tool,
                            onHome = onBack,
                            onSelectTool = onSelectTool,
                            modifier = Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal)),
                            pages = pageChrome,
                            grip = { dockedGrip(false) },
                        )
                        options(
                            Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal)),
                            Modifier.padding(horizontal = space.chromeInset),
                            Row2Layout.SPREAD,
                        )
                    }
                }

                DockMode.LEFT, DockMode.RIGHT -> {
                    val left = shown == DockMode.LEFT
                    val outer = if (left) WindowInsetsSides.Start else WindowInsetsSides.End
                    Row(Modifier.fillMaxSize(), horizontalArrangement = if (left) Arrangement.Start else Arrangement.End) {
                        val rail: @Composable () -> Unit = {
                            EditorToolbarRow1(
                                tool = state.tool,
                                onHome = onBack,
                                onSelectTool = onSelectTool,
                                modifier = Modifier.windowInsetsPadding(safe.only(outer + WindowInsetsSides.Vertical)),
                                vertical = true,
                                pages = pageChrome,
                                grip = { dockedGrip(true) },
                            )
                        }
                        val optionsRail: @Composable () -> Unit = {
                            options(
                                Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Vertical)),
                                Modifier.padding(bottom = space.chromeInset),
                                Row2Layout.RAIL,
                            )
                        }
                        // Row 1 is the outer rail, row 2 the inner one (10-editor-ui.md#toolbar-docking).
                        if (left) {
                            rail()
                            optionsRail()
                        } else {
                            optionsRail()
                            rail()
                        }
                    }
                }

                DockMode.FLOATING -> {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
                        FloatingToolbar(
                            tool = state.tool,
                            collapsed = placement.collapsed,
                            onSelectTool = onSelectTool,
                            onExpand = gestures.toggleCollapsed,
                            grip = {
                                ToolbarGrip(gestures.startFloating, gestures.move, gestures.release, onDoubleTap = gestures.toggleCollapsed)
                            },
                            modifier = Modifier.offset { drag.topLeft(placement) }.floatingPill(drag),
                            options = { options(Modifier, Modifier, Row2Layout.ROW) },
                        )
                    }
                }
            }
        }
        // The area the floating pill moves in; while a docked toolbar is dragged, a preview pill follows the finger.
        Box(
            Modifier.fillMaxSize().windowInsetsPadding(safe).onGloballyPositioned {
                drag.areaOrigin = it.positionInRoot()
                drag.areaSize = it.size
            },
        ) {
            if (dragging && mode != DockMode.FLOATING) {
                FloatingToolbar(
                    tool = state.tool,
                    collapsed = false,
                    onSelectTool = {},
                    onExpand = {},
                    grip = { ToolbarGrip(onDragStart = {}, onDrag = {}, onDragEnd = {}) },
                    modifier = Modifier.offset { drag.topLeft(placement) }.floatingPill(drag),
                )
            }
        }
    }
}

// How row 2 lays out the undo pill and the options pill (see ToolRow2).
private enum class Row2Layout { SPREAD, RAIL, ROW }

// Row 2: the undo pill and the options pill, spread across the top, stacked on a rail, or side by side when floating.
@Composable
private fun ToolRow2(
    state: EditorUiState,
    onOptionsChange: OptionsChange,
    onPopover: (OptionsPopover?) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    layout: Row2Layout,
    rowModifier: Modifier = Modifier,
    pillModifier: Modifier = Modifier,
) {
    val inset = FolioTheme.space.chromeInset
    val vertical = layout == Row2Layout.RAIL
    val history: @Composable (Modifier) -> Unit = { UndoRedoPill(state.canUndo, state.canRedo, onUndo, onRedo, it, vertical) }
    val tools: @Composable (Modifier) -> Unit = {
        ToolOptionsRow(state.tool, state.options, onOptionsChange, onPopover, it, pillModifier, vertical)
    }
    when (layout) {
        Row2Layout.SPREAD -> {
            Box(rowModifier.fillMaxWidth()) {
                history(Modifier.align(Alignment.CenterStart).padding(horizontal = inset))
                tools(Modifier.align(Alignment.TopCenter))
            }
        }

        Row2Layout.RAIL -> {
            Column(rowModifier) {
                history(Modifier.padding(top = inset))
                tools(Modifier)
            }
        }

        Row2Layout.ROW -> {
            Row(rowModifier, horizontalArrangement = Arrangement.spacedBy(inset)) {
                history(Modifier)
                tools(Modifier)
            }
        }
    }
}

// Docked toolbar opacity while its preview pill is dragged.
private const val DRAG_DIM = 0.4f

/** Canvas area for a toolbar docked at [mode]: below row 1, beside a side rail, or the whole window. */
@Composable
private fun canvasPadding(mode: DockMode): PaddingValues {
    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val direction = LocalLayoutDirection.current
    return PaddingValues(
        start = if (mode == DockMode.LEFT) insets.calculateStartPadding(direction) + TOOLBAR_ROW1_HEIGHT else 0.dp,
        top = insets.calculateTopPadding() + if (mode == DockMode.TOP) TOOLBAR_ROW1_HEIGHT else 0.dp,
        end = if (mode == DockMode.RIGHT) insets.calculateEndPadding(direction) + TOOLBAR_ROW1_HEIGHT else 0.dp,
    )
}

@Composable
private fun PreviewCanvas(modifier: Modifier = Modifier) {
    Box(modifier)
}

@Preview(name = "editor light", widthDp = 1164, heightDp = 777)
@Composable
private fun EditorLightPreview() {
    FolioTheme(darkTheme = false) {
        EditorScreen(EditorUiState(EditorStatus.READY), onBack = {}, onSelectTool = {}) { PreviewCanvas(it) }
    }
}

@Preview(name = "editor dark", widthDp = 1164, heightDp = 777, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun EditorDarkPreview() {
    FolioTheme(darkTheme = true) {
        EditorScreen(EditorUiState(EditorStatus.READY), onBack = {}, onSelectTool = {}) { PreviewCanvas(it) }
    }
}
