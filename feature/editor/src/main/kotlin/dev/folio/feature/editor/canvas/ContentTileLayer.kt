package dev.folio.feature.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.view.View
import dev.folio.core.common.PerfMonitor
import dev.folio.core.render.tiles.TileLayer
import dev.folio.core.render.viewport.Viewport

/** Committed objects in z-order (05-canvas-rendering.md#layers), drawn from the content [tiles]. */
@SuppressLint("ViewConstructor") // created in code by CanvasHostView only
internal class ContentTileLayer(
    context: Context,
    private val viewport: Viewport,
    private val tiles: TileLayer,
) : View(context) {
    override fun onDraw(canvas: Canvas) =
        PerfMonitor.trace(SECTION_DRAW) {
            // HOT PATH: every frame during pan and zoom; no allocation besides the visible range.
            val stack = viewport.layout ?: return@trace
            tiles.beginFrame()
            viewport.forEachVisiblePage(width.toFloat(), height.toFloat()) { i, originX, originY, l, t, r, b ->
                tiles.draw(canvas, stack.pages[i].id.value, originX, originY, viewport.scale, l, t, r, b)
            }
        }

    /** PerfMonitor sections. */
    companion object {
        /** Drawing the content layer (main thread, per frame). */
        const val SECTION_DRAW = "tiles:draw:content"
    }
}
