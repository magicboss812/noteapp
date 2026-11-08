package dev.folio.core.ink.shape

import kotlin.math.hypot

/**
 * Tells when the pen held still during a stroke (06-ink-input.md#shape-recognition): no sample left a circle of
 * [radiusPx] around the anchor for [dwellMs]. The anchor is the first sample of the current stay; a sample outside the
 * circle starts a new one. Each stay reports at most once ([consume]). Feed screen px and event times (ms).
 */
class DwellDetector(
    private val radiusPx: Float,
    private val dwellMs: Long = DWELL_MS,
) {
    private var anchorX = 0f
    private var anchorY = 0f
    private var anchorMs = 0L
    private var consumed = true

    /** Sample index (as given to [start] and [move]) of the anchor; -1 before [start]. */
    var anchorIndex = -1
        private set

    /** A new stroke starts at ([xPx], [yPx]) at [timeMs], sample [index]. */
    fun start(
        xPx: Float,
        yPx: Float,
        timeMs: Long,
        index: Int,
    ) {
        anchor(xPx, yPx, timeMs, index)
    }

    /** The pen is at ([xPx], [yPx]) at [timeMs], sample [index]. */
    fun move(
        xPx: Float,
        yPx: Float,
        timeMs: Long,
        index: Int,
    ) {
        // HOT PATH: per stylus sample; no allocation.
        if (hypot(xPx - anchorX, yPx - anchorY) > radiusPx) anchor(xPx, yPx, timeMs, index)
    }

    /** Milliseconds until the current stay counts as a hold at [nowMs] (0: it does now); [Long.MAX_VALUE] after [consume]. */
    fun remainingMs(nowMs: Long): Long = if (consumed) Long.MAX_VALUE else (anchorMs + dwellMs - nowMs).coerceAtLeast(0L)

    /** The current stay was handled; the next one starts when the pen leaves the circle. */
    fun consume() {
        consumed = true
    }

    /** Stops reporting until the next [start]. */
    fun stop() {
        consumed = true
        anchorIndex = -1
    }

    private fun anchor(
        xPx: Float,
        yPx: Float,
        timeMs: Long,
        index: Int,
    ) {
        anchorX = xPx
        anchorY = yPx
        anchorMs = timeMs
        anchorIndex = index
        consumed = false
    }

    /** Defaults (06-ink-input.md#shape-recognition). */
    companion object {
        /** How long the pen stays still before a hold counts. */
        const val DWELL_MS = 450L

        /** How far the pen may wander during a hold, dp. */
        const val RADIUS_DP = 4f
    }
}
