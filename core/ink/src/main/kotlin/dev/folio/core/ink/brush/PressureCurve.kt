package dev.folio.core.ink.brush

import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Pressure curve `p' = p^gamma` (06-ink-input.md#brushes). Brush families bake the curve into their
 * pressure behaviors as a piecewise-linear response, so wet and dry ink agree and stored inputs keep
 * the raw pressure.
 */
object PressureCurve {
    /** Softest curve (light touch already gives a wide line). */
    const val MIN_GAMMA = 0.5f

    /** Hardest curve. */
    const val MAX_GAMMA = 2f

    /** Gamma values are quantized to 1 / this (0.05), which bounds the number of cached families. */
    const val GAMMA_STEPS_PER_UNIT = 20f

    /** Interior sample count of the piecewise-linear response (endpoints 0 and 1 are implied). */
    const val SAMPLES = 15

    /** [gamma] clamped to the settings range and rounded to steps of 0.05; NaN means linear (1). */
    fun normalize(gamma: Float): Float {
        if (gamma.isNaN()) return 1f
        val steps = (gamma.coerceIn(MIN_GAMMA, MAX_GAMMA) * GAMMA_STEPS_PER_UNIT).roundToInt()
        return steps / GAMMA_STEPS_PER_UNIT
    }

    /** True when [gamma] (normalized) needs no response curve. */
    fun isLinear(gamma: Float): Boolean = normalize(gamma) == 1f

    /** Interior points of the response curve as x0, y0, x1, y1, ... with x evenly spaced in (0, 1). */
    fun points(gamma: Float): FloatArray {
        val g = normalize(gamma).toDouble()
        val out = FloatArray(SAMPLES * 2)
        for (i in 0 until SAMPLES) {
            val x = (i + 1).toFloat() / (SAMPLES + 1)
            out[i * 2] = x
            out[i * 2 + 1] = x.toDouble().pow(g).toFloat()
        }
        return out
    }
}
