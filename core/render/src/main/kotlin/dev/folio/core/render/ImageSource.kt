package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.annotation.WorkerThread
import dev.folio.core.model.AssetId

/** Decoded image assets for [PageRenderer] (05-canvas-rendering.md#page-renderer); called on render threads. */
fun interface ImageSource {
    /**
     * Asset [id] decoded whole (uncropped), at least [minWidthPx] x [minHeightPx] where the source has that many pixels,
     * or null while it cannot be decoded.
     */
    @WorkerThread
    fun bitmap(
        id: AssetId,
        minWidthPx: Int,
        minHeightPx: Int,
    ): Bitmap?

    /** No images: the renderer draws placeholders. */
    object None : ImageSource {
        override fun bitmap(
            id: AssetId,
            minWidthPx: Int,
            minHeightPx: Int,
        ): Bitmap? = null
    }
}

/**
 * Downsampled decodes of image assets (05-canvas-rendering.md: sized to the zoom bucket on screen): each request decodes
 * at the largest power-of-two sample size that still gives the wanted pixels (sides at most [maxSidePx]), cached by
 * asset and sample size up to [budgetBytes]. A cached decode that is at least as fine serves too. Thread-safe; decodes
 * run outside the lock, so two threads may decode the same image once each.
 */
class ImageBitmaps(
    private val load: (AssetId) -> ByteArray?,
    budgetBytes: Int = DEFAULT_BUDGET_BYTES,
    private val maxSidePx: Int = MAX_SIDE_PX,
) : ImageSource {
    private data class Key(
        val id: AssetId,
        val sample: Int,
    )

    private val lock = Any()
    private val sizes = HashMap<AssetId, Long>()
    private val cache =
        object : LruCache<Key, Bitmap>(budgetBytes) {
            override fun sizeOf(
                key: Key,
                value: Bitmap,
            ): Int = value.allocationByteCount
        }

    override fun bitmap(
        id: AssetId,
        minWidthPx: Int,
        minHeightPx: Int,
    ): Bitmap? {
        val size = synchronized(lock) { sizes[id] } ?: measure(id) ?: return null
        val sample = sampleSize((size shr SHIFT).toInt(), size.toInt(), minWidthPx, minHeightPx)
        return cached(id, sample) ?: decode(id, sample)
    }

    // The decode at [sample] or a finer one.
    private fun cached(
        id: AssetId,
        sample: Int,
    ): Bitmap? =
        synchronized(lock) {
            var s = sample
            var hit: Bitmap? = null
            while (hit == null && s >= 1) {
                hit = cache.get(Key(id, s))
                s /= 2
            }
            hit
        }

    private fun decode(
        id: AssetId,
        sample: Int,
    ): Bitmap? {
        val bytes = load(id) ?: return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)?.also { synchronized(lock) { cache.put(Key(id, sample), it) } }
    }

    /** Drops every decode (memory trim). */
    fun clear() = synchronized(lock) { cache.evictAll() }

    // Source pixel size of [id] packed as width << 32 | height, or null when it does not decode.
    private fun measure(id: AssetId): Long? {
        val bytes = load(id) ?: return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val packed = (options.outWidth.toLong() shl SHIFT) or options.outHeight.toLong()
        synchronized(lock) { sizes[id] = packed }
        return packed
    }

    /** The sample size for a [widthPx] x [heightPx] source wanted at [minWidthPx] x [minHeightPx]. */
    internal fun sampleSize(
        widthPx: Int,
        heightPx: Int,
        minWidthPx: Int,
        minHeightPx: Int,
    ): Int {
        var sample = 1
        while (widthPx / (sample * 2) >= minWidthPx && heightPx / (sample * 2) >= minHeightPx) sample *= 2
        while (widthPx / sample > maxSidePx || heightPx / sample > maxSidePx) sample *= 2
        return sample
    }

    /** Defaults. */
    companion object {
        /** Memory for decoded images. */
        const val DEFAULT_BUDGET_BYTES = 48 * 1024 * 1024

        /** Longest decoded side (GPU texture limit with headroom). */
        const val MAX_SIDE_PX = 4096
        private const val SHIFT = 32
    }
}
