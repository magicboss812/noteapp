package dev.folio.feature.editor.canvas

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.VelocityTracker
import android.widget.OverScroller
import androidx.annotation.MainThread
import dev.folio.core.ink.input.NavigationTarget
import dev.folio.core.render.viewport.Viewport
import kotlin.math.hypot
import kotlin.math.pow

/** Touch thresholds, px and px/s (from ViewConfiguration); [scrollPx] per wheel step. */
internal data class GestureConfig(
    val touchSlopPx: Float,
    val minFlingPxPerS: Float,
    val maxFlingPxPerS: Float,
    val scrollPx: Float = DEFAULT_SCROLL_PX,
) {
    private companion object {
        const val DEFAULT_SCROLL_PX = 64f
    }
}

/**
 * Finger and mouse pan and zoom on the [viewport] (06-ink-input.md#input-routing), fed by InputRouter:
 * one finger (or a mouse drag) pans after the touch slop and flings with an [OverScroller]; two fingers
 * pinch-zoom around their midpoint and pan with it; the wheel scrolls, Ctrl + wheel zooms at the cursor.
 * A gesture that starts with another pointer type is ignored until the next ACTION_DOWN; extra non-finger
 * pointers and third fingers are ignored. [onChanged] runs after every viewport change; [postFrame]
 * schedules a fling step on the next frame.
 */
@MainThread
internal class FingerGestures(
    private val viewport: Viewport,
    private val scroller: OverScroller,
    private val config: GestureConfig,
    private val onChanged: () -> Unit,
    private val postFrame: (Runnable) -> Unit,
) : NavigationTarget {
    private enum class Mode { NONE, IGNORED, PENDING, PAN, PINCH }

    private var mode = Mode.NONE
    private var velocity: VelocityTracker? = null
    private var firstId = NO_POINTER
    private var secondId = NO_POINTER
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var lastSpan = 0f
    private var flingLastX = 0
    private var flingLastY = 0
    private val flingStep = Runnable { stepFling() }

    /** True while a fling animation moves the viewport. */
    val isFlinging: Boolean get() = !scroller.isFinished

    /** Handles one event; returns true while a finger gesture is tracked. */
    fun onTouchEvent(event: MotionEvent): Boolean {
        // HOT PATH: per MotionEvent; no allocation (the VelocityTracker is reused).
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> onDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> onPointerDown(event)
            MotionEvent.ACTION_MOVE -> onMove(event)
            MotionEvent.ACTION_POINTER_UP -> onPointerUp(event)
            MotionEvent.ACTION_UP -> onUp(event)
            MotionEvent.ACTION_CANCEL -> mode = Mode.NONE
        }
        return mode != Mode.NONE && mode != Mode.IGNORED
    }

    override fun onNavigationEvent(event: MotionEvent) {
        onTouchEvent(event)
    }

    /** Ends the gesture where it is: no fling, later events ignored until the next ACTION_DOWN. */
    override fun cancelNavigation() {
        if (mode != Mode.NONE) mode = Mode.IGNORED
    }

    override fun onScroll(event: MotionEvent) {
        val v = event.getAxisValue(MotionEvent.AXIS_VSCROLL)
        val h = event.getAxisValue(MotionEvent.AXIS_HSCROLL)
        stopFling()
        if (event.metaState and KeyEvent.META_CTRL_ON != 0) {
            if (v == 0f) return
            viewport.zoomBy(WHEEL_ZOOM_STEP.pow(v), event.x, event.y)
        } else {
            if (v == 0f && h == 0f) return
            // Wheel up (v > 0) shows content above: the content moves down.
            viewport.panBy(-h * config.scrollPx, v * config.scrollPx)
        }
        onChanged()
    }

    /** Stops a running fling (new touch, programmatic viewport change). */
    fun stopFling() {
        scroller.forceFinished(true)
    }

    /** Frees the velocity tracker; the host calls this when detached. */
    fun release() {
        stopFling()
        velocity?.recycle()
        velocity = null
        mode = Mode.NONE
    }

    private fun onDown(event: MotionEvent) {
        stopFling()
        val tool = event.getToolType(0)
        if (tool != MotionEvent.TOOL_TYPE_FINGER && tool != MotionEvent.TOOL_TYPE_MOUSE) {
            mode = Mode.IGNORED
            return
        }
        firstId = event.getPointerId(0)
        secondId = NO_POINTER
        downX = event.x
        downY = event.y
        lastX = downX
        lastY = downY
        val tracker = velocity ?: VelocityTracker.obtain().also { velocity = it }
        tracker.clear()
        tracker.addMovement(event)
        mode = Mode.PENDING
    }

    private fun onPointerDown(event: MotionEvent) {
        if (mode != Mode.PENDING && mode != Mode.PAN) return
        val index = event.actionIndex
        if (event.getToolType(index) != MotionEvent.TOOL_TYPE_FINGER) return
        secondId = event.getPointerId(index)
        mode = Mode.PINCH
        val a = event.findPointerIndex(firstId)
        if (a < 0) return
        lastX = (event.getX(a) + event.getX(index)) / 2f
        lastY = (event.getY(a) + event.getY(index)) / 2f
        lastSpan = hypot(event.getX(a) - event.getX(index), event.getY(a) - event.getY(index))
    }

    private fun onMove(event: MotionEvent) {
        when (mode) {
            Mode.PENDING -> {
                velocity?.addMovement(event)
                val i = event.findPointerIndex(firstId)
                if (i < 0 || hypot(event.getX(i) - downX, event.getY(i) - downY) < config.touchSlopPx) return
                mode = Mode.PAN
                panTo(event.getX(i), event.getY(i))
            }

            Mode.PAN -> {
                velocity?.addMovement(event)
                val i = event.findPointerIndex(firstId)
                if (i >= 0) panTo(event.getX(i), event.getY(i))
            }

            Mode.PINCH -> {
                pinch(event)
            }

            Mode.NONE, Mode.IGNORED -> {
                Unit
            }
        }
    }

    private fun onPointerUp(event: MotionEvent) {
        if (mode != Mode.PINCH) return
        val leaving = event.getPointerId(event.actionIndex)
        if (leaving != firstId && leaving != secondId) return
        // Continue as a pan with the remaining finger, from its current position (no jump, no fling from the pinch).
        firstId = if (leaving == firstId) secondId else firstId
        secondId = NO_POINTER
        val i = event.findPointerIndex(firstId)
        if (i >= 0) {
            lastX = event.getX(i)
            lastY = event.getY(i)
        }
        velocity?.clear()
        mode = Mode.PAN
    }

    private fun onUp(event: MotionEvent) {
        val tracker = velocity
        if (mode == Mode.PAN && tracker != null) {
            tracker.addMovement(event)
            tracker.computeCurrentVelocity(MS_PER_S, config.maxFlingPxPerS)
            val vx = tracker.getXVelocity(firstId)
            val vy = tracker.getYVelocity(firstId)
            if (hypot(vx, vy) >= config.minFlingPxPerS) {
                flingLastX = 0
                flingLastY = 0
                scroller.fling(0, 0, vx.toInt(), vy.toInt(), Int.MIN_VALUE, Int.MAX_VALUE, Int.MIN_VALUE, Int.MAX_VALUE)
                postFrame(flingStep)
            }
        }
        mode = Mode.NONE
    }

    private fun panTo(
        x: Float,
        y: Float,
    ) {
        viewport.panBy(x - lastX, y - lastY)
        lastX = x
        lastY = y
        onChanged()
    }

    private fun pinch(event: MotionEvent) {
        val a = event.findPointerIndex(firstId)
        val b = event.findPointerIndex(secondId)
        if (a < 0 || b < 0) return
        val focusX = (event.getX(a) + event.getX(b)) / 2f
        val focusY = (event.getY(a) + event.getY(b)) / 2f
        val span = hypot(event.getX(a) - event.getX(b), event.getY(a) - event.getY(b))
        // Pan first so the zoom focus is the new midpoint: the doc point under the fingers follows them.
        viewport.panBy(focusX - lastX, focusY - lastY)
        if (lastSpan > 0f && span > 0f) viewport.zoomBy(span / lastSpan, focusX, focusY)
        lastX = focusX
        lastY = focusY
        lastSpan = span
        onChanged()
    }

    private fun stepFling() {
        // HOT PATH: one call per frame while flinging.
        if (!scroller.computeScrollOffset()) return
        val dx = scroller.currX - flingLastX
        val dy = scroller.currY - flingLastY
        flingLastX = scroller.currX
        flingLastY = scroller.currY
        if (dx == 0 && dy == 0) {
            postFrame(flingStep)
            return
        }
        val oldX = viewport.offsetXPx
        val oldY = viewport.offsetYPx
        viewport.panBy(dx.toFloat(), dy.toFloat())
        onChanged()
        val moved = viewport.offsetXPx != oldX || viewport.offsetYPx != oldY
        if (moved) postFrame(flingStep) else scroller.forceFinished(true) // stopped by the pan clamp
    }

    private companion object {
        const val NO_POINTER = -1
        const val MS_PER_S = 1000

        /** Zoom factor per Ctrl + wheel step. */
        const val WHEEL_ZOOM_STEP = 1.1f
    }
}
