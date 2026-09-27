package dev.folio.core.common

import java.util.concurrent.ConcurrentHashMap

/** Receives begin/end of named sections; :app forwards them to android.os.Trace (Perfetto). */
interface TraceSink {
    /** Opens a section on the current thread. */
    fun begin(section: String)

    /** Closes the innermost open section on the current thread. */
    fun end()

    /** Sink that drops everything. */
    object None : TraceSink {
        override fun begin(section: String) = Unit

        override fun end() = Unit
    }
}

/** Percentiles of one section, in milliseconds. */
data class PerfStats(
    val count: Long,
    val p50Ms: Double,
    val p95Ms: Double,
    val maxMs: Double,
)

/**
 * Named duration sections (12-performance.md#measurement): a ring buffer of the last [CAPACITY]
 * durations per section plus trace sections. With [enabled] false (release) only the trace sink runs.
 */
object PerfMonitor {
    /** Ring buffer size per section. */
    const val CAPACITY = 1000

    /** Records samples when true (debug builds); set once at app start. */
    @Volatile
    var enabled: Boolean = false

    /** Time source for durations; tests swap in a FakeClock. */
    @Volatile
    var clock: Clock = SystemClock

    /** Where begin/end of sections go; :app installs the platform trace sink. */
    @Volatile
    var traceSink: TraceSink = TraceSink.None

    private val sections = ConcurrentHashMap<String, Ring>()

    /** Times [block] under [section]. */
    inline fun <T> trace(
        section: String,
        block: () -> T,
    ): T {
        // HOT PATH: no allocation beyond the first use of a section name.
        // Volatiles are read once so a concurrent switch cannot pair begin/end with different sinks.
        val sink = traceSink
        val on = enabled
        val timer = clock
        sink.begin(section)
        val startNs = if (on) timer.monotonicNs() else 0L
        try {
            return block()
        } finally {
            if (on) record(section, timer.monotonicNs() - startNs)
            sink.end()
        }
    }

    /** Adds one duration sample (ignored while disabled). */
    fun record(
        section: String,
        durationNs: Long,
    ) {
        if (!enabled) return
        sections.getOrPut(section) { Ring() }.add(durationNs)
    }

    /** Current percentiles for every section that has samples, keyed by section name. */
    fun snapshot(): Map<String, PerfStats> = sections.mapValues { (_, ring) -> ring.stats() }.toSortedMap()

    /** Drops all samples. */
    fun reset() = sections.clear()

    private class Ring {
        private val samplesNs = LongArray(CAPACITY)
        private var next = 0
        private var total = 0L

        @Synchronized
        fun add(durationNs: Long) {
            samplesNs[next] = durationNs
            next = (next + 1) % CAPACITY
            total++
        }

        @Synchronized
        fun stats(): PerfStats {
            val n = minOf(total, CAPACITY.toLong()).toInt()
            val sorted = samplesNs.copyOf(n).also { it.sort() }
            return PerfStats(
                count = total,
                p50Ms = percentile(sorted, P50).nsToMs(),
                p95Ms = percentile(sorted, P95).nsToMs(),
                maxMs = (sorted.lastOrNull() ?: 0L).nsToMs(),
            )
        }
    }

    // Nearest-rank percentile.
    private fun percentile(
        sorted: LongArray,
        p: Double,
    ): Long {
        if (sorted.isEmpty()) return 0L
        val rank =
            kotlin.math
                .ceil(p * sorted.size)
                .toInt()
                .coerceIn(1, sorted.size)
        return sorted[rank - 1]
    }

    private fun Long.nsToMs(): Double = this / NS_PER_MS

    private const val P50 = 0.50
    private const val P95 = 0.95
    private const val NS_PER_MS = 1_000_000.0
}
