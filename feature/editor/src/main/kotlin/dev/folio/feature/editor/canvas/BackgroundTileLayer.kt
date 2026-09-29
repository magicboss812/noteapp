package dev.folio.feature.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.View
import dev.folio.core.common.PerfMonitor
import dev.folio.core.render.tiles.TileLayer
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.render.viewport.docToViewX
import dev.folio.core.render.viewport.docToViewY
import dev.folio.core.render.viewport.visiblePages

/**
 * Bottom layer (05-canvas-rendering.md#layers): the surroundings and, per visible page, a card with
 * shadow, 1 dp border and paper color, then the page's background tiles (template, later PDF) from
 * [tiles]. Cards are cheap rects, so a page never flashes blank before its tiles arrive.
 */
@SuppressLint("ViewConstructor") // created in code by CanvasHostView only
internal class BackgroundTileLayer(
    context: Context,
    private val viewport: Viewport,
    private val tiles: TileLayer,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val paperPaint = Paint()
    private val shadowPaint =
        Paint().apply {
            color = SURROUND_ARGB
            setShadowLayer(SHADOW_BLUR_DP * density, 0f, SHADOW_Y_DP * density, SHADOW_ARGB)
        }
    private val borderPaint =
        Paint().apply {
            style = Paint.Style.STROKE
            strokeWidth = density
            color = BORDER_ARGB
        }

    override fun onDraw(canvas: Canvas) =
        PerfMonitor.trace(SECTION_DRAW) {
            // HOT PATH: every frame during pan and zoom; no allocation besides the visible range.
            canvas.drawColor(SURROUND_ARGB)
            val stack = viewport.layout ?: return@trace
            val canvasMode = viewport.mode as? ViewportMode.Canvas
            if (canvasMode != null) {
                canvas.drawColor(stack.pages[stack.indexOf(canvasMode.pageId)].background.paperArgb)
            } else {
                drawCards(canvas)
            }
            tiles.beginFrame()
            val w = width.toFloat()
            val h = height.toFloat()
            viewport.forEachVisiblePage(w, h) { i, originX, originY, l, t, r, b ->
                tiles.draw(canvas, stack.pages[i].id.value, originX, originY, viewport.scale, l, t, r, b)
            }
        }

    private fun drawCards(canvas: Canvas) {
        val stack = viewport.layout ?: return
        val scale = viewport.scale
        val inset = borderPaint.strokeWidth / 2f
        for (i in viewport.visiblePages()) {
            val frame = stack.framePt(i)
            val left = viewport.docToViewX(stack.cardLeftPt(i)).toFloat()
            val top = viewport.docToViewY(stack.cardTopPt(i)).toFloat()
            val right = left + frame.widthPt * scale
            val bottom = top + frame.heightPt * scale
            canvas.drawRect(left, top, right, bottom, shadowPaint)
            paperPaint.color = stack.pages[i].background.paperArgb
            canvas.drawRect(left, top, right, bottom, paperPaint)
            canvas.drawRect(left - inset, top - inset, right + inset, bottom + inset, borderPaint)
        }
    }

    /** Constants and PerfMonitor sections. */
    companion object {
        /** Drawing the background layer (main thread, per frame). */
        const val SECTION_DRAW = "tiles:draw:bg"

        // 05-canvas-rendering.md#layers and 11-design-system.md#elevation (light theme; dark arrives with P04 theming).
        private const val SURROUND_ARGB = 0xFFE9ECF1.toInt()
        private const val BORDER_ARGB = 0xFFE3E6EB.toInt()
        private const val SHADOW_ARGB = 0x0F000000 // black 6%
        private const val SHADOW_BLUR_DP = 8f
        private const val SHADOW_Y_DP = 2f
    }
}
