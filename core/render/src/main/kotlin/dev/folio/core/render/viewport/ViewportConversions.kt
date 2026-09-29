package dev.folio.core.render.viewport

import dev.folio.core.model.PageId
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import kotlin.math.max
import kotlin.math.min

/** View position of page point [pt] on page [pageId]. */
fun Viewport.toViewPx(
    pageId: PageId,
    pt: PointPt,
): PointPx {
    val i = pageIndex(pageId)
    val x = offsetXPx + (originXPt(i).toDouble() + pt.x) * scale
    val y = offsetYPx + (originYPt(i).toDouble() + pt.y) * scale
    return PointPx(x.toFloat(), y.toFloat())
}

/** Page point on page [pageId] under view position [px]. */
fun Viewport.toPagePt(
    pageId: PageId,
    px: PointPx,
): PointPt {
    val i = pageIndex(pageId)
    val x = (px.x - offsetXPx) / scale - originXPt(i)
    val y = (px.y - offsetYPx) / scale - originYPt(i)
    return PointPt(x.toFloat(), y.toFloat())
}

/** Visible part of page [pageId] in its page space; [RectPt.EMPTY] if off screen. Not clipped in canvas mode. */
fun Viewport.visibleRectPt(pageId: PageId): RectPt {
    val i = pageIndex(pageId)
    val left = (-offsetXPx / scale - originXPt(i)).toFloat()
    val top = (-offsetYPx / scale - originYPt(i)).toFloat()
    val view = RectPt(left, top, left + viewWidthPx / scale, top + viewHeightPx / scale)
    if (mode is ViewportMode.Canvas) return view
    val frame = requireNotNull(layout).framePt(i)
    if (!view.intersects(frame)) return RectPt.EMPTY
    return RectPt(max(view.left, frame.left), max(view.top, frame.top), min(view.right, frame.right), min(view.bottom, frame.bottom))
}

/** Stack indices of the pages on screen (the canvas page alone in canvas mode). */
fun Viewport.visiblePages(): IntRange {
    val current = layout ?: return IntRange.EMPTY
    val canvas = mode as? ViewportMode.Canvas
    if (canvas != null) return current.indexOf(canvas.pageId).let { it..it }
    return current.visibleRange((-offsetYPx / scale).toFloat(), ((viewHeightPx - offsetYPx) / scale).toFloat())
}

private fun Viewport.pageIndex(pageId: PageId): Int {
    val canvas = mode as? ViewportMode.Canvas
    require(canvas == null || canvas.pageId == pageId) { "page ${pageId.value} is not the canvas page" }
    val i = requireNotNull(layout) { "setPages first" }.indexOf(pageId)
    require(i >= 0) { "unknown page ${pageId.value}" }
    return i
}

private fun Viewport.originXPt(index: Int): Float = if (mode is ViewportMode.Canvas) 0f else requireNotNull(layout).originXPt(index)

private fun Viewport.originYPt(index: Int): Float = if (mode is ViewportMode.Canvas) 0f else requireNotNull(layout).originYPt(index)
