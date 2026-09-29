package dev.folio.core.render.tiles

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.RectPt
import org.junit.Test

class TileCacheTest {
    private val released = ArrayList<String>()
    private val cache = TileCache<String>(budgetBytes = 3 * TILE, release = { released += it })

    @Test
    fun put_overBudget_evictsLeastRecentlyUsedUnpinned() {
        put("a", 0, 0)
        put("a", 1, 0)
        cache.beginFrame() // a/0 and a/1 lose their pin
        put("a", 2, 0)
        cache.beginFrame()
        cache.markUsed(cache[key("a", 0, 0)]!!) // a/0 drawn this frame: pinned and most recent
        put("a", 3, 0)

        assertThat(released).containsExactly("a/1/0")
        assertThat(cache.bytes).isEqualTo(3 * TILE)
        assertThat(cache.evictions).isEqualTo(1)
        assertThat(cache[key("a", 0, 0)]).isNotNull()
    }

    @Test
    fun put_everythingPinned_exceedsBudgetInsteadOfEvictingVisibleTiles() {
        repeat(5) { put("a", it, 0) }

        assertThat(released).isEmpty()
        assertThat(cache.bytes).isEqualTo(5 * TILE)
        cache.beginFrame()
        put("a", 5, 0)
        assertThat(cache.bytes).isEqualTo(3 * TILE)
        assertThat(released).hasSize(3)
        assertThat(released).doesNotContain("a/5/0")
    }

    @Test
    fun put_sameKey_replacesAndReleasesOldPayload() {
        put("a", 0, 0)
        cache.put(key("a", 0, 0), "fresh", TILE)

        assertThat(released).containsExactly("a/0/0")
        assertThat(cache[key("a", 0, 0)]!!.payload).isEqualTo("fresh")
        assertThat(cache.size).isEqualTo(1)
        assertThat(cache.entries("a")).hasSize(1)
    }

    @Test
    fun emptyTiles_holdNoBytes() {
        cache.put(key("a", 0, 0), null, 0)
        assertThat(cache.bytes).isEqualTo(0)
        assertThat(cache[key("a", 0, 0)]!!.payload).isNull()
    }

    @Test
    fun invalidate_marksOverlappingTilesOfAllBucketsOnly() {
        // Bucket 2: 256 pt tiles; bucket 0: 512 pt tiles.
        put("a", 0, 0, bucket = 2)
        put("a", 1, 0, bucket = 2)
        put("a", 0, 1, bucket = 2)
        put("a", 0, 0, bucket = 0)
        put("b", 0, 0, bucket = 2)

        val marked = cache.invalidate("a", RectPt(10f, 10f, 20f, 20f))

        assertThat(marked).isEqualTo(2)
        assertThat(cache[key("a", 0, 0, 2)]!!.stale).isTrue()
        assertThat(cache[key("a", 0, 0, 0)]!!.stale).isTrue()
        assertThat(cache[key("a", 1, 0, 2)]!!.stale).isFalse()
        assertThat(cache[key("a", 0, 1, 2)]!!.stale).isFalse()
        assertThat(cache[key("b", 0, 0, 2)]!!.stale).isFalse()
    }

    @Test
    fun invalidate_boundsWithinOnePixelOfTileEdge_marksNeighborToo() {
        put("a", 0, 0, bucket = 2)
        put("a", 1, 0, bucket = 2)
        // Bucket 2 is 2 px/pt: 255.8 pt is 0.4 px left of the tile border (antialiasing may spill over).
        cache.invalidate("a", RectPt(200f, 10f, 255.8f, 20f))

        assertThat(cache[key("a", 1, 0, 2)]!!.stale).isTrue()
        cache.invalidate("a", RectPt(300f, 10f, 400f, 20f))
        assertThat(cache[key("a", 0, 0, 2)]!!.stale).isTrue() // from the first call only
    }

    @Test
    fun invalidatePage_marksEveryTileOfThatPage() {
        put("a", 0, 0)
        put("a", 7, 7)
        put("b", 0, 0)
        cache.invalidatePage("a")

        assertThat(cache.entries("a").all { it.stale }).isTrue()
        assertThat(cache.entries("b").none { it.stale }).isTrue()
    }

    @Test
    fun retainPages_dropsOtherPagesAndReleasesTheirPayloads() {
        put("a", 0, 0)
        put("b", 0, 0)
        cache.retainPages(setOf("a"))

        assertThat(released).containsExactly("b/0/0")
        assertThat(cache.entries("b")).isEmpty()
        assertThat(cache.bytes).isEqualTo(TILE)
    }

    @Test
    fun trimUnpinned_keepsOnlyTilesDrawnInTheLatestFrame() {
        put("a", 0, 0)
        put("a", 1, 0)
        cache.beginFrame()
        cache.markUsed(cache[key("a", 1, 0)]!!)

        assertThat(cache.trimUnpinned()).isEqualTo(1)
        assertThat(released).containsExactly("a/0/0")
    }

    private fun put(
        page: String,
        tx: Int,
        ty: Int,
        bucket: Int = 4,
    ) {
        cache.put(key(page, tx, ty, bucket), "$page/$tx/$ty", TILE)
    }

    private fun key(
        page: String,
        tx: Int,
        ty: Int,
        bucket: Int = 4,
    ) = TileKey(page, bucket, tx, ty)

    private companion object {
        const val TILE = TileGrid.TILE_BYTES
    }
}
