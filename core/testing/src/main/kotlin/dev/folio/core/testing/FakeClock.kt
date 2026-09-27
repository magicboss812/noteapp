package dev.folio.core.testing

import dev.folio.core.common.Clock

/** Manually advanced [Clock]. Both clocks move together. */
class FakeClock(
    private var nowMs: Long = START_MS,
) : Clock {
    private var monotonicNs: Long = 0L

    override fun nowMs(): Long = nowMs

    override fun monotonicNs(): Long = monotonicNs

    /** Moves both clocks forward by [durationMs]. */
    fun advanceMs(durationMs: Long) {
        advanceNs(durationMs * NS_PER_MS)
    }

    /** Moves both clocks forward by [durationNs] (wall clock rounds down to whole ms). */
    fun advanceNs(durationNs: Long) {
        require(durationNs >= 0) { "time only moves forward: $durationNs" }
        val before = monotonicNs / NS_PER_MS
        monotonicNs += durationNs
        nowMs += monotonicNs / NS_PER_MS - before
    }

    private companion object {
        const val START_MS = 1_767_225_600_000L // 2026-01-01T00:00:00Z
        const val NS_PER_MS = 1_000_000L
    }
}
