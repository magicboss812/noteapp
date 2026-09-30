package dev.folio.feature.editor.canvas

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import dev.folio.core.ink.input.HoverTarget
import dev.folio.core.render.viewport.Viewport
import kotlin.math.max

/** Where the hover ring draws: a view on the device, a recorder in tests. */
internal interface HoverRing {
    /** Shows the ring centered at ([xPx], [yPx]) with [radiusPx], view px. */
    fun show(
        xPx: Float,
        yPx: Float,
        radiusPx: Float,
    )

    /** Hides the ring. */
    fun hide()
}

/**
 * The hover cursor (06-ink-input.md#stylus-capabilities): while the pen hovers and [enabled] (the
 * stylus reports hover and the user keeps the ring on), a ring as large as the mark the pen would make
 * ([radiusPt], page points: half the brush width, or the eraser radius) follows it, at least
 * [minRadiusPx] so thin pens still show one. It hides when the pen leaves or touches down.
 */
@MainThread
internal class HoverCursor(
    private val ring: HoverRing,
    private val viewport: Viewport,
    private val enabled: () -> Boolean,
    private val radiusPt: (MotionEvent) -> Float,
    private val minRadiusPx: Float,
) : HoverTarget {
    override fun onHover(event: MotionEvent) {
        // HOT PATH: per hover event (about 450 Hz); no allocation.
        if (!enabled()) {
            ring.hide()
            return
        }
        ring.show(event.x, event.y, max(radiusPt(event) * viewport.scale, minRadiusPx))
    }

    override fun onHoverEnd() = ring.hide()
}

/**
 * [HoverRing] as a plain view above the overlay slot: a dark ring inside a light one, visible on paper
 * and on dark PDF pages. Moving it redraws only this view, never Compose (05-canvas-rendering.md#layers).
 */
internal class HoverRingView(
    context: Context,
) : View(context),
    HoverRing {
    private val density = resources.displayMetrics.density
    private val inner = ringPaint(DARK_ARGB)
    private val outer = ringPaint(LIGHT_ARGB)
    private var xPx = 0f
    private var yPx = 0f
    private var radiusPx = 0f
    private var shown = false

    /** True while the ring is visible. */
    val isRingShown: Boolean get() = shown

    override fun show(
        xPx: Float,
        yPx: Float,
        radiusPx: Float,
    ) {
        // HOT PATH: per hover event.
        val unchanged = xPx == this.xPx && yPx == this.yPx && radiusPx == this.radiusPx
        if (shown && unchanged) return
        this.xPx = xPx
        this.yPx = yPx
        this.radiusPx = radiusPx
        shown = true
        invalidate()
    }

    override fun hide() {
        if (!shown) return
        shown = false
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        if (!shown) return
        val line = RING_DP * density
        canvas.drawCircle(xPx, yPx, radiusPx + line, outer)
        canvas.drawCircle(xPx, yPx, radiusPx, inner)
    }

    private fun ringPaint(argb: Int) =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = RING_DP * density
            color = argb
        }

    private companion object {
        const val RING_DP = 1f

        // Named cursor colors: neutral gray-900 at 70% inside white at 85% (no theme inside the canvas view).
        const val DARK_ARGB = 0xB3111827.toInt()
        const val LIGHT_ARGB = 0xD9FFFFFF.toInt()
    }
}
