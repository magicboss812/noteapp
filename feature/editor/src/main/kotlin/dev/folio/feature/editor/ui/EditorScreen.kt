package dev.folio.feature.editor.ui

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.canvas.CanvasHost
import dev.folio.feature.editor.canvas.findActivity
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
        onHideOptions = viewModel::setOptionsHidden,
        onReadMode = viewModel::setReadMode,
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
 * Stateless editor screen (10-editor-ui.md#screen-structure): the [canvas] slot fills the window (composed
 * once the document is open) and the chrome floats over it, with the toolbox at the placement stored for
 * the current orientation (10-editor-ui.md#toolbar-docking); the options popovers open beside the toolbox.
 * The canvas keeps its place and size across dock changes and read mode, so its view is never recreated or
 * resized by the chrome. In read mode the system bars hide and Back leaves read mode first.
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
    onHideOptions: (Boolean) -> Unit = {},
    onReadMode: (Boolean) -> Unit = {},
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
    val metrics = remember { ChromeMetrics() }
    LaunchedEffect(stored) { drag.pending = null }
    val placement = drag.pending ?: stored
    val gestures = rememberToolbarGestures(drag, placement, orientation, onPlaceToolbar)
    val actions =
        ChromeActions(
            onBack = onBack,
            onSelectTool = onSelectTool,
            onOptionsChange = onOptionsChange,
            onPopover = onPopover,
            onUndo = onUndo,
            onRedo = onRedo,
            onReadMode = onReadMode,
            onToggleOptions = { onHideOptions(!state.docks.optionsHidden) },
            onRetrySave = onRetrySave,
            pages = pageChrome,
        )
    BackHandler(enabled = state.readMode) { onReadMode(false) }
    SystemBarsHidden(state.readMode)
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
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
        EditorChrome(state, placement, drag, gestures, metrics, actions)
        if (ready) {
            val safe = Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
            PageSurfaces(state.pages, pageThumbnail, pageActions, safe)
            OptionsPopoverLayer(
                state.popover,
                state.options,
                onOptionsChange,
                onPopover,
                onClearPage,
                modifier = Modifier.besideToolbox(placement.mode, metrics),
                anchor = placement.mode.popoverAnchor,
            )
            if (state.showHelp) ShortcutHelp(shortcutGroups, onClose = { onHelp(false) })
        }
    }
}

// Hides the status and navigation bars while [hidden] (read mode); a swipe from an edge shows them for a moment.
@Composable
private fun SystemBarsHidden(hidden: Boolean) {
    val view = LocalView.current
    DisposableEffect(view, hidden) {
        val window = view.context.findActivity()?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (hidden && controller != null) {
            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
        onDispose { if (hidden) controller?.show(WindowInsetsCompat.Type.systemBars()) }
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
