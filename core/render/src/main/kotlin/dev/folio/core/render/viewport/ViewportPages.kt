package dev.folio.core.render.viewport

/**
 * Stack index of the page a pen touching view y [yPx] writes on: the card under it, else the nearest
 * card (gaps, above the first, below the last); the canvas page in canvas mode; -1 without pages.
 * No allocation.
 */
fun Viewport.pageIndexAt(yPx: Float): Int {
    val stack = layout ?: return -1
    val canvas = mode as? ViewportMode.Canvas
    if (canvas != null) return stack.indexOf(canvas.pageId)
    val yPt = ((yPx - offsetYPx) / scale).toFloat()
    val range = stack.visibleRange(yPt, yPt)
    if (!range.isEmpty()) return range.first
    val above = range.last // card ending above yPt, or -1
    val below = range.first // card starting below yPt, or size
    if (above < 0) return 0
    if (below >= stack.size) return stack.size - 1
    val gapAbove = yPt - (stack.cardTopPt(above) + stack.framePt(above).heightPt)
    val gapBelow = stack.cardTopPt(below) - yPt
    return if (gapAbove <= gapBelow) above else below
}

/** View x of the page-space origin of stack page [index] (no allocation). */
fun Viewport.pageOriginViewX(index: Int): Double = offsetXPx + originXPt(index).toDouble() * scale

/** View y of the page-space origin of stack page [index] (no allocation). */
fun Viewport.pageOriginViewY(index: Int): Double = offsetYPx + originYPt(index).toDouble() * scale
