package dev.folio.app.spikes

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Environment
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import androidx.annotation.MainThread
import dev.folio.app.BuildConfig
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException

/**
 * Spike P01-S7: live readout of what the Focus Pen delivers (tool type, pressure, tilt,
 * orientation, distance, hover, buttons, keys, batch sizes, sample rate) plus the stylus
 * InputDevice motion ranges; writes `Folio-Debug/probe/stylus.json` every second while it changes.
 * Kept for the P01-S7 USER-CHECK; removed by P03-T09 (STATUS D-002).
 */
@SuppressLint("ViewConstructor") // created in code by AppDebugHooks only
@MainThread
internal class SpikeStylusView(
    context: Context,
    private val dispatchers: FolioDispatchers,
) : View(context) {
    private val scope = CoroutineScope(SupervisorJob() + dispatchers.main)
    private val output = File(File(Environment.getExternalStorageDirectory(), BuildConfig.LIBRARY_ROOT), "probe/stylus.json")
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = TEXT_PX }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private var stats = StylusStats()
    private var dirty = true
    private var lastX = -1f
    private var lastY = -1f
    private var lastPressure = 0f
    private var lastLine = ""

    /** Last write error, or null. */
    var writeError: String? = null
        private set

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        setBackgroundColor(BACKGROUND_ARGB)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        requestFocus()
        scope.launch {
            while (isActive) {
                if (dirty) write(snapshot())
                delay(WRITE_INTERVAL_MS)
            }
        }
    }

    override fun onDetachedFromWindow() {
        scope.cancel()
        super.onDetachedFromWindow()
    }

    /** Clears all counters. */
    fun reset() {
        stats = StylusStats()
        dirty = true
        invalidate()
    }

    /** Devices with stylus sources and their motion ranges, plus the accumulated stats. */
    fun snapshot(): JsonObject =
        buildJsonObject {
            put("stats", stats.toJson())
            put("devices", stylusDevices())
            put("writeError", writeError)
        }

    @SuppressLint("ClickableViewAccessibility") // probe surface, no click action
    override fun onTouchEvent(event: MotionEvent): Boolean {
        record(event, hovering = false)
        when (event.actionMasked) {
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> stats.onGestureEnd()
            MotionEvent.ACTION_BUTTON_PRESS -> stats.onButtonPress(event.actionButton)
        }
        return true
    }

    override fun onHoverEvent(event: MotionEvent): Boolean {
        record(event, hovering = true)
        return true
    }

    override fun onGenericMotionEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_BUTTON_PRESS) stats.onButtonPress(event.actionButton)
        record(
            event,
            hovering =
                event.actionMasked != MotionEvent.ACTION_BUTTON_PRESS && event.actionMasked != MotionEvent.ACTION_BUTTON_RELEASE,
        )
        return true
    }

    /** Records every key that reaches the app (pen buttons might arrive as keys); BACK still navigates. */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.keyCode == KeyEvent.KEYCODE_BACK) return super.dispatchKeyEvent(event)
        if (event.action == KeyEvent.ACTION_DOWN) {
            stats.onKey(event.keyCode)
            lastLine = "key ${KeyEvent.keyCodeToString(event.keyCode)} source=0x${event.source.toString(HEX)}"
            dirty = true
            invalidate()
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        var y = TEXT_PX * 2
        val lines = listOf(lastLine) + stats.toJson().entries.map { (key, value) -> "$key: $value" }
        for (line in lines) {
            canvas.drawText(line, TEXT_PX, y, text)
            y += TEXT_PX * LINE_SPACING
        }
        writeError?.let { canvas.drawText("write error: $it", TEXT_PX, y, text) }
        if (lastX >= 0f) {
            dot.alpha = (OPAQUE * lastPressure.coerceIn(MIN_DOT_ALPHA, 1f)).toInt()
            canvas.drawCircle(lastX, lastY, DOT_RADIUS_PX, dot)
        }
    }

    private fun record(
        event: MotionEvent,
        hovering: Boolean,
    ) {
        val pointer = event.actionIndex
        val toolType = event.getToolType(pointer)
        stats.onBatch(event.historySize)
        if (!hovering) {
            for (h in 0 until event.historySize) stats.onSampleTime(event.getHistoricalEventTimeNanos(h))
            stats.onSampleTime(event.eventTimeNanos)
        }
        stats.onSample(
            toolType = toolType,
            hovering = hovering,
            pressure = event.getPressure(pointer),
            tiltRad = event.getAxisValue(MotionEvent.AXIS_TILT, pointer),
            orientationRad = event.getOrientation(pointer),
            distance = event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointer),
            buttonState = event.buttonState,
        )
        lastX = event.getX(pointer)
        lastY = event.getY(pointer)
        lastPressure = event.getPressure(pointer)
        lastLine =
            "${MotionEvent.actionToString(event.actionMasked)} tool=$toolType p=%.3f tilt=%.2f orient=%.2f dist=%.2f buttons=%d hist=%d"
                .format(
                    lastPressure,
                    event.getAxisValue(MotionEvent.AXIS_TILT, pointer),
                    event.getOrientation(pointer),
                    event.getAxisValue(MotionEvent.AXIS_DISTANCE, pointer),
                    event.buttonState,
                    event.historySize,
                )
        dirty = true
        invalidate()
    }

    private fun stylusDevices() =
        buildJsonArray {
            for (id in InputDevice.getDeviceIds()) {
                val device = InputDevice.getDevice(id) ?: continue
                if (!device.supportsSource(InputDevice.SOURCE_STYLUS)) continue
                add(
                    buildJsonObject {
                        put("name", device.name)
                        put("sources", "0x" + device.sources.toString(HEX))
                        put(
                            "ranges",
                            buildJsonArray {
                                for (range in device.motionRanges) {
                                    add(
                                        buildJsonObject {
                                            put("axis", MotionEvent.axisToString(range.axis))
                                            put("source", "0x" + range.source.toString(HEX))
                                            put("min", range.min)
                                            put("max", range.max)
                                            put("resolution", range.resolution)
                                            put("fuzz", range.fuzz)
                                        },
                                    )
                                }
                            },
                        )
                    },
                )
            }
        }

    private suspend fun write(json: JsonObject) {
        dirty = false
        writeError =
            withContext(dispatchers.io) {
                try {
                    output.parentFile?.mkdirs()
                    val tmp = File(output.parentFile, output.name + ".tmp")
                    tmp.writeText(json.toString())
                    if (!tmp.renameTo(output)) throw IOException("rename to ${output.name} failed")
                    null
                } catch (e: IOException) {
                    FolioLog.w(TAG, "probe write failed", e)
                    e.message
                }
            }
    }

    companion object {
        const val ROUTE = "spike-stylus"
        private const val TAG = "SpikeStylus"
        private const val TEXT_PX = 30f
        private const val LINE_SPACING = 1.4f
        private const val DOT_RADIUS_PX = 24f
        private const val OPAQUE = 255
        private const val MIN_DOT_ALPHA = 0.15f
        private const val WRITE_INTERVAL_MS = 1_000L
        private const val HEX = 16
        private const val BACKGROUND_ARGB = 0xFFFFFFFF.toInt()
    }
}
