package dev.folio.feature.editor.ui

import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.ContentTransform
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.folio.core.designsystem.component.PillGroup
import dev.folio.core.designsystem.component.ToolButton
import dev.folio.core.designsystem.icon.FolioIcons
import dev.folio.core.designsystem.theme.FolioMotion
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.state.DockMode
import dev.folio.feature.editor.state.DockSnap
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.ToolbarPlacement
import kotlin.math.roundToInt

/** Releasing the floating pill this close to an edge docks it there (10-editor-ui.md#toolbar-docking). */
internal val EDGE_SNAP = 48.dp

// How far a docking toolbar slides in from its edge.
private val DOCK_SLIDE = 64.dp

// The grip's dots run along a side rail.
private const val RAIL_GRIP_ROTATION_DEG = 90f

/**
 * Toolbar move gesture and the window area it moves in. Holds the floating pill's top-left during a drag
 * ([offset], px in the area) and the placement chosen on release until the stored state catches up
 * ([pending]), so the pill never jumps back for a frame.
 */
@Stable
internal class ToolbarDrag {
    /** The floating pill's top-left while a drag runs, else null. */
    var offset by mutableStateOf<Offset?>(null)
        private set

    /** Placement chosen by the last release, until the stored placement changes. */
    var pending by mutableStateOf<ToolbarPlacement?>(null)

    /** Size of the area the pill floats in. */
    var areaSize by mutableStateOf(IntSize.Zero)

    /** Top-left of that area in root coordinates. */
    var areaOrigin = Offset.Zero

    /** Size of the visible floating pill (the real one or the drag preview). */
    var pillSize by mutableStateOf(IntSize.Zero)

// Where the finger leads the pill, unclamped, so the grip catches up with a finger that started past an edge.
    private var led = Offset.Zero

    /** Starts a drag with the pill's top-left at [topLeft]. */
    fun start(topLeft: Offset) {
        led = topLeft
        offset = clamp(topLeft)
    }

    /** Moves the pill by [delta], kept inside the area. */
    fun move(delta: Offset) {
        if (offset == null) return
        led += delta
        offset = clamp(led)
    }

    /** Ends the drag: the placement for the release point (docked near an edge), or null if none ran. */
    fun end(
        current: ToolbarPlacement,
        edgePx: Float,
    ): ToolbarPlacement? {
        val at = offset ?: return null
        offset = null
        val released =
            DockSnap.release(
                current,
                at.x,
                at.y,
                pillSize.width.toFloat(),
                pillSize.height.toFloat(),
                areaSize.width.toFloat(),
                areaSize.height.toFloat(),
                edgePx,
            )
        pending = released
        return released
    }

    /** The floating pill's top-left for [placement] (or the running drag). */
    fun topLeft(placement: ToolbarPlacement): IntOffset {
        offset?.let { return IntOffset(it.x.roundToInt(), it.y.roundToInt()) }
        val freeX = (areaSize.width - pillSize.width).coerceAtLeast(0)
        val freeY = (areaSize.height - pillSize.height).coerceAtLeast(0)
        return IntOffset((freeX * placement.floatX).roundToInt(), (freeY * placement.floatY).roundToInt())
    }

    private fun clamp(at: Offset): Offset {
        val freeX = (areaSize.width - pillSize.width).coerceAtLeast(0).toFloat()
        val freeY = (areaSize.height - pillSize.height).coerceAtLeast(0).toFloat()
        return Offset(at.x.coerceIn(0f, freeX), at.y.coerceIn(0f, freeY))
    }
}

/**
 * Grip that moves the toolbar: drag it anywhere (the pill follows, [onDragStart] gets the grip's top-left in
 * root coordinates); [onDoubleTap] (floating pill only) collapses or expands it. [vertical] turns the dots
 * for a side rail.
 */
@Composable
internal fun ToolbarGrip(
    onDragStart: (gripInRoot: Offset) -> Unit,
    onDrag: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    modifier: Modifier = Modifier,
    vertical: Boolean = false,
    onDoubleTap: (() -> Unit)? = null,
) {
    val space = FolioTheme.space
    val start by rememberUpdatedState(onDragStart)
    val drag by rememberUpdatedState(onDrag)
    val end by rememberUpdatedState(onDragEnd)
    val doubleTap by rememberUpdatedState(onDoubleTap)
    val position = remember { GripPosition() }
    Box(
        modifier =
            modifier
                .size(space.touchTarget)
                .onGloballyPositioned { position.inRoot = it.positionInRoot() }
                .semantics { contentDescription = if (onDoubleTap != null) "Move toolbar, double-tap to collapse" else "Move toolbar" }
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { doubleTap?.invoke() }) }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { start(position.inRoot) },
                        onDragEnd = { end() },
                        onDragCancel = { end() },
                    ) { change, amount ->
                        change.consume()
                        drag(amount)
                    }
                },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = FolioIcons.GripVertical,
            contentDescription = null,
            modifier = Modifier.size(space.iconToolbar).then(if (vertical) Modifier.rotate(RAIL_GRIP_ROTATION_DEG) else Modifier),
            tint = FolioTheme.colors.textSecondary,
        )
    }
}

private class GripPosition {
    var inRoot = Offset.Zero
}

/**
 * Floating toolbar (10-editor-ui.md#toolbar-docking): a compact pill with the [grip] and the tools, or only
 * the current tool when [collapsed] (tap it to expand); the [options] row hangs below it.
 */
@Composable
internal fun FloatingToolbar(
    tool: EditorTool,
    collapsed: Boolean,
    onSelectTool: (EditorTool) -> Unit,
    onExpand: () -> Unit,
    grip: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    options: @Composable () -> Unit = {},
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(FolioTheme.space.s8)) {
        PillGroup {
            grip()
            if (collapsed) {
                ToolButton(icon = tool.icon, contentDescription = "${tool.label}, expand toolbar", selected = true, onClick = onExpand)
            } else {
                ToolsPill(tool, onSelectTool)
            }
        }
        if (!collapsed) options()
    }
}

/** Popover position for a toolbar docked at [mode]: always toward the canvas center. */
internal val DockMode.popoverAnchor: PopoverAnchor
    get() =
        when (this) {
            DockMode.TOP -> PopoverAnchor.BelowRow
            DockMode.LEFT -> PopoverAnchor.RightOfRail
            DockMode.RIGHT -> PopoverAnchor.LeftOfRail
            DockMode.FLOATING -> PopoverAnchor.Center
        }

/**
 * Dock change animation: a docked toolbar springs in from its edge (FolioMotion spring) while the old one
 * leaves toward its own edge; a floating pill appears where the drag released it, without a transition.
 */
internal fun AnimatedContentTransitionScope<DockMode>.dockTransition(
    motion: FolioMotion,
    slidePx: Int,
): ContentTransform {
    val enter =
        when (targetState) {
            DockMode.TOP -> slideInVertically(motion.spring()) { -slidePx } + fadeIn(motion.standard())
            DockMode.LEFT -> slideInHorizontally(motion.spring()) { -slidePx } + fadeIn(motion.standard())
            DockMode.RIGHT -> slideInHorizontally(motion.spring()) { slidePx } + fadeIn(motion.standard())
            DockMode.FLOATING -> EnterTransition.None
        }
    val exit =
        when (initialState) {
            DockMode.TOP -> slideOutVertically(motion.spring()) { -slidePx } + fadeOut(motion.fast())
            DockMode.LEFT -> slideOutHorizontally(motion.spring()) { -slidePx } + fadeOut(motion.fast())
            DockMode.RIGHT -> slideOutHorizontally(motion.spring()) { slidePx } + fadeOut(motion.fast())
            DockMode.FLOATING -> fadeOut(motion.fast())
        }
    return (enter togetherWith exit).using(SizeTransform(clip = false))
}

/** Slide distance of [dockTransition] in px. */
@Composable
internal fun dockSlidePx(): Int = with(LocalDensity.current) { DOCK_SLIDE.roundToPx() }

/** Measures the visible floating pill into [drag]. */
internal fun Modifier.floatingPill(drag: ToolbarDrag): Modifier = onSizeChanged { drag.pillSize = it }
