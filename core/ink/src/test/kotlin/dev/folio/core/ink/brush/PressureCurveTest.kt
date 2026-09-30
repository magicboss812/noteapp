package dev.folio.core.ink.brush

import com.google.common.collect.Range
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.pow

class PressureCurveTest {
    @Test
    fun normalize_outOfRangeAndOddValues_clampedAndSnappedToStep() {
        assertThat(PressureCurve.normalize(0.1f)).isEqualTo(0.5f)
        assertThat(PressureCurve.normalize(3f)).isEqualTo(2f)
        assertThat(PressureCurve.normalize(1.23f)).isEqualTo(1.25f)
        assertThat(PressureCurve.normalize(1.01f)).isEqualTo(1f)
        assertThat(PressureCurve.normalize(Float.NaN)).isEqualTo(1f)
    }

    @Test
    fun normalize_everyStep_isIdempotent() {
        for (step in 10..40) {
            val gamma = step / 20f
            assertThat(PressureCurve.normalize(PressureCurve.normalize(gamma))).isEqualTo(PressureCurve.normalize(gamma))
        }
    }

    @Test
    fun points_gamma_followPowerCurveAndIncrease() {
        for (gamma in listOf(0.5f, 1.35f, 2f)) {
            val p = PressureCurve.points(gamma)
            assertThat(p.size).isEqualTo(PressureCurve.SAMPLES * 2)
            for (i in 0 until PressureCurve.SAMPLES) {
                val x = p[i * 2]
                assertThat(x).isIn(Range.open(0f, 1f))
                assertThat(p[i * 2 + 1].toDouble()).isWithin(1e-6).of(x.toDouble().pow(gamma.toDouble()))
                if (i > 0) {
                    assertThat(x).isGreaterThan(p[i * 2 - 2])
                    assertThat(p[i * 2 + 1]).isGreaterThan(p[i * 2 - 1])
                }
            }
        }
    }

    @Test
    fun isLinear_onlyForGammaOne() {
        assertThat(PressureCurve.isLinear(1f)).isTrue()
        assertThat(PressureCurve.isLinear(1.02f)).isTrue() // snaps to 1
        assertThat(PressureCurve.isLinear(0.5f)).isFalse()
    }
}
