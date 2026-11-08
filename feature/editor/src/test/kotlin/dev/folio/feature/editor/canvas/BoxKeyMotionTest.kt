package dev.folio.feature.editor.canvas

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.GridUnit
import org.junit.Test

/** Arrow key box motion (P07-T14, 10-editor-ui.md#text-boxes Move): speed curve, line pacing, offsets. */
class BoxKeyMotionTest {
    private val grid = GridUnit(20f, 0f, 36f, snapsColumns = false)
    private val motion = BoxKeyMotion()

    private fun ns(seconds: Float): Long = (seconds * 1e9f).toLong()

    // Holds [key] from 0 to [seconds], advancing at 144 Hz; the sum of what each frame moved.
    private fun hold(
        key: BoxKey,
        seconds: Float,
        smooth: Boolean = false,
        topPt: Float = 36f + 5f * 20f,
    ): Triple<Float, Float, Int> {
        motion.press(key, smooth, 0L)
        var dx = 0f
        var top = topPt
        var steps = 0
        motion.advance(0L, 1f)
        dx += motion.dxPt
        top = motion.topAfter(grid, top)
        steps += motion.lineSteps
        var t = 0f
        while (t < seconds) {
            t = minOf(seconds, t + 1f / 144f)
            motion.advance(ns(t), 1f)
            dx += motion.dxPt
            top = motion.topAfter(grid, top)
            steps += motion.lineSteps
        }
        motion.release(key)
        return Triple(dx, top, steps)
    }

    @Test
    fun curve_pressMovesOnePoint_rampsQuadratically_thenHoldsTheTopSpeed() {
        assertThat(BoxKeyCurve.distanceDp(0f)).isEqualTo(0f)
        // Quadratic speed: the distance grows with t^3 during the ramp.
        val quarter = BoxKeyCurve.distanceDp(0.2f)
        val half = BoxKeyCurve.distanceDp(0.4f)
        assertThat(half / quarter).isWithin(1e-3f).of(8f)
        // After the ramp the speed is the cap: 900 dp per second.
        val speed = (BoxKeyCurve.distanceDp(2f) - BoxKeyCurve.distanceDp(1.5f)) / 0.5f
        assertThat(speed).isWithin(0.5f).of(BoxKeyCurve.MAX_DP_PER_S)

        val (tap, _, _) = hold(BoxKey.RIGHT, 0f)
        assertThat(tap).isWithin(1e-4f).of(BoxKeyCurve.PRESS_PT)
        val (left, _, _) = hold(BoxKey.LEFT, 1f)
        assertThat(left).isWithin(0.01f).of(-(BoxKeyCurve.PRESS_PT + BoxKeyCurve.distanceDp(1f)))
    }

    @Test
    fun lineSteps_onePerPress_pacedByTheCurve_cappedAt15PerSecond() {
        assertThat(BoxKeyCurve.lineSteps(0f)).isEqualTo(1)
        assertThat(BoxKeyCurve.lineSteps(0.3f)).isEqualTo(1)
        // The ramp integrates to 15 * 0.8 / 3 = 4 lines, then 15 lines per second.
        assertThat(BoxKeyCurve.lineSteps(0.8f)).isEqualTo(5)
        assertThat(BoxKeyCurve.lineSteps(1.8f) - BoxKeyCurve.lineSteps(0.8f)).isEqualTo(15)

        val start = 36f + 5f * 20f
        val (dx, top, steps) = hold(BoxKey.DOWN, 0.8f, topPt = start)
        assertThat(dx).isEqualTo(0f)
        assertThat(steps).isEqualTo(5)
        assertThat(top).isWithin(1e-3f).of(start + 5f * 20f)
    }

    @Test
    fun shiftDown_thenUp_snapsUpOntoTheLine_clearingTheOffset() {
        val rule = 36f + 5f * 20f
        val (_, shifted, steps) = hold(BoxKey.DOWN, 0.1f, smooth = true, topPt = rule)
        assertThat(steps).isEqualTo(0)
        assertThat(shifted).isGreaterThan(rule + 0.5f)
        assertThat(shifted).isLessThan(rule + 20f)

        val (_, snapped, _) = hold(BoxKey.UP, 0f, topPt = shifted)
        assertThat(snapped).isWithin(1e-3f).of(rule)
    }

    @Test
    fun shiftUp_thenDown_snapsDownOntoTheLine() {
        val rule = 36f + 5f * 20f
        val (_, shifted, _) = hold(BoxKey.UP, 0.1f, smooth = true, topPt = rule)
        assertThat(shifted).isLessThan(rule - 0.5f)

        val (_, snapped, _) = hold(BoxKey.DOWN, 0f, topPt = shifted)
        assertThat(snapped).isWithin(1e-3f).of(rule)
    }

    @Test
    fun repeatsOfTheHeldKey_areIgnored_theOppositeKeyTakesTheAxisOver() {
        assertThat(motion.press(BoxKey.RIGHT, false, 0L)).isTrue()
        assertThat(motion.press(BoxKey.RIGHT, false, ns(0.1f))).isFalse()
        assertThat(motion.press(BoxKey.LEFT, false, ns(0.2f))).isTrue()
        motion.release(BoxKey.RIGHT)
        assertThat(motion.active).isTrue()
        motion.release(BoxKey.LEFT)
        assertThat(motion.active).isFalse()
    }
}
