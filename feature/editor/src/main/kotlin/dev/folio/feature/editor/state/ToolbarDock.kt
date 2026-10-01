package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable

/** Where the editor toolbar sits (10-editor-ui.md#toolbar-docking). */
enum class DockMode { TOP, LEFT, RIGHT, FLOATING }

/** Writing hand; side docking defaults to the opposite edge of the window from the hand's reach. */
enum class Handedness { RIGHT, LEFT }

/** Window orientation; the toolbar placement is stored per orientation. */
enum class ScreenOrientation { LANDSCAPE, PORTRAIT }

/** Side dock mode for [this] hand: left-handed docks RIGHT, right-handed LEFT. */
val Handedness.sideDock: DockMode
    get() = if (this == Handedness.LEFT) DockMode.RIGHT else DockMode.LEFT

/**
 * Toolbar placement in one orientation. [floatX]/[floatY] place the floating pill as a fraction (0..1) of the
 * free space around it, so it stays inside the window when the window or the pill changes size.
 */
@Immutable
data class ToolbarPlacement(
    val mode: DockMode = DockMode.TOP,
    val floatX: Float = DEFAULT_FLOAT_X,
    val floatY: Float = DEFAULT_FLOAT_Y,
    /** Floating pill collapsed to the current tool (double-tap the grip). */
    val collapsed: Boolean = false,
) {
    init {
        require(floatX in 0f..1f && floatY in 0f..1f) { "float position out of range: $floatX, $floatY" }
    }

    internal companion object {
        const val DEFAULT_FLOAT_X = 0.5f
        const val DEFAULT_FLOAT_Y = 0f
    }
}

/** Toolbar placements of both orientations and the handedness setting (stored together). */
@Immutable
data class ToolbarDocks(
    val handedness: Handedness = Handedness.RIGHT,
    val landscape: ToolbarPlacement = ToolbarPlacement(),
    val portrait: ToolbarPlacement = ToolbarPlacement(),
) {
    /** The placement for [orientation]. */
    fun placement(orientation: ScreenOrientation): ToolbarPlacement =
        if (orientation == ScreenOrientation.LANDSCAPE) landscape else portrait

    /** Replaces the placement of [orientation]. */
    fun with(
        orientation: ScreenOrientation,
        placement: ToolbarPlacement,
    ): ToolbarDocks = if (orientation == ScreenOrientation.LANDSCAPE) copy(landscape = placement) else copy(portrait = placement)

    /** Switches to [hand]; side-docked toolbars move to the new hand's side. */
    fun withHandedness(hand: Handedness): ToolbarDocks {
        fun ToolbarPlacement.moved() = if (mode == DockMode.LEFT || mode == DockMode.RIGHT) copy(mode = hand.sideDock) else this
        return ToolbarDocks(hand, landscape.moved(), portrait.moved())
    }

    /** Docks the toolbar of [orientation] at the side of the current handedness ("Side" in toolbar settings). */
    fun dockedToSide(orientation: ScreenOrientation): ToolbarDocks =
        with(orientation, placement(orientation).copy(mode = handedness.sideDock))
}

/** Where a released toolbar drag lands (10-editor-ui.md#toolbar-docking); all values in px of one space. */
internal object DockSnap {
    /**
     * Placement after releasing the floating pill with its top-left at ([leftPx], [topPx]) and size
     * [pillWidthPx] x [pillHeightPx] inside a [containerWidthPx] x [containerHeightPx] area. Within [edgePx] of
     * the left, top or right edge it docks there (the nearest edge wins; there is no bottom dock), otherwise it
     * floats where it was released, clamped inside the area. [current] keeps its collapsed state.
     */
    @Suppress("LongParameterList") // One rectangle in one container; a wrapper type would only be built to be unpacked.
    fun release(
        current: ToolbarPlacement,
        leftPx: Float,
        topPx: Float,
        pillWidthPx: Float,
        pillHeightPx: Float,
        containerWidthPx: Float,
        containerHeightPx: Float,
        edgePx: Float,
    ): ToolbarPlacement {
        val freeX = (containerWidthPx - pillWidthPx).coerceAtLeast(0f)
        val freeY = (containerHeightPx - pillHeightPx).coerceAtLeast(0f)
        val left = leftPx.coerceIn(0f, freeX)
        val top = topPx.coerceIn(0f, freeY)
        val toLeft = left
        val toTop = top
        val toRight = freeX - left
        val nearest = minOf(toLeft, toTop, toRight)
        val mode =
            when {
                nearest > edgePx -> DockMode.FLOATING
                nearest == toTop -> DockMode.TOP
                nearest == toLeft -> DockMode.LEFT
                else -> DockMode.RIGHT
            }
        return current.copy(
            mode = mode,
            floatX = if (freeX > 0f) left / freeX else current.floatX,
            floatY = if (freeY > 0f) top / freeY else current.floatY,
        )
    }
}
