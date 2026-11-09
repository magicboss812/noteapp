package dev.folio.feature.editor.canvas

import android.view.MotionEvent
import androidx.annotation.MainThread
import dev.folio.core.ink.ruler.RulerAngles
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Fingers on the ruler (06-ink-input.md#ruler): a gesture whose first finger lands on the shown ruler is the ruler's,
 * not a pan. One finger moves it; a second finger turns it around the fingers' midpoint, resting in 15 deg detents with
 * a light tick; a tap on the angle readout turns it back to 0 deg. A finger the palm rules refuse ([acceptsFinger]) is
 * not taken. A pen touching down meanwhile (the hand holds the ruler) gets that event and all later ones through
 * [forward], so it draws as usual.
 */
@MainThread
internal class RulerGestures(
    private val overlay: RulerOverlay,
    private val slopPx: Float,
    private val tapTimeoutMs: Long,
    private val readoutMinPx: Float,
    private val acceptsFinger: (MotionEvent, Int) -> Boolean,
    private val forward: (MotionEvent) -> Unit,
) {
    private var owning = false
    private var forwarding = false
    private var first = NO_POINTER
    private var second = NO_POINTER
    private var tapping = false
    private var downX = 0f
    private var downY = 0f

    // The pose and finger positions the current one- or two-finger phase started from.
    private var startX = 0f
    private var startY = 0f
    private var startDeg = 0f
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorRad = 0f
    private var detentShown = Float.NaN

    /** True while a gesture belongs to the ruler. */
    val active: Boolean get() = owning

    /** Takes [event] when its gesture is the ruler's (the router must not see it then); false otherwise. */
    fun onTouch(event: MotionEvent): Boolean {
        // HOT PATH: per MotionEvent; no allocation.
        if (event.actionMasked == MotionEvent.ACTION_DOWN) return down(event)
        if (!owning) return false
        if (forwarding) forward(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> pointerDown(event)
            MotionEvent.ACTION_MOVE -> move(event)
            MotionEvent.ACTION_POINTER_UP -> pointerUp(event)
            MotionEvent.ACTION_UP -> up(event)
            MotionEvent.ACTION_CANCEL -> end()
        }
        return true
    }

    private fun down(event: MotionEvent): Boolean {
        end()
        val x = event.getX(0)
        val y = event.getY(0)
        val onRuler = overlay.active?.contains(x, y) == true
        if (!onRuler || event.getToolType(0) != MotionEvent.TOOL_TYPE_FINGER || !acceptsFinger(event, 0)) return false
        owning = true
        first = event.getPointerId(0)
        tapping = overlay.readoutCovers(x, y, readoutMinPx)
        downX = x
        downY = y
        begin(event)
        return true
    }

    private fun pointerDown(event: MotionEvent) {
        val index = event.actionIndex
        when (event.getToolType(index)) {
            MotionEvent.TOOL_TYPE_STYLUS, MotionEvent.TOOL_TYPE_ERASER -> {
                if (!forwarding) {
                    forwarding = true
                    forward(event)
                }
            }

            MotionEvent.TOOL_TYPE_FINGER -> {
                if (second == NO_POINTER && first != NO_POINTER) {
                    second = event.getPointerId(index)
                    tapping = false
                    begin(event)
                }
            }
        }
    }

    private fun pointerUp(event: MotionEvent) {
        val id = event.getPointerId(event.actionIndex)
        if (id == first) {
            first = second
            second = NO_POINTER
        } else if (id == second) {
            second = NO_POINTER
        } else {
            return
        }
        tapping = false
        begin(event)
    }

    private fun up(event: MotionEvent) {
        val tapped = tapping && event.getPointerId(0) == first && event.eventTime - event.downTime <= tapTimeoutMs
        end()
        if (tapped) {
            val ruler = overlay.ruler
            overlay.place(ruler.centerXPx, ruler.centerYPx, 0f)
            overlay.detentFeedback()
        }
    }

    private fun end() {
        owning = false
        forwarding = false
        first = NO_POINTER
        second = NO_POINTER
        tapping = false
        detentShown = Float.NaN
    }

    // A new phase: the pose now and where its fingers are (the midpoint and direction for two).
    private fun begin(event: MotionEvent) {
        val ruler = overlay.ruler
        startX = ruler.centerXPx
        startY = ruler.centerYPx
        startDeg = ruler.angleDeg
        detentShown = if (RulerAngles.isDetent(startDeg)) startDeg else Float.NaN
        val a = event.findPointerIndex(first)
        val b = event.findPointerIndex(second)
        if (a < 0) return
        if (b >= 0) {
            anchorX = (event.getX(a) + event.getX(b)) / 2f
            anchorY = (event.getY(a) + event.getY(b)) / 2f
            anchorRad = atan2(event.getY(b) - event.getY(a), event.getX(b) - event.getX(a))
        } else {
            anchorX = event.getX(a)
            anchorY = event.getY(a)
        }
    }

    private fun move(event: MotionEvent) {
        val a = event.findPointerIndex(first)
        if (a < 0) return
        val b = event.findPointerIndex(second)
        if (b < 0) {
            val x = event.getX(a)
            val y = event.getY(a)
            if (tapping && hypot(x - downX, y - downY) >= slopPx) tapping = false
            overlay.place(startX + x - anchorX, startY + y - anchorY, startDeg)
            return
        }
        val midX = (event.getX(a) + event.getX(b)) / 2f
        val midY = (event.getY(a) + event.getY(b)) / 2f
        val rad = atan2(event.getY(b) - event.getY(a), event.getX(b) - event.getX(a))
        val deg = RulerAngles.snap(startDeg + (rad - anchorRad) * RAD_TO_DEG)
        // The ruler turns with the fingers around their midpoint, by the (detent-snapped) angle it shows.
        val turn = (deg - startDeg) / RAD_TO_DEG
        val dx = startX - anchorX
        val dy = startY - anchorY
        val c = cos(turn)
        val s = sin(turn)
        overlay.place(midX + dx * c - dy * s, midY + dx * s + dy * c, deg)
        if (RulerAngles.isDetent(deg)) {
            if (deg != detentShown) overlay.detentFeedback()
            detentShown = deg
        } else {
            detentShown = Float.NaN
        }
    }

    private companion object {
        const val NO_POINTER = -1
        const val RAD_TO_DEG = (180.0 / PI).toFloat()
    }
}
