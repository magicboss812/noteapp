package dev.folio.feature.editor.canvas

import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.render.viewport.docToViewX
import dev.folio.core.render.viewport.docToViewY
import dev.folio.core.render.viewport.visiblePages
import kotlin.math.max
import kotlin.math.min

/**
 * Calls [block] for every page on screen with its stack index, the view px of its page-space origin
 * and its visible part in page pt: clamped to the card frame in stack mode, the whole view in canvas
 * mode. No allocation beyond the visible index range.
 */
internal inline fun Viewport.forEachVisiblePage(
    viewWidthPx: Float,
    viewHeightPx: Float,
    block: (index: Int, originXPx: Float, originYPx: Float, leftPt: Float, topPt: Float, rightPt: Float, bottomPt: Float) -> Unit,
) {
    val stack = layout ?: return
    val s = scale
    val canvasMode = mode as? ViewportMode.Canvas
    if (canvasMode != null) {
        val originX = docToViewX(0f).toFloat()
        val originY = docToViewY(0f).toFloat()
        block(
            stack.indexOf(canvasMode.pageId),
            originX,
            originY,
            -originX / s,
            -originY / s,
            (viewWidthPx - originX) / s,
            (viewHeightPx - originY) / s,
        )
        return
    }
    for (i in visiblePages()) {
        val frame = stack.framePt(i)
        val originX = docToViewX(stack.originXPt(i)).toFloat()
        val originY = docToViewY(stack.originYPt(i)).toFloat()
        val left = max(frame.left, -originX / s)
        val top = max(frame.top, -originY / s)
        val right = min(frame.right, (viewWidthPx - originX) / s)
        val bottom = min(frame.bottom, (viewHeightPx - originY) / s)
        if (left < right && top < bottom) block(i, originX, originY, left, top, right, bottom)
    }
}
