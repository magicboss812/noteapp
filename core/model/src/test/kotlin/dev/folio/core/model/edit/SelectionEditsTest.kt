package dev.folio.core.model.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Shape
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.geometry.Affine
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.model.shape.ShapeGeometry
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import kotlin.random.Random

class SelectionEditsTest {
    private val random = Random(11)
    private val strokes = List(50) { i -> ModelFixtures.randomStroke(random, ObjectId("s$i")) }
    private val shape =
        ShapeGeometry.create(
            ObjectId("shape"),
            ShapeKind.RECTANGLE,
            listOf(PointPt(10f, 10f), PointPt(60f, 40f)),
            StrokeStyle(0xFF000000.toInt(), 2f, false),
            null,
        )
    private val frame = FlowFrame(ObjectId("box"), FlowId("f"), RectPt(20f, 300f, 200f, 340f), 0, FrameRole.BOX, autoGrow = true)
    private val page = ModelFixtures.page("p1", strokes + shape + frame)
    private val doc = ModelFixtures.document(listOf(page), flows = listOf(ModelFixtures.flow("f", "text")))
    private val selected = strokes.map { it.id } + shape.id

    // Every edit is one command whose inverse restores the document exactly, and one undo step in the manager.
    private fun assertOneUndoStep(edit: SelectionEdit) {
        val undo = UndoManager(FakeClock())
        val applied = edit.command(page.id, selected).execute(doc)
        undo.push(applied)
        assertThat(applied.doc).isNotEqualTo(doc)
        val undone = checkNotNull(undo.undo(applied.doc))
        assertThat(undone.doc).isEqualTo(doc)
        assertThat(undo.undo(undone.doc)).isNull()
    }

    @Test
    fun transform_moveScaleRotate_eachOneUndoStep() {
        assertOneUndoStep(SelectionEdit.Transform(Affine.translate(30f, -12f)))
        assertOneUndoStep(SelectionEdit.Transform(Affine.scale(1.5f, 1.5f, 100f, 100f)))
        assertOneUndoStep(SelectionEdit.Transform(Affine.rotate(45f, 250f, 250f)))
    }

    @Test
    fun transform_movesEveryStrokePoint() {
        val moved =
            SelectionEdit
                .Transform(Affine.translate(30f, -12f))
                .command(page.id, selected)
                .execute(doc)
                .doc
        val before = strokes[7]
        val after = moved.page(page.id)!!.objects.first { it.id == before.id } as InkStroke
        assertThat(after.inputs.x[0]).isWithin(1e-3f).of(before.inputs.x[0] + 30f)
        assertThat(after.inputs.y[0]).isWithin(1e-3f).of(before.inputs.y[0] - 12f)
        // The unselected frame stays.
        assertThat(moved.page(page.id)!!.objects.last()).isEqualTo(frame)
    }

    @Test
    fun deleteRecolorReorder_eachOneUndoStep() {
        assertOneUndoStep(SelectionEdit.Delete)
        assertOneUndoStep(SelectionEdit.Recolor(0xFFDC2626.toInt()))
        // The selection is everything but the frame on top: to the front changes the order.
        assertOneUndoStep(SelectionEdit.Reorder(ReorderOp.BRING_TO_FRONT))
    }

    @Test
    fun recolor_strokesAndShapes() {
        val red = 0xFFDC2626.toInt()
        val after =
            SelectionEdit
                .Recolor(red)
                .command(page.id, selected)
                .execute(doc)
                .doc
                .page(page.id)!!
                .objects
        assertThat(after.filterIsInstance<InkStroke>().map { it.brush.argb }.distinct()).containsExactly(red)
        assertThat((after.first { it.id == shape.id } as Shape).stroke.argb).isEqualTo(red)
    }

    @Test
    fun copies_freshIdsOffsetAndWithoutTextObjects() {
        var n = 0
        val copies = ObjectCopies.of(listOf(strokes[0], shape, frame), Affine.translate(16f, 16f)) { ObjectId("c${n++}") }
        assertThat(copies.map { it.id.value }).containsExactly("c0", "c1").inOrder()
        assertThat(copies[1].bounds.left).isWithin(1e-3f).of(shape.bounds.left + 16f)
        assertThat(ObjectCopies.bounds(listOf(shape, frame))).isEqualTo(shape.bounds.union(frame.bounds))
    }

    @Test
    fun add_pastesOnTopAndUndoRemovesThem() {
        val copies = ObjectCopies.of(strokes.take(3), Affine.translate(5f, 5f))
        val applied = SelectionEdit.Add(copies).command(page.id, emptyList()).execute(doc)
        assertThat(
            applied.doc
                .page(page.id)!!
                .objects
                .takeLast(3),
        ).isEqualTo(copies)
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }
}
