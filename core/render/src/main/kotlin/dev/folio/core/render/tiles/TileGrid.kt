package dev.folio.core.render.tiles

import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.ZoomBuckets
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max

/** Address of one tile: page (its id string), zoom bucket and tile column/row in bucket pixels / 512. */
data class TileKey(
    val page: String,
    val bucket: Int,
    val tx: Int,
    val ty: Int,
)

/** Inclusive tile index ranges; empty when [txMax] < [txMin] or [tyMax] < [tyMin]. Mutable for reuse per frame. */
data class TileRange(
    var txMin: Int = 0,
    var txMax: Int = -1,
    var tyMin: Int = 0,
    var tyMax: Int = -1,
) {
    /** True when the range holds no tile. */
    val isEmpty: Boolean get() = txMax < txMin || tyMax < tyMin

    /** Number of tiles. */
    val count: Int get() = if (isEmpty) 0 else (txMax - txMin + 1) * (tyMax - tyMin + 1)

    /** True when ([tx], [ty]) lies in the range. */
    fun contains(
        tx: Int,
        ty: Int,
    ): Boolean = tx in txMin..txMax && ty in tyMin..tyMax

    /** Sets all bounds and returns this. */
    fun set(
        txMin: Int,
        txMax: Int,
        tyMin: Int,
        tyMax: Int,
    ): TileRange {
        this.txMin = txMin
        this.txMax = txMax
        this.tyMin = tyMin
        this.tyMax = tyMax
        return this
    }
}

/**
 * Tile addressing (05-canvas-rendering.md#tiles): a tile is [TILE_PX] square in bucket pixels, so tile
 * (tx, ty) of bucket b covers page pt [tx, tx + 1) x [ty, ty + 1) times [tileSizePt] (b). Indices may be
 * negative on infinite pages.
 */
object TileGrid {
    /** Tile edge in px. */
    const val TILE_PX = 512

    /** Bytes of one ARGB_8888 tile bitmap. */
    const val TILE_BYTES = TILE_PX.toLong() * TILE_PX * 4

    /** Tile edge in page pt at [bucket]. */
    fun tileSizePt(bucket: Int): Float = TILE_PX / ZoomBuckets.scaleOf(bucket)

    /** Page-space rect of tile ([tx], [ty]) at [bucket]. */
    fun tileRectPt(
        bucket: Int,
        tx: Int,
        ty: Int,
    ): RectPt {
        val size = tileSizePt(bucket).toDouble()
        return RectPt((tx * size).toFloat(), (ty * size).toFloat(), ((tx + 1) * size).toFloat(), ((ty + 1) * size).toFloat())
    }

    /**
     * Tiles at [bucket] overlapping the page-space rect ([leftPt], [topPt], [rightPt], [bottomPt]) with
     * positive area (a rect edge on a tile border does not pull in the neighbor), written into [out].
     * No allocation.
     */
    @Suppress("LongParameterList") // edges as floats keep the per-frame path free of RectPt allocations
    fun rangeInto(
        out: TileRange,
        leftPt: Float,
        topPt: Float,
        rightPt: Float,
        bottomPt: Float,
        bucket: Int,
    ): TileRange {
        if (!(leftPt < rightPt && topPt < bottomPt)) return out.set(0, -1, 0, -1)
        val perTile = ZoomBuckets.scaleOf(bucket).toDouble() / TILE_PX
        val txMin = floor(leftPt * perTile).toInt()
        val tyMin = floor(topPt * perTile).toInt()
        return out.set(
            txMin,
            max(txMin, ceil(rightPt * perTile).toInt() - 1),
            tyMin,
            max(tyMin, ceil(bottomPt * perTile).toInt() - 1),
        )
    }

    /** Allocating form of [rangeInto]. */
    fun range(
        rectPt: RectPt,
        bucket: Int,
    ): TileRange = rangeInto(TileRange(), rectPt.left, rectPt.top, rectPt.right, rectPt.bottom, bucket)

    /** [range] widened by [ring] tiles on every side (prefetch). */
    fun grow(
        range: TileRange,
        ring: Int,
    ): TileRange =
        if (range.isEmpty) {
            TileRange()
        } else {
            TileRange(range.txMin - ring, range.txMax + ring, range.tyMin - ring, range.tyMax + ring)
        }

    /** Tiles of [range] ordered by the distance of their centers from page point ([centerXPt], [centerYPt]). */
    fun centerOut(
        range: TileRange,
        bucket: Int,
        centerXPt: Float,
        centerYPt: Float,
    ): List<Pair<Int, Int>> {
        if (range.isEmpty) return emptyList()
        val size = tileSizePt(bucket).toDouble()
        val tiles = ArrayList<Pair<Int, Int>>(range.count)
        for (ty in range.tyMin..range.tyMax) for (tx in range.txMin..range.txMax) tiles += tx to ty
        return tiles.sortedWith(
            compareBy<Pair<Int, Int>> { (tx, ty) ->
                val dx = (tx + 0.5) * size - centerXPt
                val dy = (ty + 0.5) * size - centerYPt
                dx * dx + dy * dy
            }.thenBy { it.second }.thenBy { it.first },
        )
    }
}
