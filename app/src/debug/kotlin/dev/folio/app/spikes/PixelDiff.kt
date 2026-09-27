package dev.folio.app.spikes

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.max

/** Accumulates per-pixel differences between two ARGB images (P01-S2 visual equality check). */
internal class PixelDiff(
    private val threshold: Int = DEFAULT_THRESHOLD,
) {
    var pixels = 0L
        private set
    var differing = 0L
        private set
    private var channelSum = 0L

    /** Percentage of pixels whose largest channel difference exceeds the threshold. */
    val differingPct: Double get() = if (pixels == 0L) 0.0 else differing * PERCENT / pixels

    /** Mean absolute difference per color channel, 0..255. */
    val meanChannelDiff: Double get() = if (pixels == 0L) 0.0 else channelSum.toDouble() / (pixels * CHANNELS)

    /** Adds the first [count] pixels of [a] and [b]. */
    fun add(
        a: IntArray,
        b: IntArray,
        count: Int = a.size,
    ) {
        for (i in 0 until count) {
            val dr = abs((a[i] shr RED and BYTE) - (b[i] shr RED and BYTE))
            val dg = abs((a[i] shr GREEN and BYTE) - (b[i] shr GREEN and BYTE))
            val db = abs((a[i] and BYTE) - (b[i] and BYTE))
            channelSum += dr + dg + db
            if (max(dr, max(dg, db)) > threshold) differing++
        }
        pixels += count
    }

    /** Compares two same-sized bitmaps row by row. */
    fun addBitmaps(
        a: Bitmap,
        b: Bitmap,
    ): PixelDiff {
        require(a.width == b.width && a.height == b.height) { "bitmap sizes differ" }
        val rowA = IntArray(a.width)
        val rowB = IntArray(a.width)
        for (y in 0 until a.height) {
            a.getPixels(rowA, 0, a.width, 0, y, a.width, 1)
            b.getPixels(rowB, 0, b.width, 0, y, b.width, 1)
            add(rowA, rowB)
        }
        return this
    }

    private companion object {
        const val DEFAULT_THRESHOLD = 32
        const val RED = 16
        const val GREEN = 8
        const val BYTE = 0xFF
        const val CHANNELS = 3
        const val PERCENT = 100.0
    }
}
