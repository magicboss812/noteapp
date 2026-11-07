package dev.folio.feature.editor.canvas

import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.render.viewport.pageIndexAt

/** The zoom a zoom command goes to from [zoom] in steps of [step]; null for page commands. */
internal fun CanvasCommand.zoomFrom(
    zoom: Float,
    step: Float,
): Float? =
    when (this) {
        CanvasCommand.ZOOM_IN -> zoom * step
        CanvasCommand.ZOOM_OUT -> zoom / step
        CanvasCommand.FIT_WIDTH -> 1f
        else -> null
    }

/**
 * The stack page index a page [command] goes to from the page at the view center; -1 for zoom commands, outside the
 * page stack or without pages.
 */
internal fun Viewport.pageTargetOf(command: CanvasCommand): Int {
    val size = layout?.size ?: 0
    if (mode !is ViewportMode.Stack || size == 0) return -1
    val current = pageIndexAt(viewHeightPx / 2f).coerceIn(0, size - 1)
    val target =
        when (command) {
            CanvasCommand.PREVIOUS_PAGE -> current - 1
            CanvasCommand.NEXT_PAGE -> current + 1
            CanvasCommand.FIRST_PAGE -> 0
            CanvasCommand.LAST_PAGE -> size - 1
            CanvasCommand.ZOOM_IN, CanvasCommand.ZOOM_OUT, CanvasCommand.FIT_WIDTH -> return -1
        }
    return target.coerceIn(0, size - 1)
}
