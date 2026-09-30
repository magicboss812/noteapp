package dev.folio.core.ink.input

import android.view.InputDevice
import android.view.MotionEvent

/**
 * What the connected stylus reports (06-ink-input.md#stylus-capabilities): detected from the pen
 * digitizer's motion ranges, then refined by observed events ([observe]). Features gated on a
 * capability (pencil tilt shading, hover cursor, button eraser) check it here and never assume it.
 */
data class StylusCapabilities(
    /** Pressure axis with a real range. */
    val pressure: Boolean,
    /** Tilt axis (altitude). */
    val tilt: Boolean,
    /** Orientation axis (azimuth). */
    val orientation: Boolean,
    /** Hover events (distance axis, or hover observed). */
    val hover: Boolean,
    /** Stylus button state reaches the app (only ever observed; P01-S7 saw none). */
    val primaryButton: Boolean,
) {
    /**
     * This, extended by what pointer [pointerIndex] of [event] shows: hover actions, a nonzero tilt,
     * stylus button state. Returns this instance when nothing is new (no allocation).
     */
    fun observe(
        event: MotionEvent,
        pointerIndex: Int,
    ): StylusCapabilities {
        // HOT PATH: per stylus down and hover event.
        val nextHover = hover || event.isHoverAction()
        val nextTilt = tilt || event.getAxisValue(MotionEvent.AXIS_TILT, pointerIndex) > 0f
        val nextButton = primaryButton || event.buttonState and STYLUS_BUTTONS != 0
        if (nextHover == hover && nextTilt == tilt && nextButton == primaryButton) return this
        return copy(hover = nextHover, tilt = nextTilt, primaryButton = nextButton)
    }

    /** Detection. */
    companion object {
        /** Nothing known (no stylus seen yet). */
        val NONE = StylusCapabilities(pressure = false, tilt = false, orientation = false, hover = false, primaryButton = false)

        private const val STYLUS_BUTTONS = MotionEvent.BUTTON_STYLUS_PRIMARY or MotionEvent.BUTTON_STYLUS_SECONDARY

        /** Capabilities from the axes a device declares; [hasAxis] answers for `MotionEvent.AXIS_*`. */
        fun fromAxes(hasAxis: (Int) -> Boolean): StylusCapabilities =
            StylusCapabilities(
                pressure = hasAxis(MotionEvent.AXIS_PRESSURE),
                tilt = hasAxis(MotionEvent.AXIS_TILT),
                orientation = hasAxis(MotionEvent.AXIS_ORIENTATION),
                hover = hasAxis(MotionEvent.AXIS_DISTANCE),
                primaryButton = false,
            )

        /** Capabilities of [device] as a stylus source; [NONE] for null or a device without stylus ranges. */
        fun detect(device: InputDevice?): StylusCapabilities {
            if (device == null || !device.supportsSource(InputDevice.SOURCE_STYLUS)) return NONE
            return fromAxes { axis -> (device.getMotionRange(axis, InputDevice.SOURCE_STYLUS)?.range ?: 0f) > 0f }
        }

        private fun MotionEvent.isHoverAction(): Boolean =
            when (actionMasked) {
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_EXIT -> true
                else -> false
            }
    }
}
