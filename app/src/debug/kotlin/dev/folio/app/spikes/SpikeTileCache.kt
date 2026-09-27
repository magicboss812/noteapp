package dev.folio.app.spikes

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.annotation.MainThread
import dev.folio.core.common.FolioDispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Tile cache of the P01-S2 spike: renders missing tiles on the render dispatcher (center-out),
 * keeps other-bucket tiles on screen until the current bucket is complete, pools bitmaps (A).
 * Main thread only; [onTileReady] fires after each accepted tile.
 */
@MainThread
internal class SpikeTileCache(
    private val dispatchers: FolioDispatchers,
    private val scope: CoroutineScope,
    private val onTileReady: () -> Unit,
) {
    private val tiles = ArrayList<SpikeTile>()
    private val inFlight = HashMap<Long, Job>()
    private val pool = ArrayDeque<Bitmap>()
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var generation = 0

    var renderer: SpikeTileRenderer? = null
    var strategy = TileStrategy.A
        private set

    val tileCount: Int get() = tiles.size
    val pendingCount: Int get() = inFlight.size
    val pooledCount: Int get() = pool.size

    /** Drops every tile and in-flight render, then switches to [next]. */
    fun reset(next: TileStrategy = strategy) {
        generation++
        inFlight.values.forEach { it.cancel() }
        inFlight.clear()
        tiles.forEach(::recycle)
        tiles.clear()
        strategy = next
    }

    /** Starts renders for the [order]ed tiles of [bucket] that are neither cached nor in flight. */
    fun request(
        bucket: Int,
        order: List<Pair<Int, Int>>,
    ) {
        val renderer = renderer ?: return
        val stale = inFlight.entries.iterator()
        while (stale.hasNext()) {
            val (key, job) = stale.next()
            if (tileBucket(key) == bucket) continue
            job.cancel() // an old bucket nobody waits for; its bitmap returns to the pool
            stale.remove()
        }
        val section = "tiles:render${strategy.name}:b$bucket"
        for ((tx, ty) in order) {
            val key = tileKey(bucket, tx, ty)
            if (key in inFlight || tiles.any { it.key == key }) continue
            inFlight[key] = launch(renderer, bucket, tx, ty, section)
        }
    }

    /** True when every tile of [range] at [bucket] is cached. */
    fun complete(
        bucket: Int,
        range: TileRange,
    ): Boolean {
        if (range.isEmpty) return true
        var have = 0
        for (tile in tiles) if (tile.bucket == bucket && range.contains(tile.tx, tile.ty)) have++
        return have == (range.txMax - range.txMin + 1) * (range.tyMax - range.tyMin + 1)
    }

    /** Drops tiles of other buckets and current-bucket tiles outside [keep]. */
    fun evict(
        bucket: Int,
        keep: TileRange,
    ) {
        tiles.removeAll { tile -> (tile.bucket != bucket || !keep.contains(tile.tx, tile.ty)).also { if (it) recycle(tile) } }
    }

    /** Draws cached tiles for a view at [scale] with the page origin at ([offsetXPx], [offsetYPx]); current bucket on top. */
    fun draw(
        canvas: Canvas,
        bucket: Int,
        scale: Float,
        offsetXPx: Float,
        offsetYPx: Float,
    ) {
        // HOT PATH: runs every frame during pan and zoom; no allocation.
        for (pass in 0..1) {
            for (i in tiles.indices) {
                val tile = tiles[i]
                if ((tile.bucket == bucket) == (pass == 0)) continue
                drawTile(canvas, tile, scale, offsetXPx, offsetYPx)
            }
        }
    }

    private fun drawTile(
        canvas: Canvas,
        tile: SpikeTile,
        scale: Float,
        offsetXPx: Float,
        offsetYPx: Float,
    ) {
        val k = scale / tile.bucketScale
        val sizePx = TileMath.TILE_PX * k
        val left = offsetXPx + tile.tx * sizePx
        val top = offsetYPx + tile.ty * sizePx
        if (left > canvas.width || top > canvas.height || left + sizePx < 0f || top + sizePx < 0f) return
        canvas.save()
        canvas.translate(left, top)
        canvas.scale(k, k)
        tile.bitmap?.let { canvas.drawBitmap(it, 0f, 0f, bitmapPaint) }
        tile.node?.let { if (canvas.isHardwareAccelerated) canvas.drawRenderNode(it) }
        canvas.restore()
    }

    private fun launch(
        renderer: SpikeTileRenderer,
        bucket: Int,
        tx: Int,
        ty: Int,
        section: String,
    ): Job {
        val gen = generation
        val mode = strategy
        val bitmap = if (mode == TileStrategy.A) pool.removeLastOrNull() ?: newBitmap() else null
        return scope.launch {
            val tile =
                try {
                    withContext(dispatchers.render) { renderer.render(mode, bucket, tx, ty, bitmap, section) }
                } catch (e: CancellationException) {
                    bitmap?.let(::release) // withContext returns only after the render finished
                    throw e
                }
            if (gen != generation) {
                recycle(tile)
                return@launch
            }
            inFlight.remove(tile.key)
            tiles += tile
            onTileReady()
        }
    }

    private fun newBitmap(): Bitmap = Bitmap.createBitmap(TileMath.TILE_PX, TileMath.TILE_PX, Bitmap.Config.ARGB_8888)

    private fun recycle(tile: SpikeTile) {
        tile.bitmap?.let(::release)
        tile.node?.discardDisplayList()
    }

    private fun release(bitmap: Bitmap) {
        if (pool.size < POOL_MAX) pool.addLast(bitmap) else bitmap.recycle()
    }

    private companion object {
        const val POOL_MAX = 24
    }
}
