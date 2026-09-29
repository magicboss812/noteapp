package dev.folio.feature.editor.canvas

import android.view.InputDevice
import android.view.MotionEvent

/** Builds MotionEvents for tests; pointer ids are firstPointerId + the index in [points] (x, y pairs). */
internal object TouchEvents {
    fun event(
        action: Int,
        timeMs: Long,
        vararg points: Pair<Float, Float>,
        toolType: Int = MotionEvent.TOOL_TYPE_FINGER,
        actionIndex: Int = 0,
        firstPointerId: Int = 0,
    ): MotionEvent {
        val properties =
            Array(points.size) { i ->
                MotionEvent.PointerProperties().apply {
                    id = firstPointerId + i
                    this.toolType = if (i == 0) toolType else MotionEvent.TOOL_TYPE_FINGER
                }
            }
        val coords =
            Array(points.size) { i ->
                MotionEvent.PointerCoords().apply {
                    x = points[i].first
                    y = points[i].second
                    pressure = 1f
                    size = 1f
                }
            }
        val fullAction = action or (actionIndex shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val source = if (toolType == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        return MotionEvent.obtain(0L, timeMs, fullAction, points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, source, 0)
    }
}
