package dev.folio.core.model.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.Background
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.PdfBackground
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import org.junit.Assert.assertThrows
import org.junit.Test
import kotlin.random.Random

class PageOpsTest {
    private val random = Random(11)
    private val strokes = List(3) { i -> ModelFixtures.randomStroke(random, ObjectId("o$i")) }
    private val doc =
        ModelFixtures.document(
            listOf(ModelFixtures.page("p1", strokes), ModelFixtures.page("p2"), ModelFixtures.page("p3")),
        )

    private fun order(d: Document) = d.pages.map { it.id.value }

    private fun ids(vararg id: String) = id.map(::PageId)

    private var next = 0
    private val newId = { PageId("n${next++}") }

    @Test
    fun blankNear_after_insertsEmptyPageWithAnchorSpecAndUndoes() {
        val spec = PageSpec.Fixed(PaperSize.A5, Orientation.LANDSCAPE)
        val anchored = ModelFixtures.document(listOf(ModelFixtures.page("p1", spec = spec), ModelFixtures.page("p2")))
        val applied = PageOps.blankNear(anchored, PageId("p1"), PageOps.Side.AFTER, ModelFixtures.LINED_BACKGROUND, newId).execute(anchored)
        assertThat(order(applied.doc)).containsExactly("p1", "n0", "p2").inOrder()
        assertThat(applied.doc.pageRef(PageId("n0"))!!.spec).isEqualTo(spec)
        assertThat(applied.doc.page(PageId("n0"))!!.objects).isEmpty()
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(anchored)
    }

    @Test
    fun blankNear_before_insertsAtAnchorIndex() {
        val applied = PageOps.blankNear(doc, PageId("p2"), PageOps.Side.BEFORE, ModelFixtures.LINED_BACKGROUND, newId).execute(doc)
        assertThat(order(applied.doc)).containsExactly("p1", "n0", "p2", "p3").inOrder()
    }

    @Test
    fun blankNear_pdfPage_isBlankA4OnFallback() {
        val pdf = PdfBackground(AssetId("a"), 0)
        val fallback = ModelFixtures.LINED_BACKGROUND.copy(paperArgb = 0xFFFFF8E7.toInt())
        val pdfPage =
            ModelFixtures
                .page("p1", spec = PageSpec.Custom(300f, 500f))
                .let { it.copy(background = Background(0, it.background.template, pdf)) }
        val d = ModelFixtures.document(listOf(pdfPage))
        val added =
            PageOps
                .blankNear(d, pdfPage.id, PageOps.Side.AFTER, fallback, newId)
                .execute(d)
                .doc
                .pageRef(PageId("n0"))!!
        assertThat(added.spec).isEqualTo(PageSpec.Fixed(PaperSize.A4, Orientation.PORTRAIT))
        assertThat(added.background).isEqualTo(fallback)
    }

    @Test
    fun duplicate_copiesInkWithFreshIdsAfterTheLastSelectedPageAndUndoes() {
        var obj = 0
        val applied = PageOps.duplicate(doc, ids("p1"), newId) { ObjectId("c${obj++}") }.execute(doc)
        assertThat(order(applied.doc)).containsExactly("p1", "n0", "p2", "p3").inOrder()
        val copy = applied.doc.page(PageId("n0"))!!
        assertThat(copy.objects.map { it.id.value }).containsExactly("c0", "c1", "c2").inOrder()
        assertThat(copy.objects.map { (it as dev.folio.core.model.InkStroke).inputs }).isEqualTo(strokes.map { it.inputs })
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }

    @Test
    fun duplicate_textFrames_stayBehind() {
        val frame = FlowFrame(ObjectId("t"), FlowId("f"), RectPt(0f, 0f, 100f, 100f), 0, FrameRole.BODY, false)
        val d = ModelFixtures.document(listOf(ModelFixtures.page("p1", listOf(frame, strokes[0]))), flows = listOf(ModelFixtures.flow("f")))
        val copy =
            PageOps
                .duplicate(d, ids("p1"), newId)
                .execute(d)
                .doc
                .page(PageId("n0"))!!
        assertThat(copy.objects).hasSize(1)
    }

    @Test
    fun duplicate_severalPages_keepDocumentOrder() {
        val applied = PageOps.duplicate(doc, ids("p3", "p1"), newId).execute(doc)
        assertThat(order(applied.doc)).containsExactly("p1", "p2", "p3", "n0", "n1").inOrder()
        assertThat(applied.doc.page(PageId("n0"))!!.objects).hasSize(3)
    }

    @Test
    fun delete_removesPagesAndUndoRestoresThemWithInk() {
        val applied = PageOps.delete(doc, ids("p1", "p3"))!!.execute(doc)
        assertThat(order(applied.doc)).containsExactly("p2")
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }

    @Test
    fun delete_everyPage_isRefused() {
        assertThat(PageOps.delete(doc, ids("p1", "p2", "p3"))).isNull()
        assertThat(PageOps.delete(doc, emptyList())).isNull()
    }

    @Test
    fun delete_unknownPage_throws() {
        assertThrows(IllegalArgumentException::class.java) { PageOps.delete(doc, ids("zz")) }
    }

    @Test
    fun reorder_movePages_undoRestoresOrder() {
        val applied = MovePages(ids("p1"), 2).execute(doc)
        assertThat(order(applied.doc)).containsExactly("p2", "p3", "p1").inOrder()
        assertThat(order(applied.inverse.execute(applied.doc).doc)).containsExactly("p1", "p2", "p3").inOrder()
    }

    @Test
    fun restyle_oneBatchChangesOnlyDifferingPagesAndUndoesTogether() {
        val a3 = PageSpec.Fixed(PaperSize.A3, Orientation.PORTRAIT)
        val cream = ModelFixtures.LINED_BACKGROUND.copy(paperArgb = 0xFFFFF8E7.toInt())
        val command = PageOps.restyle(doc, ids("p1", "p2"), { a3 }, { cream })!!
        val applied = command.execute(doc)
        assertThat(applied.doc.pageRef(PageId("p1"))!!.spec).isEqualTo(a3)
        assertThat(applied.doc.pageRef(PageId("p2"))!!.background).isEqualTo(cream)
        assertThat(applied.doc.pageRef(PageId("p3"))!!.spec).isEqualTo(ModelFixtures.A4)
        assertThat(applied.doc.page(PageId("p1"))!!.objects).hasSize(3)
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(doc)
    }

    @Test
    fun restyle_nothingChanges_isNull() {
        assertThat(PageOps.restyle(doc, ids("p1"), { it.spec }, { it.background })).isNull()
    }
}
