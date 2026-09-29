package dev.folio.core.render.viewport

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Test
import kotlin.random.Random

class ZoomBucketsTest {
    @Test
    fun indexFor_exactBucketScales_mapToThemselves() {
        for (k in -6..12) {
            assertThat(ZoomBuckets.indexFor(ZoomBuckets.scaleOf(k))).isEqualTo(k)
        }
    }

    @Test
    fun indexFor_betweenBuckets_picksBucketAbove() {
        assertThat(ZoomBuckets.indexFor(1.0001f)).isEqualTo(1)
        assertThat(ZoomBuckets.indexFor(1.2f)).isEqualTo(1)
        assertThat(ZoomBuckets.indexFor(1.5f)).isEqualTo(2)
        assertThat(ZoomBuckets.indexFor(0.6f)).isEqualTo(-1)
        assertThat(ZoomBuckets.indexFor(0.5f)).isEqualTo(-2)
    }

    @Test
    fun scaleOf_stepsBySqrtTwo() {
        assertThat(ZoomBuckets.scaleOf(0)).isEqualTo(1f)
        assertThat(ZoomBuckets.scaleOf(1)).isWithin(1e-6f).of(1.4142135f)
        assertThat(ZoomBuckets.scaleOf(2)).isEqualTo(2f)
        assertThat(ZoomBuckets.scaleOf(-2)).isEqualTo(0.5f)
    }

    @Test
    fun indexFor_randomScales_bucketAtOrAboveAndNextLowerBelow() {
        val seed = 20260929L
        val random = Random(seed)
        repeat(10_000) {
            val scale = 0.1f + random.nextFloat() * 40f
            val k = ZoomBuckets.indexFor(scale)
            assertWithMessage("seed=$seed scale=$scale").that(ZoomBuckets.scaleOf(k)).isAtLeast(scale * 0.9999f)
            assertWithMessage("seed=$seed scale=$scale").that(ZoomBuckets.scaleOf(k - 1)).isLessThan(scale)
        }
    }
}
