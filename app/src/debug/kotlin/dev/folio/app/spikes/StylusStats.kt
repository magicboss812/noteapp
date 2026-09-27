package dev.folio.app.spikes

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Spike P01-S7 accumulator: what the Focus Pen delivers to an app. Plain numbers in, JSON out, so
 * it is testable without MotionEvents. Kept for the P01-S7 USER-CHECK; P03-T09 promotes it into StylusCapabilities or deletes it (D-002).
 */
internal class StylusStats {
    /** Observed min..max of one axis; empty until the first value. */
    class AxisRange {
        var min = Float.POSITIVE_INFINITY
            private set
        var max = Float.NEGATIVE_INFINITY
            private set
        val seen: Boolean get() = min <= max

        fun add(value: Float) {
            if (value < min) min = value
            if (value > max) max = value
        }
    }

    val pressure = AxisRange()
    val tiltRad = AxisRange()
    val orientationRad = AxisRange()
    val distance = AxisRange()
    val toolTypes = IntArray(TOOL_TYPES)
    var contactEvents = 0
        private set
    var hoverEvents = 0
        private set
    var buttonStatesSeen = 0
        private set
    val buttonPresses = sortedMapOf<Int, Int>()
    val keyCodes = sortedMapOf<Int, Int>()
    var maxHistory = 0
        private set
    private var historyTotal = 0L
    private val intervalsUs = LongArray(MAX_INTERVALS)
    private var intervalCount = 0
    private var lastSampleNs = NO_SAMPLE

    /** One pointer sample of a stylus event; [hovering] for ACTION_HOVER_*, else contact. */
    @Suppress("LongParameterList") // one argument per MotionEvent axis keeps this free of android.* types
    fun onSample(
        toolType: Int,
        hovering: Boolean,
        pressure: Float,
        tiltRad: Float,
        orientationRad: Float,
        distance: Float,
        buttonState: Int,
    ) {
        if (toolType in toolTypes.indices) toolTypes[toolType]++
        if (hovering) hoverEvents++ else contactEvents++
        if (!hovering) this.pressure.add(pressure)
        this.tiltRad.add(tiltRad)
        this.orientationRad.add(orientationRad)
        this.distance.add(distance)
        buttonStatesSeen = buttonStatesSeen or buttonState
    }

    /** Batch size of one MotionEvent (historical samples). */
    fun onBatch(historySize: Int) {
        if (historySize > maxHistory) maxHistory = historySize
        historyTotal += historySize
    }

    /** Time of a contact sample (current or historical) in ns; consecutive samples give the rate. */
    fun onSampleTime(timeNs: Long) {
        if (lastSampleNs != NO_SAMPLE && timeNs > lastSampleNs) {
            intervalsUs[intervalCount % MAX_INTERVALS] = (timeNs - lastSampleNs) / NS_PER_US
            intervalCount++
        }
        lastSampleNs = timeNs
    }

    /** Pen lifted: the next sample starts a new interval chain. */
    fun onGestureEnd() {
        lastSampleNs = NO_SAMPLE
    }

    fun onButtonPress(actionButton: Int) {
        buttonPresses[actionButton] = (buttonPresses[actionButton] ?: 0) + 1
    }

    fun onKey(keyCode: Int) {
        keyCodes[keyCode] = (keyCodes[keyCode] ?: 0) + 1
    }

    /** Median time between contact samples, or null before two samples. */
    fun medianIntervalMs(): Double? {
        val n = minOf(intervalCount, MAX_INTERVALS)
        if (n == 0) return null
        val sorted = intervalsUs.copyOf(n).apply { sort() }
        return sorted[n / 2] / US_PER_MS
    }

    fun toJson(): JsonObject =
        buildJsonObject {
            put("contactEvents", contactEvents)
            put("hoverEvents", hoverEvents)
            putJsonObject("toolTypes") { toolTypes.forEachIndexed { type, count -> if (count > 0) put(type.toString(), count) } }
            putRange("pressure", pressure)
            putRange("tiltRad", tiltRad)
            putRange("orientationRad", orientationRad)
            putRange("distance", distance)
            put("buttonStatesSeen", buttonStatesSeen)
            putJsonObject("buttonPresses") { buttonPresses.forEach { (button, count) -> put(button.toString(), count) } }
            putJsonObject("keyCodes") { keyCodes.forEach { (code, count) -> put(code.toString(), count) } }
            put("maxHistory", maxHistory)
            put("historyTotal", historyTotal)
            val interval = medianIntervalMs()
            put("medianIntervalMs", interval)
            put("sampleRateHz", interval?.let { MS_PER_S / it })
        }

    private fun kotlinx.serialization.json.JsonObjectBuilder.putRange(
        name: String,
        range: AxisRange,
    ) {
        if (!range.seen) return
        putJsonObject(name) {
            put("min", range.min)
            put("max", range.max)
        }
    }

    private companion object {
        const val TOOL_TYPES = 5 // MotionEvent.TOOL_TYPE_UNKNOWN..TOOL_TYPE_PALM
        const val MAX_INTERVALS = 4096
        const val NO_SAMPLE = -1L
        const val NS_PER_US = 1_000L
        const val US_PER_MS = 1_000.0
        const val MS_PER_S = 1_000.0
    }
}
