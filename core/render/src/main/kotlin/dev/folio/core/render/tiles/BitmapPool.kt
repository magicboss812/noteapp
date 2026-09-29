package dev.folio.core.render.tiles

import android.graphics.Bitmap

/**
 * Reuses [TileGrid.TILE_PX] ARGB_8888 tile bitmaps (05-canvas-rendering.md#tiles): tiles return their
 * bitmap here instead of recycling it, renders take one from here. Holds at most [maxPooled] idle
 * bitmaps. Thread-safe.
 */
class BitmapPool(
    private val maxPooled: Int = DEFAULT_MAX_POOLED,
) {
    private val idle = ArrayDeque<Bitmap>()

    private var createdCount = 0

    /** Bitmaps created because the pool was empty. */
    val created: Int get() = synchronized(idle) { createdCount }

    /** Idle bitmaps held. */
    val pooled: Int get() = synchronized(idle) { idle.size }

    /** An idle bitmap (content undefined) or a new one. */
    fun acquire(): Bitmap {
        synchronized(idle) {
            idle.removeLastOrNull()?.let { return it }
            createdCount++
        }
        return Bitmap.createBitmap(TileGrid.TILE_PX, TileGrid.TILE_PX, Bitmap.Config.ARGB_8888)
    }

    /** Returns [bitmap] for reuse; recycles it when the pool is full or it is not a tile bitmap. */
    fun release(bitmap: Bitmap) {
        if (bitmap.isRecycled) return
        val fits = bitmap.width == TileGrid.TILE_PX && bitmap.height == TileGrid.TILE_PX && bitmap.isMutable
        val kept =
            fits &&
                synchronized(idle) {
                    (idle.size < maxPooled).also { room -> if (room) idle.addLast(bitmap) }
                }
        if (!kept) bitmap.recycle()
    }

    /** Recycles every idle bitmap (memory pressure). */
    fun clear() {
        val drained = synchronized(idle) { idle.toList().also { idle.clear() } }
        drained.forEach(Bitmap::recycle)
    }

    /** Defaults. */
    companion object {
        /** Idle bitmaps kept by default (8 MiB). */
        const val DEFAULT_MAX_POOLED = 8
    }
}
