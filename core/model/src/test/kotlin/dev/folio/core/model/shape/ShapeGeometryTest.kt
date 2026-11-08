package dev.folio.core.model.shape

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.edit.transformed
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import org.junit.Test
import kotlin.math.atan2
import kotlin.math.hypot

class ShapeGeometryTest {
    @Test
    fun dragPoints_tinyDrag_makesNoShape() {
        assertThat(ShapeGeometry.dragPoints(ShapeKind.LINE, PointPt(10f, 10f), PointPt(11f, 11f), constrain = false)).isNull()
        assertThat(ShapeGeometry.dragPoints(ShapeKind.POLYGON, PointPt(10f, 10f), PointPt(80f, 80f), constrain = false)).isNull()
    }

    @Test
    fun dragPoints_constrainedLine_snapsTo15DegreesKeepingLength() {
        val end = ShapeGeometry.dragPoints(ShapeKind.LINE, PointPt(0f, 0f), PointPt(100f, 22f), constrain = true)!![1]
        val deg = Math.toDegrees(atan2(end.y.toDouble(), end.x.toDouble()))
        assertThat(deg).isWithin(1e-3).of(15.0)
        assertThat(hypot(end.x, end.y)).isWithin(1e-3f).of(hypot(100f, 22f))
    }

    @Test
    fun dragPoints_constrainedBox_isSquareTowardTheDrag() {
        val pts = ShapeGeometry.dragPoints(ShapeKind.RECTANGLE, PointPt(100f, 100f), PointPt(40f, 130f), constrain = true)!!
        assertThat(pts[1]).isEqualTo(PointPt(40f, 160f))
    }

    @Test
    fun dragPoints_triangle_apexTopCenterBaseAtBottom() {
        val pts = ShapeGeometry.dragPoints(ShapeKind.TRIANGLE, PointPt(100f, 200f), PointPt(0f, 100f), constrain = false)!!
        assertThat(pts).containsExactly(PointPt(50f, 100f), PointPt(100f, 200f), PointPt(0f, 200f)).inOrder()
    }

    @Test
    fun create_lineIgnoresFill_boundsGrowByHalfWidth() {
        val s = ShapeGeometry.create(ID, ShapeKind.LINE, listOf(PointPt(10f, 10f), PointPt(50f, 10f)), STYLE, FILL)
        assertThat(s.fillArgb).isNull()
        assertThat(s.bounds.top).isEqualTo(8f)
        assertThat(s.bounds.right).isEqualTo(52f)
    }

    @Test
    fun bounds_arrow_includeTheHead() {
        val s = ShapeGeometry.create(ID, ShapeKind.ARROW, listOf(PointPt(0f, 50f), PointPt(100f, 50f)), STYLE, null)
        val (left, right) = ShapeGeometry.arrowHead(s.points[0], s.points[1], STYLE.widthPt)
        assertThat(left.x).isLessThan(100f)
        assertThat(s.bounds.top).isAtMost(minOf(left.y, right.y) - 2f)
        assertThat(s.bounds.bottom).isAtLeast(maxOf(left.y, right.y) + 2f)
    }

    @Test
    fun arrowHead_shortShaft_headAtMostHalfTheShaft() {
        val (left, _) = ShapeGeometry.arrowHead(PointPt(0f, 0f), PointPt(6f, 0f), 4f)
        assertThat(hypot(left.x - 6f, left.y)).isWithin(1e-4f).of(3f)
    }

    @Test
    fun hits_outlineWithinTolerance_insideOnlyWhenFilled() {
        val open = rect(fill = null)
        assertThat(ShapeGeometry.hits(open, PointPt(100f, 53f), 2f)).isTrue() // near the top edge
        assertThat(ShapeGeometry.hits(open, PointPt(150f, 100f), 2f)).isFalse() // inside, no fill
        assertThat(ShapeGeometry.hits(rect(fill = FILL), PointPt(150f, 100f), 2f)).isTrue()
        assertThat(ShapeGeometry.hits(open, PointPt(100f, 30f), 2f)).isFalse()
    }

    @Test
    fun hits_rotatedRectangle_testsInItsOwnFrame() {
        // 200 x 100 box centered at (150, 100), turned 90 deg: it now spans x 100..200, y 0..200.
        val turned = ShapeGeometry.withPoints(rect(fill = null), rect(fill = null).points, 90f)
        assertThat(ShapeGeometry.hits(turned, PointPt(100f, 150f), 1f)).isTrue()
        assertThat(ShapeGeometry.hits(turned, PointPt(50f, 100f), 1f)).isFalse()
        assertThat(turned.bounds.top).isWithin(1e-3f).of(-2f)
    }

    @Test
    fun hits_ellipseAndArrowBarb() {
        val e = ShapeGeometry.create(ID, ShapeKind.ELLIPSE, listOf(PointPt(0f, 0f), PointPt(200f, 100f)), STYLE, null)
        assertThat(ShapeGeometry.hits(e, PointPt(200f, 50f), 1f)).isTrue()
        assertThat(ShapeGeometry.hits(e, PointPt(10f, 10f), 1f)).isFalse() // box corner, off the ellipse
        val a = ShapeGeometry.create(ID, ShapeKind.ARROW, listOf(PointPt(0f, 50f), PointPt(100f, 50f)), STYLE, null)
        val (barb, _) = ShapeGeometry.arrowHead(a.points[0], a.points[1], STYLE.widthPt)
        assertThat(ShapeGeometry.hits(a, barb, 0.5f)).isTrue()
    }

    @Test
    fun transformed_arrow_keepsHeadInBounds() {
        val a = ShapeGeometry.create(ID, ShapeKind.ARROW, listOf(PointPt(0f, 50f), PointPt(100f, 50f)), STYLE, null)
        val moved = a.transformed(Affine.translate(10f, 0f)) as Shape
        assertThat(moved.bounds).isEqualTo(a.bounds.offset(10f, 0f))
    }

    private fun rect(fill: Int?): Shape =
        ShapeGeometry.create(ID, ShapeKind.RECTANGLE, listOf(PointPt(50f, 50f), PointPt(250f, 150f)), STYLE, fill)

    private companion object {
        val ID = ObjectId("s")
        val STYLE = StrokeStyle(0xFF1A1A1A.toInt(), 4f, dashed = false)
        const val FILL = 0x402563EB
    }
}
