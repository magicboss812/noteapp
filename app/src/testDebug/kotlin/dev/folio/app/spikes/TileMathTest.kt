package dev.folio.app.spikes

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class TileMathTest {
    @Test
    fun bucketIndex_betweenBuckets_picksBucketAtOrAbove() {
        assertThat(TileMath.bucketIndex(1f)).isEqualTo(0)
        assertThat(TileMath.bucketIndex(1.1f)).isEqualTo(1) // sqrt 2
        assertThat(TileMath.bucketIndex(5.16f)).isEqualTo(5) // 2^2.5 = 5.66
        assertThat(TileMath.bucketScale(5)).isWithin(1e-4f).of(5.6569f)
    }

    @Test
    fun bucketIndex_exactBucketScale_roundTrips() {
        for (i in 0..16) assertThat(TileMath.bucketIndex(TileMath.bucketScale(i))).isEqualTo(i)
    }

    @Test
    fun visibleTiles_pageFitsView_coversPageTilesOnly() {
        // 100 x 100 pt page at 5 px/pt = 500 px square: exactly one tile.
        val range = TileMath.visibleTiles(100f, 100f, 5f, 10f, 10f, 1000, 1000, bucketScale = 5f, ring = 1)

        assertThat(range).isEqualTo(TileRange(0, 0, 0, 0))
    }

    @Test
    fun visibleTiles_scrolledPage_returnsIntersectingTilesPlusRing() {
        // 1000 x 1000 pt page at 1 px/pt (tiles of 512 pt); view 600 x 400 px scrolled by (600, 520).
        val range = TileMath.visibleTiles(1000f, 1000f, 1f, -600f, -520f, 600, 400, bucketScale = 1f)
        val ringed = TileMath.visibleTiles(1000f, 1000f, 1f, -600f, -520f, 600, 400, bucketScale = 1f, ring = 1)

        assertThat(range).isEqualTo(TileRange(1, 1, 1, 1))
        assertThat(ringed).isEqualTo(TileRange(0, 1, 0, 1))
    }

    @Test
    fun visibleTiles_bucketAboveScale_countsBucketPixels() {
        // View at 1 px/pt, tiles rendered at 2 px/pt: 600 view px = 1200 bucket px = tiles 0..2.
        val range = TileMath.visibleTiles(2000f, 2000f, 1f, 0f, 0f, 600, 100, bucketScale = 2f)

        assertThat(range).isEqualTo(TileRange(0, 2, 0, 0))
    }

    @Test
    fun centerOut_threeByThree_startsAtCenterTile() {
        val order = TileMath.centerOut(TileRange(0, 2, 0, 2), centerXBucketPx = 768f, centerYBucketPx = 768f)

        assertThat(order).hasSize(9)
        assertThat(order.first()).isEqualTo(1 to 1)
        assertThat(order.subList(1, 5)).containsExactly(0 to 1, 1 to 0, 1 to 2, 2 to 1)
    }

    @Test
    fun tileKey_packsAndUnpacksBucket() {
        val a = tileKey(bucket = 8, tx = 3, ty = 17)

        assertThat(tileBucket(a)).isEqualTo(8)
        assertThat(a).isNotEqualTo(tileKey(8, 17, 3))
    }
}
