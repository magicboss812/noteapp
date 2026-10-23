package dev.folio.core.text.flow

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.EditFlow
import dev.folio.core.model.edit.InsertPages
import dev.folio.core.model.edit.PageOps
import dev.folio.core.model.edit.RemovePages
import dev.folio.core.model.edit.TextEdit
import dev.folio.core.model.edit.UpdateObjects
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class FlowReflowTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val reflow = FlowReflow(FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver))))
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, template)!!
    private val capacity = TextZones.capacityUnits(bodyRect.heightPt, template.spacingPt)
    private val flowId = FlowId("body")

    private var pages = 0
    private var objects = 0
    private val newPageId = { PageId("n${pages++}") }
    private val newObjectId = { ObjectId("f${objects++}") }

    private fun lines(n: Int) = (1..n).joinToString("\n\n") { "Line $it" }

    private fun body(
        id: String,
        order: Int,
    ) = FlowFrame(ObjectId(id), flowId, bodyRect, order, FrameRole.BODY, autoGrow = false)

    private fun doc(
        markdown: String,
        vararg pageObjects: List<PageObject>,
    ): Document =
        ModelFixtures.document(
            pageObjects.mapIndexed { i, objects -> ModelFixtures.page("p${i + 1}", objects) },
            flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
        )

    private fun plan(d: Document) = reflow.plan(d, flowId, newPageId = newPageId, newObjectId = newObjectId)

    private fun overflowed(): Document {
        val d = doc(lines(2 * capacity + 8), listOf(body("t", 0)))
        return plan(d).command!!.execute(d).doc
    }

    @Test
    fun overflow_ofTheLastBodyFrame_insertsContinuationPagesWithTheSameSpec() {
        val d = doc(lines(2 * capacity + 8), listOf(body("t", 0)))
        val result = plan(d)
        assertThat(result.layout!!.continuationCount).isEqualTo(2)
        assertThat(result.layout.overflows).isFalse()
        val insert = result.command as InsertPages
        assertThat(insert.atIndex).isEqualTo(1)
        val applied = insert.execute(d).doc
        assertThat(applied.pages.map { it.id.value }).containsExactly("p1", "n0", "n1").inOrder()
        val frames = listOf("n0", "n1").map { applied.page(PageId(it))!!.objects.single() as FlowFrame }
        assertThat(frames.map { it.order }).containsExactly(1, 2).inOrder()
        assertThat(frames.map { it.role }.toSet()).containsExactly(FrameRole.BODY)
        assertThat(frames.map { it.rect }.toSet()).containsExactly(bodyRect)
        assertThat(applied.pageRef(PageId("n1"))!!.spec).isEqualTo(ModelFixtures.A4)
        // Stable: the next pass over the grown document changes nothing.
        val again = plan(applied)
        assertThat(again.command).isNull()
        assertThat(
            again.layout!!
                .slices(2)
                .map { it.blockIndex }
                .first(),
        ).isEqualTo(2 * capacity)
    }

    @Test
    fun overflow_continuesAfterTheLastFramesPage_notAtTheEnd() {
        val d = doc(lines(capacity + 1), listOf(body("t", 0)), emptyList())
        val insert = plan(d).command as InsertPages
        assertThat(insert.atIndex).isEqualTo(1)
    }

    @Test
    fun deletingText_removesTheEmptyContinuationPages_undoRestoresThem() {
        val grown = overflowed()
        val flow = grown.flows[flowId]!!
        val edited = EditFlow(flowId, listOf(TextEdit(0, flow.markdown.length, "Short"))).execute(grown).doc
        val remove = plan(edited).command as RemovePages
        assertThat(remove.ids.map { it.value }).containsExactly("n0", "n1").inOrder()
        val applied = remove.execute(edited)
        assertThat(applied.doc.pages.map { it.id.value }).containsExactly("p1")
        assertThat(applied.inverse.execute(applied.doc).doc).isEqualTo(edited)
    }

    @Test
    fun deletingText_keepsPagesWithOtherObjects_andTheFirstPage() {
        val stroke = ModelFixtures.randomStroke(Random(3), ObjectId("ink"))
        val d = doc("", listOf(body("a", 0)), listOf(body("b", 1)), listOf(body("c", 2), stroke))
        val remove = plan(d).command as RemovePages
        assertThat(remove.ids.map { it.value }).containsExactly("p2")
    }

    @Test
    fun fixedBox_overflows_withoutCreatingPages() {
        val box = FlowFrame(ObjectId("b"), flowId, RectPt(100f, 100f, 300f, 100f + 2 * template.spacingPt), 0, FrameRole.BOX, false)
        val result = plan(doc(lines(5), listOf(box)))
        assertThat(result.command).isNull()
        assertThat(result.layout!!.overflows).isTrue()
    }

    @Test
    fun autoGrowBox_takesTheHeightOfItsTextInWholeUnits() {
        val top = bodyRect.top
        val box = FlowFrame(ObjectId("b"), flowId, RectPt(100f, top, 300f, top + template.spacingPt), 0, FrameRole.BOX, true)
        val update = plan(doc(lines(3), listOf(box))).command as UpdateObjects
        val grown = update.objects.single() as FlowFrame
        assertThat(grown.rect.heightPt).isWithin(1e-3f).of(3 * template.spacingPt)
        assertThat(grown.rect.top).isEqualTo(top)
    }

    @Test
    fun slots_followPageThenFrameOrder_stickyUsesItsContentRect() {
        val sticky = StickyNote(ObjectId("s"), flowId, RectPt(300f, 400f, 420f, 520f), 0f, 0)
        val d = doc("x", listOf(body("b", 1), sticky), listOf(body("a", 0)))
        val slots = FlowFrames.slotsOf(d, flowId)
        assertThat(slots.map { it.frameId.value }).containsExactly("b", "s", "a").inOrder()
        assertThat(slots[1].rect).isEqualTo(TextZones.stickyContentRect(sticky, template.spacingPt))
        assertThat(slots[0].capacityUnits).isEqualTo(capacity)
    }

    @Test
    fun duplicatedPage_keepsItsTextInANewIndependentFlow() {
        val grown = overflowed()
        val layout = plan(grown).layout!!
        val onSecond = layout.markdownOn(PageId("n0"))!!
        assertThat(onSecond).startsWith("Line ${capacity + 1}\n\n")
        assertThat(onSecond).endsWith("Line ${2 * capacity}")
        val result = PageOps.duplicate(grown, listOf(PageId("n0")), { PageId("copy") }) { _, page -> layout.markdownOn(page) }
        val applied = result.command.execute(grown).doc
        val frame = applied.page(PageId("copy"))!!.objects.single() as FlowFrame
        assertThat(frame.flow).isNotEqualTo(flowId)
        assertThat(frame.role).isEqualTo(FrameRole.BOX)
        val copy = applied.flows[frame.flow]!!
        assertThat(copy.markdown).isEqualTo(onSecond)
        // The copy fits its frame and needs no pages; editing the original flow leaves it alone.
        val copyPlan = reflow.plan(applied, frame.flow)
        assertThat(copyPlan.command).isNull()
        assertThat(copyPlan.layout!!.overflows).isFalse()
        val edited = EditFlow(flowId, listOf(TextEdit(0, 4, "Row"))).execute(applied).doc
        assertThat(edited.flows[frame.flow]!!.markdown).isEqualTo(onSecond)
        assertThat(FlowFrames.slotsOf(edited, flowId).map { it.pageId.value }).containsExactly("p1", "n0", "n1").inOrder()
    }
}
