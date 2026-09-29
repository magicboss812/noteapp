package dev.folio.core.render.tiles

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.ZoomBuckets
import org.junit.Test

class TileGridTest {
    @Test
    fun tileSizePt_bucket0_is512Pt_andHalvesEveryTwoBuckets() {
        assertThat(TileGrid.tileSizePt(0)).isEqualTo(512f)
        assertThat(TileGrid.tileSizePt(2)).isEqualTo(256f)
        assertThat(TileGrid.tileSizePt(-2)).isEqualTo(1024f)
        assertThat(TileGrid.tileSizePt(1)).isWithin(1e-3f).of(512f / ZoomBuckets.scaleOf(1))
    }

    @Test
    fun tileRectPt_matchesTileSize_negativeIndicesAllowed() {
        assertThat(TileGrid.tileRectPt(2, 1, 3)).isEqualTo(RectPt(256f, 768f, 512f, 1024f))
        assertThat(TileGrid.tileRectPt(2, -1, -2)).isEqualTo(RectPt(-256f, -512f, 0f, -256f))
    }

    @Test
    fun range_edgeOnTileBorder_doesNotPullInNeighbor() {
        // Bucket 2: 256 pt tiles. [0, 256) is exactly tile 0.
        assertThat(TileGrid.range(RectPt(0f, 0f, 256f, 256f), 2)).isEqualTo(TileRange(0, 0, 0, 0))
        assertThat(TileGrid.range(RectPt(0f, 0f, 256.01f, 512f), 2)).isEqualTo(TileRange(0, 1, 0, 1))
    }

    @Test
    fun range_a4PageAtFitWidthBucket_coversWholePage() {
        // Pad 7 fit-width is about 5.2 px/pt -> bucket 5 (5.66 px/pt, 90.5 pt tiles).
        val bucket = ZoomBuckets.indexFor(5.2f)
        assertThat(bucket).isEqualTo(5)
        val range = TileGrid.range(RectPt(0f, 0f, 595.2756f, 841.8898f), bucket)
        assertThat(range).isEqualTo(TileRange(0, 6, 0, 9))
        assertThat(range.count).isEqualTo(70)
    }

    @Test
    fun range_negativeCoordinates_floorToNegativeIndices() {
        assertThat(TileGrid.range(RectPt(-10f, -300f, 10f, -1f), 2)).isEqualTo(TileRange(-1, 0, -2, -1))
    }

    @Test
    fun range_emptyOrZeroAreaRect_isEmpty() {
        assertThat(TileGrid.range(RectPt.EMPTY, 3).isEmpty).isTrue()
        assertThat(TileGrid.range(RectPt(5f, 5f, 5f, 50f), 3).isEmpty).isTrue()
        assertThat(TileRange().count).isEqualTo(0)
    }

    @Test
    fun grow_addsRingOnEverySide() {
        assertThat(TileGrid.grow(TileRange(0, 2, 1, 1), 1)).isEqualTo(TileRange(-1, 3, 0, 2))
        assertThat(TileGrid.grow(TileRange(), 1).isEmpty).isTrue()
    }

    @Test
    fun centerOut_ordersByDistanceFromCenter() {
        val order = TileGrid.centerOut(TileRange(0, 2, 0, 2), bucket = 0, centerXPt = 768f, centerYPt = 768f)
        assertThat(order.first()).isEqualTo(1 to 1)
        assertThat(order.subList(1, 5)).containsExactly(1 to 0, 0 to 1, 2 to 1, 1 to 2)
        assertThat(order.subList(5, 9)).containsExactly(0 to 0, 2 to 0, 0 to 2, 2 to 2)
    }
}
