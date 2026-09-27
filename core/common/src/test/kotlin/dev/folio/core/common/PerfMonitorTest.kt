package dev.folio.core.common

import com.google.common.truth.Truth.assertThat
import dev.folio.core.testing.FakeClock
import org.junit.After
import org.junit.Before
import org.junit.Test

class PerfMonitorTest {
    private val clock = FakeClock()
    private val events = mutableListOf<String>()

    @Before
    fun setUp() {
        PerfMonitor.reset()
        PerfMonitor.enabled = true
        PerfMonitor.clock = clock
        PerfMonitor.traceSink =
            object : TraceSink {
                override fun begin(section: String) {
                    events += "begin $section"
                }

                override fun end() {
                    events += "end"
                }
            }
    }

    @After
    fun tearDown() {
        PerfMonitor.reset()
        PerfMonitor.enabled = false
        PerfMonitor.clock = SystemClock
        PerfMonitor.traceSink = TraceSink.None
    }

    @Test
    fun trace_hundredSamples_reportsNearestRankPercentiles() {
        for (ms in 1L..100L) PerfMonitor.trace("ink:onTouch") { clock.advanceMs(ms) }

        val stats = PerfMonitor.snapshot().getValue("ink:onTouch")

        assertThat(stats).isEqualTo(PerfStats(count = 100, p50Ms = 50.0, p95Ms = 95.0, maxMs = 100.0))
    }

    @Test
    fun trace_blockThrows_stillRecordsAndClosesSection() {
        runCatching {
            PerfMonitor.trace("fail") {
                clock.advanceMs(3)
                error("x")
            }
        }

        assertThat(PerfMonitor.snapshot().getValue("fail").maxMs).isEqualTo(3.0)
        assertThat(events).containsExactly("begin fail", "end").inOrder()
    }

    @Test
    fun record_moreThanCapacity_keepsLatestWindowButCountsAll() {
        repeat(PerfMonitor.CAPACITY) { PerfMonitor.record("s", 1_000_000_000L) }
        repeat(PerfMonitor.CAPACITY) { PerfMonitor.record("s", 1_000_000L) }

        val stats = PerfMonitor.snapshot().getValue("s")

        assertThat(stats.count).isEqualTo(2L * PerfMonitor.CAPACITY)
        assertThat(stats.maxMs).isEqualTo(1.0)
    }

    @Test
    fun trace_disabled_onlyTraces() {
        PerfMonitor.enabled = false

        val value = PerfMonitor.trace("release") { 7 }

        assertThat(value).isEqualTo(7)
        assertThat(PerfMonitor.snapshot()).isEmpty()
        assertThat(events).containsExactly("begin release", "end").inOrder()
    }

    @Test
    fun reset_afterSamples_clearsSnapshot() {
        PerfMonitor.record("a", 1L)
        PerfMonitor.reset()
        assertThat(PerfMonitor.snapshot()).isEmpty()
    }
}
