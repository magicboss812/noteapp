package dev.folio.core.common

/** Time source; inject it instead of calling System time directly in logic. */
interface Clock {
    /** Wall-clock time in epoch milliseconds. */
    fun nowMs(): Long

    /** Monotonic time in nanoseconds, for durations only. */
    fun monotonicNs(): Long
}

/** [Clock] backed by the JVM system clocks. */
object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()

    override fun monotonicNs(): Long = System.nanoTime()
}
