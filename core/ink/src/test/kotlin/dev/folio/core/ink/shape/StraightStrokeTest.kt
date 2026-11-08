package dev.folio.core.ink.shape

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StraightStrokeTest {
    @Test
    fun inputs_spanTheSegmentEvenly() {
        val inputs = StraightStroke.inputs(10f, 20f, 110f, 20f, durationMs = 300f)

        assertThat(inputs.size).isEqualTo(51)
        assertThat(inputs.x.first()).isEqualTo(10f)
        assertThat(inputs.x.last()).isEqualTo(110f)
        assertThat(inputs.y.toSet()).containsExactly(20f)
        assertThat(inputs.x[25]).isWithin(1e-4f).of(60f)
        assertThat(inputs.tMs.last()).isEqualTo(300f)
        assertThat(inputs.pressure).isNull()
    }

    @Test
    fun inputs_samePoint_keepsTwoInputs() {
        val inputs = StraightStroke.inputs(5f, 5f, 5f, 5f, durationMs = 0f)

        assertThat(inputs.size).isEqualTo(2)
    }
}
