package dev.folio.core.model.shape

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import org.junit.Test

class ShapeHandlesTest {
    @Test
    fun handles_rectangle_eightResizeAndRotate_noPoints() {
        val hs = ShapeHandles.handles(rect(), ROTATE_OFFSET)
        assertThat(hs.count { it.kind == ShapeHandleKind.RESIZE }).isEqualTo(8)
        assertThat(hs.count { it.kind == ShapeHandleKind.POINT }).isEqualTo(0)
        assertThat(hs.single { it.kind == ShapeHandleKind.ROTATE }.position).isEqualTo(PointPt(150f, 30f))
        assertThat(hs.first { it.index == 3 }.position).isEqualTo(PointPt(250f, 100f))
    }

    @Test
    fun handles_line_onlyItsTwoPoints_polygonBoth() {
        val line = shape(ShapeKind.LINE, listOf(PointPt(0f, 0f), PointPt(10f, 0f)))
        assertThat(ShapeHandles.handles(line, ROTATE_OFFSET).map { it.kind }).containsExactly(ShapeHandleKind.POINT, ShapeHandleKind.POINT)
        val poly = shape(ShapeKind.POLYGON, listOf(PointPt(0f, 0f), PointPt(10f, 0f), PointPt(5f, 8f)))
        assertThat(ShapeHandles.handles(poly, ROTATE_OFFSET)).hasSize(8 + 1 + 3)
    }

    @Test
    fun handles_rotatedShape_handlesTurnAroundTheCenter() {
        val turned = ShapeGeometry.withPoints(rect(), rect().points, 90f)
        val right = ShapeHandles.handles(turned, ROTATE_OFFSET).first { it.kind == ShapeHandleKind.RESIZE && it.index == 3 }
        assertNear(right.position, PointPt(150f, 200f))
    }

    @Test
    fun handleAt_prefersNearest_pointsWinTies() {
        val poly = shape(ShapeKind.POLYGON, listOf(PointPt(0f, 0f), PointPt(10f, 0f), PointPt(10f, 10f)))
        val hs = ShapeHandles.handles(poly, ROTATE_OFFSET)
        assertThat(ShapeHandles.handleAt(hs, PointPt(1f, 1f), 5f)?.kind).isEqualTo(ShapeHandleKind.POINT) // TL corner = point 0
        assertThat(ShapeHandles.handleAt(hs, PointPt(5f, 1f), 5f)?.index).isEqualTo(1) // top edge resize
        assertThat(ShapeHandles.handleAt(hs, PointPt(40f, 40f), 5f)).isNull()
    }

    @Test
    fun resize_corner_oppositeCornerStays() {
        val r = ShapeHandles.resize(rect(), 4, PointPt(350f, 250f), keepAspect = false)
        assertThat(RectPt.bounds(r.points)).isEqualTo(RectPt(50f, 50f, 350f, 250f))
        assertThat(r.bounds).isEqualTo(RectPt(48f, 48f, 352f, 252f))
    }

    @Test
    fun resize_topLeftKeepAspect_scalesBothSidesEqually() {
        val r = ShapeHandles.resize(rect(), 0, PointPt(-150f, 120f), keepAspect = true)
        assertThat(RectPt.bounds(r.points)).isEqualTo(RectPt(-150f, -50f, 250f, 150f)) // x2 about the BR corner
    }

    @Test
    fun resize_edge_changesOneSideOnly_andFlipsPastTheAnchor() {
        val r = ShapeHandles.resize(rect(), 5, PointPt(999f, 200f), keepAspect = true)
        assertThat(RectPt.bounds(r.points)).isEqualTo(RectPt(50f, 50f, 250f, 200f))
        val flipped = ShapeHandles.resize(rect(), 3, PointPt(0f, 100f), keepAspect = false)
        assertThat(RectPt.bounds(flipped.points)).isEqualTo(RectPt(0f, 50f, 50f, 150f))
    }

    @Test
    fun resize_collapsed_keepsMinimumSide() {
        val r = ShapeHandles.resize(rect(), 3, PointPt(50f, 100f), keepAspect = false)
        assertThat(RectPt.bounds(r.points).widthPt).isWithin(1e-3f).of(ShapeHandles.MIN_SIDE_PT)
    }

    @Test
    fun resize_rotatedShape_anchorStaysOnThePage() {
        val turned = ShapeGeometry.withPoints(rect(), rect().points, 30f)
        val anchorBefore = ShapeHandles.handles(turned, ROTATE_OFFSET).first { it.kind == ShapeHandleKind.RESIZE && it.index == 0 }.position
        val dragged = ShapeHandles.handles(turned, ROTATE_OFFSET).first { it.kind == ShapeHandleKind.RESIZE && it.index == 4 }.position
        val r = ShapeHandles.resize(turned, 4, PointPt(dragged.x + 40f, dragged.y + 25f), keepAspect = false)
        val anchorAfter = ShapeHandles.handles(r, ROTATE_OFFSET).first { it.kind == ShapeHandleKind.RESIZE && it.index == 0 }.position
        assertNear(anchorAfter, anchorBefore)
        assertThat(r.rotationDeg).isEqualTo(30f)
    }

    @Test
    fun rotate_handleFollowsPointer_snapsNearDetents() {
        val c = PointPt(150f, 100f)
        assertThat(ShapeHandles.rotate(rect(), PointPt(c.x + 100f, c.y)).rotationDeg).isEqualTo(90f)
        assertThat(ShapeHandles.rotate(rect(), PointPt(c.x - 100f, c.y + 2f)).rotationDeg).isEqualTo(-90f) // 88.9 off: snaps
        assertThat(ShapeHandles.rotate(rect(), PointPt(c.x, c.y + 100f)).rotationDeg).isEqualTo(180f)
        val free = ShapeHandles.rotate(rect(), PointPt(c.x + 100f, c.y - 50f)).rotationDeg
        assertThat(free).isWithin(0.01f).of(63.43f) // 3.4 deg from 60: stays free
    }

    @Test
    fun movePoint_line_movesOnlyThatEnd() {
        val line = shape(ShapeKind.LINE, listOf(PointPt(0f, 0f), PointPt(10f, 0f)))
        assertThat(ShapeHandles.movePoint(line, 1, PointPt(20f, 5f)).points).containsExactly(PointPt(0f, 0f), PointPt(20f, 5f)).inOrder()
        assertThat(ShapeHandles.movePoint(line, 4, PointPt(20f, 5f))).isSameInstanceAs(line)
    }

    @Test
    fun movePoint_rotatedPolygon_otherPointsStayOnThePage() {
        val poly =
            ShapeGeometry.withPoints(
                shape(ShapeKind.POLYGON, listOf(PointPt(0f, 0f), PointPt(100f, 0f), PointPt(50f, 80f))),
                listOf(PointPt(0f, 0f), PointPt(100f, 0f), PointPt(50f, 80f)),
                45f,
            )
        val before = ShapeHandles.handles(poly, ROTATE_OFFSET).filter { it.kind == ShapeHandleKind.POINT }.map { it.position }
        val moved = ShapeHandles.movePoint(poly, 2, PointPt(200f, 200f))
        val after = ShapeHandles.handles(moved, ROTATE_OFFSET).filter { it.kind == ShapeHandleKind.POINT }.map { it.position }
        assertNear(after[0], before[0])
        assertNear(after[1], before[1])
        assertNear(after[2], PointPt(200f, 200f))
    }

    @Test
    fun translate_movesPointsAndBounds() {
        val t = ShapeHandles.translate(rect(), 5f, -5f)
        assertThat(t.bounds).isEqualTo(rect().bounds.offset(5f, -5f))
        assertThat(ShapeHandles.translate(rect(), 0f, 0f)).isEqualTo(rect())
    }

    private fun rect(): Shape = shape(ShapeKind.RECTANGLE, listOf(PointPt(50f, 50f), PointPt(250f, 150f)))

    private fun shape(
        kind: ShapeKind,
        points: List<PointPt>,
    ): Shape = ShapeGeometry.create(ObjectId("s"), kind, points, StrokeStyle(0xFF000000.toInt(), 4f, dashed = false), null)

    private fun assertNear(
        actual: PointPt,
        expected: PointPt,
    ) {
        assertThat(actual.x).isWithin(1e-3f).of(expected.x)
        assertThat(actual.y).isWithin(1e-3f).of(expected.y)
    }

    private companion object {
        const val ROTATE_OFFSET = 20f
    }
}
