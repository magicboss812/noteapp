package dev.folio.app.spikes

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log2
import kotlin.math.pow

/** Inclusive tile index ranges; empty when [txMax] < [txMin] or [tyMax] < [tyMin]. */
internal data class TileRange(
    val txMin: Int,
    val txMax: Int,
    val tyMin: Int,
    val tyMax: Int,
) {
    val isEmpty: Boolean get() = txMax < txMin || tyMax < tyMin

    fun contains(
        tx: Int,
        ty: Int,
    ): Boolean = tx in txMin..txMax && ty in tyMin..tyMax
}

/**
 * Tile addressing for the P01-S2 spike (05-canvas-rendering.md#viewport, #tiles): zoom buckets in
 * steps of sqrt 2, the bucket at or above the current scale, 512 px tiles in bucket pixels.
 */
internal object TileMath {
    const val TILE_PX = 512

    /** Index of the smallest bucket `2^(i/2)` >= [scale] (px per pt). */
    fun bucketIndex(scale: Float): Int = ceil(2.0 * log2(scale.toDouble()) - EPSILON).toInt()

    /** Scale (px per pt) of bucket [index]. */
    fun bucketScale(index: Int): Float = 2.0.pow(index / 2.0).toFloat()

    /**
     * Tiles of a [pageWidthPt] x [pageHeightPt] page at [bucketScale] that intersect a
     * [viewWidthPx] x [viewHeightPx] view showing the page at [scale] with its origin at
     * ([offsetXPx], [offsetYPx]), widened by [ring] tiles and clamped to the page.
     */
    @Suppress("LongParameterList") // plain numbers keep the math testable without a Viewport type (P03)
    fun visibleTiles(
        pageWidthPt: Float,
        pageHeightPt: Float,
        scale: Float,
        offsetXPx: Float,
        offsetYPx: Float,
        viewWidthPx: Int,
        viewHeightPx: Int,
        bucketScale: Float,
        ring: Int = 0,
    ): TileRange {
        val toBucket = bucketScale / scale // view px -> bucket px
        val left = -offsetXPx * toBucket
        val top = -offsetYPx * toBucket
        val right = (viewWidthPx - offsetXPx) * toBucket
        val bottom = (viewHeightPx - offsetYPx) * toBucket
        val lastTx = ceil(pageWidthPt * bucketScale / TILE_PX).toInt() - 1
        val lastTy = ceil(pageHeightPt * bucketScale / TILE_PX).toInt() - 1
        return TileRange(
            txMin = (floor(left / TILE_PX).toInt() - ring).coerceAtLeast(0),
            txMax = (ceil(right / TILE_PX).toInt() - 1 + ring).coerceAtMost(lastTx),
            tyMin = (floor(top / TILE_PX).toInt() - ring).coerceAtLeast(0),
            tyMax = (ceil(bottom / TILE_PX).toInt() - 1 + ring).coerceAtMost(lastTy),
        )
    }

    /** Tiles of [range] ordered by distance of their centers from the view center (center-out). */
    fun centerOut(
        range: TileRange,
        centerXBucketPx: Float,
        centerYBucketPx: Float,
    ): List<Pair<Int, Int>> {
        if (range.isEmpty) return emptyList()
        val tiles = ArrayList<Pair<Int, Int>>()
        for (ty in range.tyMin..range.tyMax) for (tx in range.txMin..range.txMax) tiles += tx to ty
        val half = TILE_PX / 2f
        return tiles.sortedBy { (tx, ty) ->
            val dx = tx * TILE_PX + half - centerXBucketPx
            val dy = ty * TILE_PX + half - centerYBucketPx
            dx * dx + dy * dy
        }
    }

    private const val EPSILON = 1e-6
}
