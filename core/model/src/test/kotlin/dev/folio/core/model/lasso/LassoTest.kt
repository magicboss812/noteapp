package dev.folio.core.model.lasso

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageObject
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.shape.ShapeGeometry
import org.junit.Test

class LassoTest {
    private val all = LassoKind.entries.toSet()

    // The lasso: the square 0..100.
    private val square = Lasso.rectangle(PointPt(0f, 0f), PointPt(100f, 100f))

    private fun stroke(
        id: String,
        vararg xy: Float,
    ): InkStroke {
        val n = xy.size / 2
        val inputs =
            StrokeInputs(
                FloatArray(n) {
                    xy[2 * it]
                },
                FloatArray(n) { xy[2 * it + 1] },
                FloatArray(n) { it.toFloat() },
                null,
                null,
                null,
                InputTool.SYNTHETIC,
            )
        return InkStroke.of(ObjectId(id), BrushSpec(BrushKind.BALLPOINT, 0xFF000000.toInt(), 1f, 1), inputs)
    }

    // A horizontal stroke of 10 points from x = [x0] in steps of 10 at y 50.
    private fun row(
        id: String,
        x0: Float,
    ): InkStroke = stroke(id, *FloatArray(20) { if (it % 2 == 0) x0 + (it / 2) * 10f else 50f })

    @Test
    fun stroke_selectedFromSixtyPercentOfItsPointsInside() {
        // Points at x 45..135: 6 of 10 inside (45..95), 5 for x0 = 55 (55..95).
        val six = row("six", 45f)
        val five = row("five", 55f)
        assertThat(Lasso.select(listOf(six, five), square, all)).containsExactly(ObjectId("six"))
    }

    @Test
    fun stroke_fullyInsideOrOutside() {
        val inside = stroke("in", 10f, 10f, 20f, 20f, 90f, 90f)
        val outside = stroke("out", 110f, 10f, 150f, 20f)
        assertThat(Lasso.select(listOf(inside, outside), square, all)).containsExactly(ObjectId("in"))
    }

    @Test
    fun freeformPolygon_concaveShapeLeavesTheNotchOut() {
        // A U shape: the notch 40..60 x 0..80 is outside.
        val u =
            listOf(
                PointPt(0f, 0f),
                PointPt(40f, 0f),
                PointPt(40f, 80f),
                PointPt(60f, 80f),
                PointPt(60f, 0f),
                PointPt(100f, 0f),
                PointPt(100f, 100f),
                PointPt(0f, 100f),
            )
        val inNotch = stroke("notch", 45f, 10f, 50f, 30f, 55f, 50f)
        val inArm = stroke("arm", 10f, 10f, 20f, 30f, 30f, 50f)
        assertThat(Lasso.select(listOf(inNotch, inArm), u, all)).containsExactly(ObjectId("arm"))
    }

    @Test
    fun shapesAndFrames_byBoundsCenter() {
        val stroke = StrokeStyle(0xFF000000.toInt(), 2f, dashed = false)
        // Center (90, 50): inside, though most of the rectangle is outside.
        val mostlyOut =
            ShapeGeometry.create(
                ObjectId("s1"),
                ShapeKind.RECTANGLE,
                listOf(PointPt(80f, 40f), PointPt(100f, 60f)),
                stroke,
                null,
            )
        val centerOut =
            ShapeGeometry.create(
                ObjectId("s2"),
                ShapeKind.RECTANGLE,
                listOf(PointPt(60f, 40f), PointPt(160f, 60f)),
                stroke,
                null,
            )
        val box = FlowFrame(ObjectId("box"), FlowId("f"), RectPt(10f, 10f, 60f, 30f), 0, FrameRole.BOX, autoGrow = true)
        val body = FlowFrame(ObjectId("body"), FlowId("b"), RectPt(0f, 0f, 100f, 100f), 0, FrameRole.BODY, autoGrow = true)
        assertThat(
            Lasso.select(listOf(mostlyOut, centerOut, box, body), square, all),
        ).containsExactly(ObjectId("s1"), ObjectId("box")).inOrder()
    }

    @Test
    fun filters_selectOnlyTheKindsOn() {
        val ink = stroke("ink", 10f, 10f, 20f, 20f)
        val shape =
            ShapeGeometry.create(
                ObjectId("shape"),
                ShapeKind.ELLIPSE,
                listOf(PointPt(30f, 30f), PointPt(50f, 50f)),
                StrokeStyle(0xFF000000.toInt(), 1f, false),
                null,
            )
        val objects: List<PageObject> = listOf(ink, shape)
        assertThat(Lasso.select(objects, square, setOf(LassoKind.SHAPES))).containsExactly(ObjectId("shape"))
        assertThat(Lasso.select(objects, square, setOf(LassoKind.INK))).containsExactly(ObjectId("ink"))
        assertThat(Lasso.select(objects, square, setOf(LassoKind.TEXT, LassoKind.IMAGES))).isEmpty()
    }

    @Test
    fun polygonOfTwoPoints_selectsNothing() {
        val ink = stroke("ink", 10f, 10f, 20f, 20f)
        assertThat(Lasso.select(listOf(ink), listOf(PointPt(0f, 0f), PointPt(100f, 100f)), all)).isEmpty()
    }

    @Test
    fun rectangle_fromAnyTwoCorners() {
        assertThat(Lasso.rectangle(PointPt(100f, 0f), PointPt(0f, 100f))).isEqualTo(square)
    }
}
