package dev.folio.core.model.geometry

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

class AffineTest {
    @Test
    fun composeInvert_randomTransforms_roundTripWithin1e4() {
        val random = Random(SEED)
        repeat(1000) { i ->
            val m = randomAffine(random)
            val msg = "seed=$SEED i=$i m=$m"
            assertIdentity(m.compose(m.invert()), msg)
            assertIdentity(m.invert().compose(m), msg)
            val p = PointPt(random.nextFloat() * 400f - 200f, random.nextFloat() * 400f - 200f)
            val back = m.invert().mapPoint(m.mapPoint(p))
            assertWithMessage(msg).that(back.x).isWithin(POINT_TOL).of(p.x)
            assertWithMessage(msg).that(back.y).isWithin(POINT_TOL).of(p.y)
        }
    }

    @Test
    fun compose_appliesArgumentFirst() {
        val t = Affine.translate(10f, 0f)
        val s = Affine.scale(2f, 2f)
        // scale after translate: (1, 0) -> (11, 0) -> (22, 0)
        assertThat(s.compose(t).mapPoint(PointPt(1f, 0f))).isEqualTo(PointPt(22f, 0f))
        assertThat(t.compose(s).mapPoint(PointPt(1f, 0f))).isEqualTo(PointPt(12f, 0f))
        assertThat(s.after(t)).isEqualTo(s.compose(t))
    }

    @Test
    fun rotate_90DegAroundPivot_turnsClockwiseOnScreen() {
        val r = Affine.rotate(90f, 10f, 10f)
        val p = r.mapPoint(PointPt(20f, 10f))
        assertThat(p.x).isWithin(TOL).of(10f)
        assertThat(p.y).isWithin(TOL).of(20f)
    }

    @Test
    fun scale_aroundPivot_keepsPivotFixed() {
        val p = Affine.scale(3f, 0.5f, 7f, 9f).mapPoint(PointPt(7f, 9f))
        assertThat(p).isEqualTo(PointPt(7f, 9f))
    }

    @Test
    fun mapRect_rotation45_returnsAxisAlignedBounds() {
        val r = Affine.rotate(45f, 0f, 0f).mapRect(RectPt(-1f, -1f, 1f, 1f))
        val h = 1.4142135f
        assertThat(r.left).isWithin(TOL).of(-h)
        assertThat(r.top).isWithin(TOL).of(-h)
        assertThat(r.right).isWithin(TOL).of(h)
        assertThat(r.bottom).isWithin(TOL).of(h)
    }

    @Test
    fun invert_singular_throws() {
        val singular = Affine(1f, 2f, 2f, 4f, 0f, 0f)
        assertThat(singular.isInvertible).isFalse()
        assertThrows(IllegalArgumentException::class.java) { singular.invert() }
    }

    private fun randomAffine(random: Random): Affine {
        while (true) {
            val m =
                Affine(
                    a = random.nextFloat() * 4f - 2f,
                    b = random.nextFloat() * 4f - 2f,
                    c = random.nextFloat() * 4f - 2f,
                    d = random.nextFloat() * 4f - 2f,
                    tx = random.nextFloat() * 400f - 200f,
                    ty = random.nextFloat() * 400f - 200f,
                )
            if (abs(m.determinant) > 0.25f) return m
        }
    }

    private fun assertIdentity(
        m: Affine,
        msg: String,
    ) {
        assertWithMessage(msg).that(m.a).isWithin(TOL).of(1f)
        assertWithMessage(msg).that(m.b).isWithin(TOL).of(0f)
        assertWithMessage(msg).that(m.c).isWithin(TOL).of(0f)
        assertWithMessage(msg).that(m.d).isWithin(TOL).of(1f)
        assertWithMessage(msg).that(m.tx).isWithin(TOL).of(0f)
        assertWithMessage(msg).that(m.ty).isWithin(TOL).of(0f)
    }

    private companion object {
        const val SEED = 20260928
        const val TOL = 1e-4f

        // Mapped points reach ~600 pt (float ulp 6e-5) and the inverse amplifies that by up to ~8.
        const val POINT_TOL = 1e-3f
    }
}
