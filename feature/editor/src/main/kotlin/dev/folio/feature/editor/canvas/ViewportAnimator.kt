package dev.folio.feature.editor.canvas

import android.view.Choreographer
import androidx.annotation.MainThread
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.offsetYForPageTop
import kotlin.math.min

/**
 * Scripted viewport motion, one step per display frame with smoothstep easing: zoom around the view
 * center and scrolling to a page. Drives the debug commands `zoom-anim` and `scroll-page` so frame
 * stats are repeatable (12-performance.md); later also animated "go to page".
 */
@MainThread
internal class ViewportAnimator(
    private val viewport: Viewport,
    private val onChanged: () -> Unit,
) {
    private enum class Kind { ZOOM, SCROLL }

    private val choreographer = Choreographer.getInstance()
    private val frame = Choreographer.FrameCallback(::onFrame)
    private var kind = Kind.ZOOM
    private var from = 0.0
    private var to = 0.0
    private var startNs = 0L
    private var durationNs = 0L

    /** True while an animation runs. */
    var isRunning = false
        private set

    /** Animates the zoom (times fit-width) from [fromZoom] to [toZoom] over [durationMs]. */
    fun zoom(
        fromZoom: Float,
        toZoom: Float,
        durationMs: Long,
    ) {
        viewport.zoomTo(fromZoom, viewport.viewWidthPx / 2f, viewport.viewHeightPx / 2f)
        onChanged()
        start(Kind.ZOOM, fromZoom.toDouble(), toZoom.toDouble(), durationMs)
    }

    /** Scrolls so stack page [index] starts at the view top, over [durationMs] (0 jumps). */
    fun scrollToPage(
        index: Int,
        durationMs: Long,
    ) {
        start(Kind.SCROLL, viewport.offsetYPx, viewport.offsetYForPageTop(index), durationMs)
    }

    /** Stops a running animation where it is. */
    fun cancel() {
        choreographer.removeFrameCallback(frame)
        isRunning = false
    }

    private fun start(
        next: Kind,
        fromValue: Double,
        toValue: Double,
        durationMs: Long,
    ) {
        cancel()
        kind = next
        from = fromValue
        to = toValue
        startNs = 0L
        durationNs = durationMs * NS_PER_MS
        if (durationNs <= 0L) {
            apply(1.0)
            return
        }
        isRunning = true
        choreographer.postFrameCallback(frame)
    }

    private fun onFrame(frameTimeNanos: Long) {
        // HOT PATH: one call per frame while animating.
        if (startNs == 0L) startNs = frameTimeNanos
        val t = min(1.0, (frameTimeNanos - startNs).toDouble() / durationNs)
        apply(t * t * (1.0 + 2.0 * (1.0 - t))) // smoothstep 3t^2 - 2t^3
        if (t < 1.0) choreographer.postFrameCallback(frame) else isRunning = false
    }

    private fun apply(eased: Double) {
        val value = from + (to - from) * eased
        when (kind) {
            Kind.ZOOM -> viewport.zoomTo(value.toFloat(), viewport.viewWidthPx / 2f, viewport.viewHeightPx / 2f)
            Kind.SCROLL -> viewport.panBy(0f, (value - viewport.offsetYPx).toFloat())
        }
        onChanged()
    }

    private companion object {
        const val NS_PER_MS = 1_000_000L
    }
}
