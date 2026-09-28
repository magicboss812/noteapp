package dev.folio.core.model.geometry

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeometryTest {
    @Test
    fun polygonContains_concavePolygon_evenOdd() {
        // U shape: notch between x 4..6 from y 0..6.
        val u =
            listOf(
                PointPt(0f, 0f),
                PointPt(4f, 0f),
                PointPt(4f, 6f),
                PointPt(6f, 6f),
                PointPt(6f, 0f),
                PointPt(10f, 0f),
                PointPt(10f, 10f),
                PointPt(0f, 10f),
            )
        assertThat(polygonContains(u, PointPt(2f, 2f))).isTrue()
        assertThat(polygonContains(u, PointPt(5f, 2f))).isFalse()
        assertThat(polygonContains(u, PointPt(5f, 8f))).isTrue()
        assertThat(polygonContains(u, PointPt(11f, 5f))).isFalse()
        assertThat(polygonContains(u.take(2), PointPt(1f, 0f))).isFalse()
    }

    @Test
    fun segmentDistance_projectionsAndEndpoints() {
        val a = PointPt(0f, 0f)
        val b = PointPt(10f, 0f)
        assertThat(segmentDistance(PointPt(5f, 3f), a, b)).isWithin(EPS).of(3f)
        assertThat(segmentDistance(PointPt(-3f, 4f), a, b)).isWithin(EPS).of(5f)
        assertThat(segmentDistance(PointPt(13f, 4f), a, b)).isWithin(EPS).of(5f)
        assertThat(segmentDistance(PointPt(3f, 4f), a, a)).isWithin(EPS).of(5f)
    }

    @Test
    fun polylineLength_listAndArrays_agree() {
        val pts = listOf(PointPt(0f, 0f), PointPt(3f, 4f), PointPt(3f, 10f))
        assertThat(polylineLength(pts)).isWithin(EPS).of(11f)
        assertThat(polylineLength(floatArrayOf(0f, 3f, 3f), floatArrayOf(0f, 4f, 10f))).isWithin(EPS).of(11f)
        assertThat(polylineLength(emptyList())).isEqualTo(0f)
    }

    @Test
    fun rect_unionWithEmpty_isIdentity() {
        val r = RectPt(1f, 2f, 3f, 4f)
        assertThat(RectPt.EMPTY.union(r)).isEqualTo(r)
        assertThat(r.union(RectPt.EMPTY)).isEqualTo(r)
        assertThat(RectPt.EMPTY.isEmpty).isTrue()
        assertThat(r.union(RectPt(-1f, 3f, 2f, 9f))).isEqualTo(RectPt(-1f, 2f, 3f, 9f))
    }

    @Test
    fun rect_touchingAndDegenerate_intersect() {
        val r = RectPt(0f, 0f, 10f, 10f)
        assertThat(r.intersects(RectPt(10f, 10f, 20f, 20f))).isTrue()
        assertThat(r.intersects(RectPt(5f, -5f, 5f, 5f))).isTrue() // zero-width line bounds
        assertThat(r.intersects(RectPt(10.1f, 0f, 20f, 10f))).isFalse()
        assertThat(r.intersects(RectPt.EMPTY)).isFalse()
    }

    @Test
    fun rect_containsAndInsetAndDistance() {
        val r = RectPt(0f, 0f, 10f, 10f)
        assertThat(r.contains(RectPt(1f, 1f, 9f, 9f))).isTrue()
        assertThat(r.contains(PointPt(10f, 0f))).isTrue()
        assertThat(r.inset(2f)).isEqualTo(RectPt(2f, 2f, 8f, 8f))
        assertThat(r.inset(6f).isEmpty).isTrue()
        assertThat(r.distanceTo(13f, 14f)).isWithin(EPS).of(5f)
        assertThat(r.distanceTo(5f, 5f)).isEqualTo(0f)
    }

    private companion object {
        const val EPS = 1e-5f
    }
}
