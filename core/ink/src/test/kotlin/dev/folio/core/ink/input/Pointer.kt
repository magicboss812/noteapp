package dev.folio.core.ink.input

import android.view.InputDevice
import android.view.MotionEvent

/** One pointer of a synthetic MotionEvent. */
internal data class Pointer(
    val id: Int,
    val toolType: Int,
    val x: Float = 100f,
    val y: Float = 100f,
    val touchMajor: Float = 20f,
    val tiltRad: Float = 0f,
)

internal fun finger(
    id: Int,
    x: Float = 100f,
    y: Float = 100f,
    touchMajor: Float = 20f,
) = Pointer(id, MotionEvent.TOOL_TYPE_FINGER, x, y, touchMajor)

internal fun pen(
    id: Int,
    x: Float = 100f,
    y: Float = 100f,
    tiltRad: Float = 0f,
) = Pointer(id, MotionEvent.TOOL_TYPE_STYLUS, x, y, tiltRad = tiltRad)

/** Builds a MotionEvent; [actionIndex] selects the pointer of POINTER_DOWN/UP. */
internal fun motion(
    action: Int,
    timeMs: Long,
    vararg pointers: Pointer,
    actionIndex: Int = 0,
    flags: Int = 0,
    buttonState: Int = 0,
): MotionEvent {
    val properties =
        Array(pointers.size) { i ->
            MotionEvent.PointerProperties().apply {
                id = pointers[i].id
                toolType = pointers[i].toolType
            }
        }
    val coords =
        Array(pointers.size) { i ->
            MotionEvent.PointerCoords().apply {
                x = pointers[i].x
                y = pointers[i].y
                pressure = 0.5f
                touchMajor = pointers[i].touchMajor
                setAxisValue(MotionEvent.AXIS_TILT, pointers[i].tiltRad)
            }
        }
    val stylus = pointers.any { it.toolType == MotionEvent.TOOL_TYPE_STYLUS || it.toolType == MotionEvent.TOOL_TYPE_ERASER }
    val source = if (stylus) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
    val fullAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
    return MotionEvent.obtain(0L, timeMs, fullAction, pointers.size, properties, coords, 0, buttonState, 1f, 1f, 0, 0, source, flags)
}
