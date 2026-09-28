package dev.folio.core.model.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.geometry.Affine
import dev.folio.core.testing.ModelFixtures
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class CommandsTest {
    private val random = Random(7)
    private val objs = List(5) { i -> ModelFixtures.randomStroke(random, ObjectId("o$i")) }
    private val p1 = ModelFixtures.page("p1", objs)
    private val doc = ModelFixtures.document(listOf(p1, ModelFixtures.page("p2"), ModelFixtures.page("p3")))

    @Test
    fun reorder_allOps_keepSelectionOrder() {
        fun order(op: ReorderOp): List<String> =
            ReorderObjects(p1.id, listOf(ObjectId("o1"), ObjectId("o2")), op)
                .execute(doc)
                .doc
                .page(p1.id)!!
                .objects
                .map { it.id.value }
        assertThat(order(ReorderOp.BRING_TO_FRONT)).containsExactly("o0", "o3", "o4", "o1", "o2").inOrder()
        assertThat(order(ReorderOp.SEND_TO_BACK)).containsExactly("o1", "o2", "o0", "o3", "o4").inOrder()
        assertThat(order(ReorderOp.FORWARD)).containsExactly("o0", "o3", "o1", "o2", "o4").inOrder()
        assertThat(order(ReorderOp.BACKWARD)).containsExactly("o1", "o2", "o0", "o3", "o4").inOrder()
    }

    @Test
    fun replaceObjects_insertsAtLowestRemovedIndex() {
        val added = listOf(ModelFixtures.randomStroke(random, ObjectId("a")), ModelFixtures.randomStroke(random, ObjectId("b")))
        val result = ReplaceObjects(p1.id, listOf(ObjectId("o3"), ObjectId("o1")), added).execute(doc)
        assertThat(
            result.doc
                .page(p1.id)!!
                .objects
                .map { it.id.value },
        ).containsExactly("o0", "a", "b", "o2", "o4").inOrder()
        assertThat(result.inverse.execute(result.doc).doc).isEqualTo(doc)
    }

    @Test
    fun movePages_blockToEnd() {
        val result = MovePages(listOf(PageId("p1"), PageId("p2")), 1).execute(doc)
        assertThat(result.doc.pages.map { it.id.value }).containsExactly("p3", "p1", "p2").inOrder()
    }

    @Test
    fun editFlow_multipleEdits_inverseRestoresText() {
        val d = ModelFixtures.document(listOf(p1), flows = listOf(ModelFixtures.flow("f", "hello world")))
        val result = EditFlow(FlowId("f"), listOf(TextEdit(0, 5, "HELLO"), TextEdit(11, 11, "!"), TextEdit(5, 6, ""))).execute(d)
        assertThat(
            result.doc.flows
                .getValue(FlowId("f"))
                .markdown,
        ).isEqualTo("HELLOworld!")
        assertThat(result.inverse.execute(result.doc).doc).isEqualTo(d)
    }

    @Test
    fun transformObjects_translate_movesBoundsExactly() {
        val result = TransformObjects(p1.id, listOf(ObjectId("o0")), Affine.translate(10f, -5f)).execute(doc)
        val before = objs[0].bounds
        val after =
            result.doc
                .page(p1.id)!!
                .objects[0]
                .bounds
        assertThat(after.left).isWithin(1e-3f).of(before.left + 10f)
        assertThat(after.top).isWithin(1e-3f).of(before.top - 5f)
        assertThat(result.inverse.execute(result.doc).doc).isEqualTo(doc)
    }

    @Test
    fun updatePageSpec_infinite_cachesContentBoundsInRef() {
        val result = UpdatePageSpec(p1.id, PageSpec.Infinite(ModelFixtures.A4)).execute(doc)
        assertThat(result.doc.pageRef(p1.id)!!.contentBounds).isEqualTo(p1.contentBounds())
        assertThat(doc.pageRef(p1.id)!!.contentBounds).isNull()
    }

    @Test
    fun objectCommand_pageNotLoaded_throws() {
        val unloaded: Document = doc.copy(pageBodies = doc.pageBodies.remove(p1.id))
        assertThrows(IllegalStateException::class.java) { RemoveObjects(p1.id, listOf(ObjectId("o0"))).execute(unloaded) }
        assertThat(RemoveObjects(p1.id, emptyList()).requiredPages).containsExactly(p1.id)
    }

    @Test
    fun addObjects_duplicateId_throws() {
        assertThrows(IllegalArgumentException::class.java) { AddObjects(p1.id, listOf(objs[0])).execute(doc) }
    }
}
