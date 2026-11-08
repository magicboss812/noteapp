package dev.folio.core.model.lasso

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import org.junit.Test

class SelectionBoxTest {
    private val box = RectPt(100f, 100f, 300f, 200f)

    private fun Affine.at(
        x: Float,
        y: Float,
    ): PointPt = mapPoint(PointPt(x, y))

    private fun assertNear(
        actual: PointPt,
        x: Float,
        y: Float,
    ) {
        assertThat(actual.x).isWithin(1e-2f).of(x)
        assertThat(actual.y).isWithin(1e-2f).of(y)
    }

    @Test
    fun handles_onCornersEdgesAndAboveTheTop() {
        assertThat(SelectionBox.position(box, SelectionHandle.BOTTOM_RIGHT, 20f)).isEqualTo(PointPt(300f, 200f))
        assertThat(SelectionBox.position(box, SelectionHandle.LEFT, 20f)).isEqualTo(PointPt(100f, 150f))
        assertThat(SelectionBox.position(box, SelectionHandle.ROTATE, 20f)).isEqualTo(PointPt(200f, 80f))
        assertThat(SelectionBox.handleAt(box, PointPt(203f, 82f), 10f, 20f)).isEqualTo(SelectionHandle.ROTATE)
        assertThat(SelectionBox.handleAt(box, PointPt(296f, 104f), 10f, 20f)).isEqualTo(SelectionHandle.TOP_RIGHT)
        assertThat(SelectionBox.handleAt(box, PointPt(200f, 150f), 10f, 20f)).isNull()
    }

    @Test
    fun dragInside_moves() {
        val a = SelectionBox.drag(box, null, PointPt(150f, 150f), PointPt(170f, 140f), 10f)
        assertNear(a.at(100f, 100f), 120f, 90f)
    }

    @Test
    fun corner_scalesUniformlyAroundTheOppositeCorner() {
        // Bottom-right dragged by (+200, +0): projection on the diagonal (200, 100) gives s = 1 + 40000/50000 = 1.8.
        val a = SelectionBox.drag(box, SelectionHandle.BOTTOM_RIGHT, PointPt(300f, 200f), PointPt(500f, 200f), 10f)
        assertNear(a.at(100f, 100f), 100f, 100f)
        assertNear(a.at(300f, 200f), 460f, 280f)
    }

    @Test
    fun edge_scalesOneAxisAroundTheOppositeEdge() {
        val a = SelectionBox.drag(box, SelectionHandle.LEFT, PointPt(100f, 150f), PointPt(0f, 170f), 10f)
        assertNear(a.at(300f, 200f), 300f, 200f)
        assertNear(a.at(100f, 100f), 0f, 100f)
        val top = SelectionBox.drag(box, SelectionHandle.TOP, PointPt(200f, 100f), PointPt(200f, 150f), 10f)
        assertNear(top.at(100f, 100f), 100f, 150f)
        assertNear(top.at(300f, 200f), 300f, 200f)
    }

    @Test
    fun scale_neverMirrorsOrShrinksBelowTheMinimum() {
        val a = SelectionBox.drag(box, SelectionHandle.RIGHT, PointPt(300f, 150f), PointPt(0f, 150f), 20f)
        assertNear(a.at(300f, 150f), 120f, 150f)
        val c = SelectionBox.drag(box, SelectionHandle.TOP_LEFT, PointPt(100f, 100f), PointPt(500f, 400f), 20f)
        // The height (100) limits: at least 20 pt tall, so s = 0.2.
        assertNear(c.at(100f, 100f), 260f, 180f)
    }

    @Test
    fun rotate_aroundTheCenterWithDetents() {
        val c = box.center
        // A quarter turn plus 2 degrees snaps to 90.
        val from = PointPt(c.x, c.y - 100f)
        val rad = Math.toRadians(92.0)
        val to = PointPt(c.x + 100f * Math.sin(rad).toFloat(), c.y - 100f * Math.cos(rad).toFloat())
        assertThat(SelectionBox.rotation(box, from, to)).isEqualTo(90f)
        val a = SelectionBox.drag(box, SelectionHandle.ROTATE, from, to, 10f)
        assertNear(a.at(c.x, c.y - 50f), c.x + 50f, c.y)
        // 40 degrees stays 40.
        val rad40 = Math.toRadians(40.0)
        val to40 = PointPt(c.x + 100f * Math.sin(rad40).toFloat(), c.y - 100f * Math.cos(rad40).toFloat())
        assertThat(SelectionBox.rotation(box, from, to40)).isWithin(1e-3f).of(40f)
    }

    @Test
    fun rotate_pastHalfATurnIsNegative() {
        val c = box.center
        val from = PointPt(c.x - 100f, c.y + 1f)
        val to = PointPt(c.x - 100f, c.y - 1f)
        assertThat(SelectionBox.rotation(box, from, to)).isEqualTo(0f)
    }
}
