package dev.folio.core.testing

import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Test

class FakeClockTest {
    @Test
    fun advance_msAndNs_moveBothClocks() {
        val clock = FakeClock(nowMs = 1_000L)

        clock.advanceMs(5)
        clock.advanceNs(1_500_000)

        assertThat(clock.monotonicNs()).isEqualTo(6_500_000L)
        assertThat(clock.nowMs()).isEqualTo(1_006L)
    }

    @Test
    fun advance_negative_throws() {
        assertThrows(IllegalArgumentException::class.java) { FakeClock().advanceMs(-1) }
    }
}
