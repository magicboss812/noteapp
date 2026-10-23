package dev.folio.feature.editor.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsIgnoringVisibility
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.DockMode
import dev.folio.feature.editor.state.EditorStatus
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.EditorUiState
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.ScreenOrientation
import dev.folio.feature.editor.state.ToolbarPlacement
import kotlin.math.max

/** Row 1 of a bottom-docked toolbox sits this far above the window's safe bottom (DESIGN.md section 8). */
internal val TOOLBOX_BOTTOM = 25.dp

// Hidden chrome sits this far outside the window (read mode), out of reach of touches.
private const val HIDDEN_OFFSET_PX = 100_000f

// Docked toolbar opacity while its preview pill is dragged.
private const val DRAG_DIM = 0.4f

/** Callbacks of the editor chrome (10-editor-ui.md#toolbar). */
internal class ChromeActions(
    val onBack: () -> Unit,
    val onSelectTool: (EditorTool) -> Unit,
    val onOptionsChange: OptionsChange,
    val onPopover: (OptionsPopover?) -> Unit,
    val onUndo: () -> Unit,
    val onRedo: () -> Unit,
    val onReadMode: (Boolean) -> Unit,
    val onToggleOptions: () -> Unit,
    val onRetrySave: () -> Unit,
    val pages: PageChrome,
) {
    internal companion object {
        /** Actions that call whatever [latest] holds when invoked; the instance itself never changes. */
        fun forwardingTo(latest: State<ChromeActions>): ChromeActions =
            ChromeActions(
                onBack = { latest.value.onBack() },
                onSelectTool = { latest.value.onSelectTool(it) },
                onOptionsChange = { latest.value.onOptionsChange(it) },
                onPopover = { latest.value.onPopover(it) },
                onUndo = { latest.value.onUndo() },
                onRedo = { latest.value.onRedo() },
                onReadMode = { latest.value.onReadMode(it) },
                onToggleOptions = { latest.value.onToggleOptions() },
                onRetrySave = { latest.value.onRetrySave() },
                pages =
                    PageChrome(
                        onPanel = { latest.value.pages.onPanel() },
                        onOverview = { latest.value.pages.onOverview() },
                        onAdd = { latest.value.pages.onAdd() },
                    ),
            )
    }
}

/**
 * Measured chrome sizes (px): the free [area] of the window, the nav column ([nav]: nav pill and edit pill),
 * the [mode] pill, the [info] row (info pill and save dot), the docked [toolbox] (rows 1 and 2) and both
 * side [rails]. The corner pills and the docked toolbox avoid each other from these.
 */
@Stable
internal class ChromeMetrics {
    var area by mutableStateOf(IntSize.Zero)
    var nav by mutableStateOf(IntSize.Zero)
    var mode by mutableStateOf(IntSize.Zero)
    var info by mutableStateOf(IntSize.Zero)
    var toolbox by mutableStateOf(IntSize.Zero)
    var rails by mutableIntStateOf(0)

    /** Free width on each side of a centered toolbox, or null before the first measure. */
    private fun sideRoom(): Int? = if (area.width == 0 || toolbox.width == 0) null else (area.width - toolbox.width) / 2

    /** Top of a top-docked toolbox: level with the corner pills, or below the nav column when they would overlap. */
    fun topDockOffsetPx(insetPx: Int): Int {
        val room = sideRoom() ?: return insetPx
        return if (room < max(nav.width, mode.width) + 2 * insetPx) nav.height + 2 * insetPx else insetPx
    }

    /** Whether the info row moves above a bottom-docked toolbox because they would overlap side by side. */
    fun infoLifted(insetPx: Int): Boolean {
        val room = sideRoom() ?: return false
        return room < info.width + 2 * insetPx
    }
}

/** Grip callbacks of the toolbar in one orientation. */
internal class ToolbarGestures(
    val startDocked: (gripInRoot: Offset) -> Unit,
    val startFloating: (Offset) -> Unit,
    val move: (Offset) -> Unit,
    val release: () -> Unit,
    val toggleCollapsed: () -> Unit,
)

@Composable
internal fun rememberToolbarGestures(
    drag: ToolbarDrag,
    placement: ToolbarPlacement,
    orientation: ScreenOrientation,
    onPlaceToolbar: (ScreenOrientation, ToolbarPlacement) -> Unit,
): ToolbarGestures {
    val density = LocalDensity.current
    // The grip leads the floating pill, after its 4 dp end padding (PillGroup).
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

/**
 * Editor chrome without an app bar (10-editor-ui.md#screen-structure, A-037): corner pills (nav and edit
 * top-left, mode top-right, info bottom-left) and the toolbox at its dock, all floating over the canvas.
 * Read mode slides everything out but a small exit pill (10-editor-ui.md#read-mode).
 */
@Composable
internal fun EditorChrome(
    state: EditorUiState,
    placement: ToolbarPlacement,
    drag: ToolbarDrag,
    gestures: ToolbarGestures,
    metrics: ChromeMetrics,
    actions: ChromeActions,
) {
    val motion = FolioTheme.motion
    val slidePx = dockSlidePx()
    val mode = placement.mode
    // Read mode moves the chrome out in the layer only: it stays composed, so leaving read mode composes nothing.
    val shown by animateFloatAsState(if (state.readMode) 0f else 1f, motion.standard(), label = "chromeShown")
    val shownFraction = { shown }
    val dragging by remember(drag) { derivedStateOf { drag.offset != null } }
    val safe = chromeInsets
    Box(Modifier.fillMaxSize()) {
        CornerPills(state, mode, metrics, actions, shownFraction)
        AnimatedContent(
            targetState = mode,
            modifier =
                Modifier
                    .fillMaxSize()
                    .chromeShown(shownFraction, mode.edge, slidePx)
                    .graphicsLayer {
                        alpha = if (dragging && mode != DockMode.FLOATING) DRAG_DIM else 1f
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            transitionSpec = { dockTransition(motion, slidePx) },
            label = "toolbarDock",
        ) { dock -> Toolbox(state, dock, placement, drag, gestures, metrics, actions) }
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
        AnimatedVisibility(
            visible = state.readMode,
            modifier = Modifier.align(Alignment.TopEnd),
            enter = fadeIn(motion.standard()),
            exit = fadeOut(motion.fast()),
            label = "readModeExit",
        ) {
            ReadModeExitPill(
                onLeave = { actions.onReadMode(false) },
                modifier = Modifier.windowInsetsPadding(safe).padding(FolioTheme.space.chromeInset),
            )
        }
    }
}

// The toolbox at [dock]: rows 2 and 1 centered at the bottom or top edge, two rails at a side, or the floating pill.
@Composable
private fun Toolbox(
    state: EditorUiState,
    dock: DockMode,
    placement: ToolbarPlacement,
    drag: ToolbarDrag,
    gestures: ToolbarGestures,
    metrics: ChromeMetrics,
    actions: ChromeActions,
) {
    val space = FolioTheme.space
    val density = LocalDensity.current
    val safe = chromeInsets
    val hidden = state.docks.optionsHidden
    val ready = state.status == EditorStatus.READY
    val options: @Composable (Modifier, Boolean) -> Unit = { pillModifier, vertical ->
        if (ready) {
            ToolOptionsRow(
                state.tool,
                state.options,
                actions.onOptionsChange,
                actions.onPopover,
                Modifier,
                pillModifier,
                vertical,
                onSettings = actions.onSelectTool,
            )
        }
    }
    val row1: @Composable (Modifier, Boolean) -> Unit = { rowModifier, vertical ->
        ToolsRow(
            tool = state.tool,
            onSelectTool = actions.onSelectTool,
            mode = dock,
            optionsHidden = hidden,
            onToggleOptions = actions.onToggleOptions,
            modifier = rowModifier,
            vertical = vertical,
            grip = { ToolbarGrip(gestures.startDocked, gestures.move, gestures.release, vertical = vertical) },
        )
    }
    when (dock) {
        DockMode.BOTTOM, DockMode.TOP -> {
            val top = dock == DockMode.TOP
            val insetPx = with(density) { space.chromeInset.roundToPx() }
            val offset = if (top) with(density) { metrics.topDockOffsetPx(insetPx).toDp() } else TOOLBOX_BOTTOM
            Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
                DockedToolbox(
                    top = top,
                    hidden = hidden,
                    row1 = { row1(Modifier, false) },
                    row2 = { options(Modifier, false) },
                    modifier =
                        Modifier
                            .align(if (top) Alignment.TopCenter else Alignment.BottomCenter)
                            .padding(PaddingValues(top = if (top) offset else 0.dp, bottom = if (top) 0.dp else offset))
                            .padding(horizontal = space.chromeInset)
                            .onSizeChanged { metrics.toolbox = it },
                )
            }
        }

        DockMode.LEFT, DockMode.RIGHT -> {
            val left = dock == DockMode.LEFT
            val outer = if (left) WindowInsetsSides.Start else WindowInsetsSides.End
            Row(Modifier.fillMaxSize(), horizontalArrangement = if (left) Arrangement.Start else Arrangement.End) {
                Row(Modifier.fillMaxHeight().onSizeChanged { metrics.rails = it.width }) {
                    val rail: @Composable () -> Unit = {
                        row1(
                            Modifier
                                .windowInsetsPadding(safe.only(outer + WindowInsetsSides.Vertical))
                                .fillMaxHeight()
                                .width(TOOLBAR_RAIL_WIDTH)
                                .padding(vertical = space.chromeInset),
                            true,
                        )
                    }
                    val optionsRail: @Composable () -> Unit = {
                        AnimatedVisibility(
                            visible = !hidden,
                            enter = fadeIn(FolioTheme.motion.standard()) + expandHorizontally(FolioTheme.motion.standard()),
                            exit = fadeOut(FolioTheme.motion.fast()) + shrinkHorizontally(FolioTheme.motion.standard()),
                            label = "optionsRail",
                        ) {
                            Box(Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Vertical)).padding(top = space.chromeInset)) {
                                options(Modifier.padding(bottom = space.chromeInset), true)
                            }
                        }
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
        }

        DockMode.FLOATING -> {
            Box(Modifier.fillMaxSize().windowInsetsPadding(safe)) {
                FloatingToolbar(
                    tool = state.tool,
                    collapsed = placement.collapsed,
                    onSelectTool = actions.onSelectTool,
                    onExpand = gestures.toggleCollapsed,
                    grip = {
                        ToolbarGrip(gestures.startFloating, gestures.move, gestures.release, onDoubleTap = gestures.toggleCollapsed)
                    },
                    modifier = Modifier.offset { drag.topLeft(placement) }.floatingPill(drag),
                    optionsHidden = hidden,
                    onToggleOptions = actions.onToggleOptions,
                    options = { options(Modifier, false) },
                )
            }
        }
    }
}

/**
 * Rows 2 and 1 of a toolbox docked at the bottom ([top] false: row 2 above row 1, which stays put when row 2
 * hides) or the top (row 2 below), 8 dp apart and centered on each other.
 */
@Composable
private fun DockedToolbox(
    top: Boolean,
    hidden: Boolean,
    row1: @Composable () -> Unit,
    row2: @Composable () -> Unit,
    modifier: Modifier = Modifier,
) {
    val motion = FolioTheme.motion
    val gap = FolioTheme.space.chromeInset
    val edge = if (top) Alignment.Top else Alignment.Bottom
    val options: @Composable () -> Unit = {
        AnimatedVisibility(
            visible = !hidden,
            enter = fadeIn(motion.standard()) + expandVertically(motion.standard(), expandFrom = edge),
            exit = fadeOut(motion.fast()) + shrinkVertically(motion.standard(), shrinkTowards = edge),
            label = "optionsRow",
        ) { Box(Modifier.padding(PaddingValues(top = if (top) gap else 0.dp, bottom = if (top) 0.dp else gap))) { row2() } }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        if (!top) options()
        row1()
        if (top) options()
    }
}

// Nav and edit pills top-left, mode pill top-right, info row bottom-left; beside side rails, never under them.
@Composable
private fun CornerPills(
    state: EditorUiState,
    mode: DockMode,
    metrics: ChromeMetrics,
    actions: ChromeActions,
    shown: () -> Float,
) {
    val space = FolioTheme.space
    val motion = FolioTheme.motion
    val density = LocalDensity.current
    val slidePx = dockSlidePx()
    val ready = state.status == EditorStatus.READY
    val rails = with(density) { metrics.rails.toDp() }
    val insetPx = with(density) { space.chromeInset.roundToPx() }
    val lifted = mode == DockMode.BOTTOM && metrics.infoLifted(insetPx)
    val infoBottom by animateDpAsState(
        targetValue = if (lifted) TOOLBOX_BOTTOM + with(density) { metrics.toolbox.height.toDp() } + space.chromeInset else TOOLBOX_BOTTOM,
        animationSpec = motion.standard(),
        label = "infoBottom",
    )
    Box(
        Modifier
            .fillMaxSize()
            .windowInsetsPadding(chromeInsets)
            .padding(start = if (mode == DockMode.LEFT) rails else 0.dp, end = if (mode == DockMode.RIGHT) rails else 0.dp)
            .onSizeChanged { metrics.area = it },
    ) {
        Column(
            Modifier
                .align(Alignment.TopStart)
                .chromeShown(shown, Edge.TOP, slidePx)
                .padding(space.chromeInset)
                .onSizeChanged { metrics.nav = it },
            verticalArrangement = Arrangement.spacedBy(space.chromeInset),
        ) {
            NavPill(actions.onBack, actions.pages, pagesEnabled = ready)
            if (ready) UndoRedoPill(state.canUndo, state.canRedo, actions.onUndo, actions.onRedo)
        }
        if (ready) {
            ModePill(
                { actions.onReadMode(true) },
                Modifier
                    .align(Alignment.TopEnd)
                    .chromeShown(shown, Edge.TOP, slidePx)
                    .padding(space.chromeInset)
                    .onSizeChanged { metrics.mode = it },
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .chromeShown(shown, Edge.BOTTOM, slidePx)
                    .padding(start = space.chromeInset, bottom = infoBottom)
                    .onSizeChanged { metrics.info = it },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val count = state.pages.pages.size
                InfoPill(
                    page = (state.view.pageIndex + 1).coerceIn(1, max(count, 1)),
                    pageCount = count,
                    zoomPercent = state.view.zoomPercent,
                )
                SaveIndicator(state.saveState, actions.onRetrySave, Modifier.padding(horizontal = space.s12))
            }
        }
    }
}

/** Where the popover layer may draw for a toolbox at [mode]: the window minus the toolbox (see PopoverAnchor). */
@Composable
internal fun Modifier.besideToolbox(
    mode: DockMode,
    metrics: ChromeMetrics,
): Modifier {
    val density = LocalDensity.current
    val insetPx = with(density) { FolioTheme.space.chromeInset.roundToPx() }
    val toolbox: Dp = with(density) { metrics.toolbox.height.toDp() }
    val rails: Dp = with(density) { metrics.rails.toDp() }
    val padding =
        when (mode) {
            DockMode.BOTTOM -> PaddingValues(bottom = TOOLBOX_BOTTOM + toolbox)
            DockMode.TOP -> PaddingValues(top = with(density) { metrics.topDockOffsetPx(insetPx).toDp() } + toolbox)
            DockMode.LEFT -> PaddingValues(start = rails)
            DockMode.RIGHT -> PaddingValues(end = rails)
            DockMode.FLOATING -> PaddingValues()
        }
    return windowInsetsPadding(chromeInsets).padding(padding)
}

// Window edge a piece of chrome leaves toward in read mode.
private enum class Edge { TOP, BOTTOM, START, END, NONE }

private val DockMode.edge: Edge
    get() =
        when (this) {
            DockMode.BOTTOM -> Edge.BOTTOM
            DockMode.TOP -> Edge.TOP
            DockMode.LEFT -> Edge.START
            DockMode.RIGHT -> Edge.END
            DockMode.FLOATING -> Edge.NONE
        }

/**
 * Insets the chrome keeps clear of: system bars as if shown, cutout and IME. Read mode hides the bars, but
 * these stay put, so the chrome never relayouts per frame of the bars' animation (A-037).
 */
@OptIn(ExperimentalLayoutApi::class) // systemBarsIgnoringVisibility: no stable equivalent in foundation-layout
internal val chromeInsets: WindowInsets
    @Composable get() = WindowInsets.systemBarsIgnoringVisibility.union(WindowInsets.displayCutout).union(WindowInsets.ime)

/**
 * Read mode visibility in the layer only (no composition per frame or on show): [shown] (read in the layer)
 * runs from 1 (shown) to 0 (hidden); the chrome fades and slides [slidePx] toward [edge]. Alpha modulates
 * each draw instead of an offscreen buffer (the toolbox layer spans the window). Fully hidden it sits far
 * outside the window, so it takes no touches and the canvas gets them.
 */
private fun Modifier.chromeShown(
    shown: () -> Float,
    edge: Edge,
    slidePx: Int,
): Modifier =
    graphicsLayer {
        val visible = shown()
        alpha = visible
        compositingStrategy = CompositingStrategy.ModulateAlpha
        val away = if (visible == 0f) HIDDEN_OFFSET_PX else (1f - visible) * slidePx
        when (edge) {
            Edge.TOP -> translationY = -away
            Edge.BOTTOM, Edge.NONE -> translationY = away
            Edge.START -> translationX = -away
            Edge.END -> translationX = away
        }
    }
