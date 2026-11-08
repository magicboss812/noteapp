package dev.folio.core.ink.shape

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DwellDetectorTest {
    private val dwell = DwellDetector(radiusPx = 10f, dwellMs = 450L)

    @Test
    fun remainingMs_penStillWithinRadius_countsFromTheAnchor() {
        dwell.start(0f, 0f, 1000L, 0)
        dwell.move(100f, 0f, 1100L, 1)
        dwell.move(105f, 6f, 1300L, 2)

        assertThat(dwell.anchorIndex).isEqualTo(1)
        assertThat(dwell.remainingMs(1400L)).isEqualTo(150L)
        assertThat(dwell.remainingMs(1600L)).isEqualTo(0L)
    }

    @Test
    fun remainingMs_penLeavesTheRadius_restartsTheStay() {
        dwell.start(0f, 0f, 1000L, 0)
        dwell.move(11f, 0f, 1400L, 1)

        assertThat(dwell.remainingMs(1500L)).isEqualTo(350L)
    }

    @Test
    fun consume_reportsEachStayOnce() {
        dwell.start(0f, 0f, 1000L, 0)
        dwell.consume()
        dwell.move(5f, 0f, 1500L, 1)

        assertThat(dwell.remainingMs(2000L)).isEqualTo(Long.MAX_VALUE)

        dwell.move(30f, 0f, 2000L, 2)

        assertThat(dwell.remainingMs(2450L)).isEqualTo(0L)
    }
}
