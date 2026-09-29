package dev.folio.core.render.viewport

import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef

/** What the viewport shows. */
sealed interface ViewportMode {
    /** Fixed pages in a vertical column ([PageStackLayout]). */
    data object Stack : ViewportMode

    /** One infinite page, free 2D; doc space is its page space. */
    data class Canvas(
        val pageId: PageId,
    ) : ViewportMode
}

/** A point in view space (px, origin top-left of the canvas host). */
data class PointPx(
    val x: Float,
    val y: Float,
)

/**
 * Pan and zoom state of the canvas host (05-canvas-rendering.md#viewport). `view = doc * scale + offset`,
 * where doc space is stack space in [ViewportMode.Stack] and the canvas page's space in
 * [ViewportMode.Canvas]. Conversions live in ViewportConversions.kt. Mutable, main thread only; gesture
 * methods do not allocate.
 */
class Viewport(
    /** Display density (px per dp). */
    val density: Float,
) {
    /** Current mode. */
    var mode: ViewportMode = ViewportMode.Stack
        private set

    /** Current zoom in px per pt. */
    var scale: Float = 1f
        private set

    /** View x of the doc origin, px. Double: deep in a long stack at 8x it exceeds 10^6 px (float ulp 0.25 px). */
    var offsetXPx: Double = 0.0
        private set

    /** View y of the doc origin, px (Double, see [offsetXPx]). */
    var offsetYPx: Double = 0.0
        private set

    /** View width, px. */
    var viewWidthPx: Float = 0f
        private set

    /** View height, px. */
    var viewHeightPx: Float = 0f
        private set

    /** Current page stack; null until [setPages]. */
    var layout: PageStackLayout? = null
        private set

    /** Fit-width scale of the current mode (px per pt). */
    var fitWidthScale: Float = 1f
        private set

    /** Tile bucket for the current scale ([ZoomBuckets]). */
    val bucketIndex: Int get() = ZoomBuckets.indexFor(scale)

    /**
     * Lays out [pages] for a view of [widthPx] x [heightPx]. The first call shows fit-width at the first
     * page top; later calls keep the zoom relative to fit-width and the doc point at the view center.
     */
    fun setPages(
        pages: List<PageRef>,
        widthPx: Float,
        heightPx: Float,
    ) {
        val old = layout
        val zoom = scale / fitWidthScale
        val centerX = (viewWidthPx / 2.0 - offsetXPx) / scale
        val centerY = (viewHeightPx / 2.0 - offsetYPx) / scale
        val next = PageStackLayout(pages, widthPx, density)
        layout = next
        viewWidthPx = widthPx
        viewHeightPx = heightPx
        val canvas = mode as? ViewportMode.Canvas
        if (canvas != null && next.indexOf(canvas.pageId) < 0) mode = ViewportMode.Stack
        fitWidthScale = fitScaleFor(next, mode)
        if (old == null) {
            fitWidth()
            return
        }
        scale = (zoom * fitWidthScale).coerceIn(MIN_ZOOM * fitWidthScale, MAX_ZOOM * fitWidthScale)
        // Stack space stretches when the gap or padding changes; keep the same relative position.
        val stack = mode is ViewportMode.Stack
        val sx = if (stack) next.contentWidthPt / old.contentWidthPt else 1f
        val sy = if (stack) next.contentHeightPt / old.contentHeightPt else 1f
        offsetXPx = widthPx / 2.0 - centerX * sx * scale
        offsetYPx = heightPx / 2.0 - centerY * sy * scale
        clampOffsets()
    }

    /** Switches to [newMode] at fit-width. */
    fun show(newMode: ViewportMode) {
        val current = requireNotNull(layout) { "setPages first" }
        if (newMode is ViewportMode.Canvas) require(current.indexOf(newMode.pageId) >= 0) { "unknown page" }
        mode = newMode
        fitWidthScale = fitScaleFor(current, newMode)
        fitWidth()
    }

    /** Fit-width scale; the stack scrolled to its top, a canvas with its origin inset by the side padding. */
    fun fitWidth() {
        scale = fitWidthScale
        val insetPx = if (mode is ViewportMode.Stack) 0.0 else SIDE_PADDING_DP * density.toDouble()
        offsetXPx = insetPx
        offsetYPx = insetPx
        clampOffsets()
    }

    /**
     * Multiplies the zoom by [factor] (clamped to [MIN_ZOOM]..[MAX_ZOOM] of fit-width) keeping the doc
     * point under ([focusXPx], [focusYPx]) fixed, unless the stack pan clamp has to move it.
     */
    fun zoomBy(
        factor: Float,
        focusXPx: Float,
        focusYPx: Float,
    ) {
        // HOT PATH: runs per pinch event.
        val newScale = (scale * factor).coerceIn(MIN_ZOOM * fitWidthScale, MAX_ZOOM * fitWidthScale)
        val docX = (focusXPx - offsetXPx) / scale
        val docY = (focusYPx - offsetYPx) / scale
        scale = newScale
        offsetXPx = focusXPx - docX * newScale
        offsetYPx = focusYPx - docY * newScale
        clampOffsets()
    }

    /** Moves the content by ([dxPx], [dyPx]); clamped in stack mode, free in canvas mode. */
    fun panBy(
        dxPx: Float,
        dyPx: Float,
    ) {
        // HOT PATH: runs per pan event and fling frame.
        offsetXPx += dxPx
        offsetYPx += dyPx
        clampOffsets()
    }

    /**
     * Stack mode: vertically at most half a screen of empty space past the first or last card;
     * horizontally centered while the column is narrower than the view, else clamped to its edges.
     */
    private fun clampOffsets() {
        val current = layout ?: return
        if (mode !is ViewportMode.Stack) return
        val contentWidthPx = current.contentWidthPt.toDouble() * scale
        offsetXPx =
            if (contentWidthPx <= viewWidthPx) {
                (viewWidthPx - contentWidthPx) / 2.0
            } else {
                offsetXPx.coerceIn(viewWidthPx - contentWidthPx, 0.0)
            }
        val halfPx = viewHeightPx / 2.0
        offsetYPx = offsetYPx.coerceIn(halfPx - current.contentHeightPt.toDouble() * scale, halfPx)
    }

    private fun fitScaleFor(
        stack: PageStackLayout,
        target: ViewportMode,
    ): Float =
        when (target) {
            ViewportMode.Stack -> {
                stack.fitWidthScale
            }

            is ViewportMode.Canvas -> {
                val originWidthPt = stack.pages[stack.indexOf(target.pageId)].spec.widthPt
                (viewWidthPx - 2 * SIDE_PADDING_DP * density) / originWidthPt
            }
        }

    /** Zoom limits relative to fit-width. */
    companion object {
        /** Smallest zoom, times fit-width. */
        const val MIN_ZOOM = 0.2f

        /** Largest zoom, times fit-width. */
        const val MAX_ZOOM = 8f

        private const val SIDE_PADDING_DP = 24f
    }
}
