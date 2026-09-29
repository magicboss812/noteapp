package dev.folio.feature.editor.canvas

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import dev.folio.core.model.PageRef
import dev.folio.core.render.template.TemplateRenderer
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.ViewportMode
import dev.folio.core.render.viewport.docToViewX
import dev.folio.core.render.viewport.docToViewY
import dev.folio.core.render.viewport.visiblePages
import kotlin.math.max
import kotlin.math.min

/**
 * Bottom layer (05-canvas-rendering.md#layers): the surroundings and, per visible page, a card with
 * shadow, 1 dp border, paper color and template (drawn directly; background tiles take over in P03-T04).
 */
@SuppressLint("ViewConstructor") // created in code by CanvasHostView only
internal class BackgroundTileLayer(
    context: Context,
    private val viewport: Viewport,
) : View(context) {
    private val density = resources.displayMetrics.density
    private val templates = TemplateRenderer()
    private val regionPt = RectF()
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

    override fun onDraw(canvas: Canvas) {
        // HOT PATH: every frame during pan and zoom; no allocation besides the visible range.
        canvas.drawColor(SURROUND_ARGB)
        val stack = viewport.layout ?: return
        val canvasMode = viewport.mode as? ViewportMode.Canvas
        val scale = viewport.scale
        if (canvasMode != null) {
            val page = stack.pages[stack.indexOf(canvasMode.pageId)]
            canvas.drawColor(page.background.paperArgb)
            val originX = viewport.docToViewX(0f).toFloat()
            val originY = viewport.docToViewY(0f).toFloat()
            regionPt.set(-originX / scale, -originY / scale, (width - originX) / scale, (height - originY) / scale)
            drawTemplate(canvas, page, originX, originY)
            return
        }
        for (i in viewport.visiblePages()) {
            val frame = stack.framePt(i)
            val left = viewport.docToViewX(stack.cardLeftPt(i)).toFloat()
            val top = viewport.docToViewY(stack.cardTopPt(i)).toFloat()
            val right = left + frame.widthPt * scale
            val bottom = top + frame.heightPt * scale
            canvas.drawRect(left, top, right, bottom, shadowPaint)
            paperPaint.color = stack.pages[i].background.paperArgb
            canvas.drawRect(left, top, right, bottom, paperPaint)
            val inset = borderPaint.strokeWidth / 2f
            canvas.drawRect(left - inset, top - inset, right + inset, bottom + inset, borderPaint)
            // Visible part of the card in page space (the card frame of an infinite page may start above/left of 0).
            val originX = viewport.docToViewX(stack.originXPt(i)).toFloat()
            val originY = viewport.docToViewY(stack.originYPt(i)).toFloat()
            regionPt.set(
                max(frame.left, -originX / scale),
                max(frame.top, -originY / scale),
                min(frame.right, (width - originX) / scale),
                min(frame.bottom, (height - originY) / scale),
            )
            drawTemplate(canvas, stack.pages[i], originX, originY)
        }
    }

    /** Draws the template of [page] in [regionPt]; page origin at view px ([originX], [originY]). Moves into tiles in P03-T04. */
    private fun drawTemplate(
        canvas: Canvas,
        page: PageRef,
        originX: Float,
        originY: Float,
    ) {
        if (regionPt.isEmpty) return
        val scale = viewport.scale
        canvas.save()
        canvas.translate(originX, originY)
        canvas.scale(scale, scale)
        templates.draw(canvas, page.background.template, page.spec, regionPt, scale)
        canvas.restore()
    }

    private companion object {
        // 05-canvas-rendering.md#layers and 11-design-system.md#elevation (light theme; dark arrives with P04 theming).
        const val SURROUND_ARGB = 0xFFE9ECF1.toInt()
        const val BORDER_ARGB = 0xFFE3E6EB.toInt()
        const val SHADOW_ARGB = 0x0F000000 // black 6%
        const val SHADOW_BLUR_DP = 8f
        const val SHADOW_Y_DP = 2f
    }
}
