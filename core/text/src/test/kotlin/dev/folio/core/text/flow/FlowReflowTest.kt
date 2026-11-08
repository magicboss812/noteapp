package dev.folio.core.text.flow

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Document
import dev.folio.core.model.FlowBox
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextAlign
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
import kotlin.math.roundToInt
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
    fun boxBetweenTheLines_baselinesOnRulesPlusOffsetMinusLift_lineBoxesWholeUnits() {
        // Grid invariant with a vertical offset (A-057, P07-T14): the whole box shifts, its line boxes stay k * U.
        val u = template.spacingPt
        val offset = 0.4f * u
        val lift = 0.15f
        val top = bodyRect.top + 4 * u + offset
        val frame = FlowFrame(ObjectId("b"), flowId, RectPt(150f, top, 420f, top + 14 * u), 0, FrameRole.BOX, false)
        val markdown =
            "# Heading\n\nA paragraph long enough to wrap onto more lines inside this box, with **bold** and \$x^2\$ math, " +
                "again and again until it wraps.\n\n- a list item\n- [ ] a task"
        val flow = ModelFixtures.flow(flowId.value, markdown)
        val d =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p1", listOf(frame))),
                flows =
                    listOf(
                        flow.copy(
                            style = flow.style.copy(baselineLiftUnits = lift),
                            box = FlowBox.FIXED.copy(offsetPt = offset),
                            autoContinue = false,
                        ),
                    ),
            )

        val runs = plan(d).layout!!.runs(0)

        assertThat(runs.size).isAtLeast(4)
        var lines = 0
        for (run in runs) {
            val layout = run.layout
            assertThat(layout.boxUnits).isAtLeast(1)
            for (line in run.firstLine until run.firstLine + run.lineCount) {
                assertThat(layout.lineTopPt(line + 1) - layout.lineTopPt(line)).isWithin(1e-3f).of(layout.boxUnits * u)
                val baseline = run.topPt + layout.baselinePt(line)
                val rule = bodyRect.top + ((baseline - bodyRect.top - offset + lift * u) / u).roundToInt() * u
                assertThat(baseline).isWithin(BASELINE_TOLERANCE_PT).of(rule + offset - lift * u)
                lines++
            }
        }
        assertThat(lines).isAtLeast(5)
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

    private fun boxDoc(
        markdown: String,
        frame: FlowFrame,
        box: FlowBox = FlowBox.created(frame.rect.left),
        align: TextAlign = TextAlign.START,
        autoContinue: Boolean = true,
    ): Document {
        val flow = ModelFixtures.flow(flowId.value, markdown)
        return ModelFixtures.document(
            listOf(ModelFixtures.page("p1", listOf(frame)), ModelFixtures.page("p2")),
            flows = listOf(flow.copy(style = flow.style.copy(align = align), autoContinue = autoContinue, box = box)),
        )
    }

    private fun newBox(
        xPt: Float,
        yPt: Float,
        align: TextAlign = TextAlign.START,
    ): FlowFrame =
        FlowFrame(ObjectId("b"), flowId, TextZones.newBoxAt(ModelFixtures.A4, template, xPt, yPt, 0f, align), 0, FrameRole.BOX, true)

    private fun boxAfter(
        d: Document,
        markdown: String,
    ): FlowFrame {
        val edited = EditFlow(flowId, listOf(TextEdit(0, d.flows[flowId]!!.markdown.length, markdown))).execute(d).doc
        val update = plan(edited).command as UpdateObjects
        return update.objects.single() as FlowFrame
    }

    @Test
    fun autoWidthBox_growsRightWithTyping_stopsAtTheZoneEdgeAndWraps_shrinksOnDelete() {
        val frame = newBox(200f, bodyRect.top + 3.5f * template.spacingPt)
        val d = boxDoc("", frame)

        val short = boxAfter(d, "Hi")
        val longer = boxAfter(d, "Hi there, friend")
        val wrapped = boxAfter(d, "word ".repeat(60).trim())
        val shrunk =
            boxAfter(ModelFixtures.document(listOf(ModelFixtures.page("p1", listOf(longer))), flows = d.flows.values.toList()), "H")

        assertThat(short.rect.left).isEqualTo(200f)
        assertThat(short.rect.right).isGreaterThan(200f + TextZones.AUTO_MIN_WIDTH_PT)
        assertThat(longer.rect.left).isEqualTo(200f)
        assertThat(longer.rect.right).isGreaterThan(short.rect.right)
        assertThat(longer.rect.heightPt).isWithin(1e-3f).of(template.spacingPt)
        assertThat(wrapped.rect.right).isWithin(1e-3f).of(bodyRect.right)
        assertThat(wrapped.rect.heightPt).isGreaterThan(2.5f * template.spacingPt)
        assertThat(shrunk.rect.right).isLessThan(short.rect.right)
        assertThat(shrunk.rect.left).isEqualTo(200f)
    }

    @Test
    fun autoWidthBox_endAligned_growsLeftFromTheAnchor() {
        val frame = newBox(400f, bodyRect.top + 3.5f * template.spacingPt, TextAlign.END)
        val d = boxDoc("", frame, FlowBox.created(400f), TextAlign.END)

        val grown = boxAfter(d, "Hello there")

        assertThat(grown.rect.right).isEqualTo(400f)
        assertThat(grown.rect.left).isLessThan(400f - 20f)
        assertThat(grown.rect.left).isAtLeast(bodyRect.left)
    }

    @Test
    fun box_passingTheZoneBottom_continuesOnTheNextPageBetweenTheSameBorders_removedAgain() {
        val u = template.spacingPt
        val top = bodyRect.bottom - 2 * u
        val frame = FlowFrame(ObjectId("b"), flowId, RectPt(150f, top, 350f, top + u), 0, FrameRole.BOX, true)
        val d = boxDoc(lines(5), frame, FlowBox.FIXED)

        val result = plan(d)
        val applied = result.command!!.execute(d).doc

        assertThat(applied.pages.map { it.id.value }).containsExactly("p1", "n0", "p2").inOrder()
        val first = applied.page(PageId("p1"))!!.objects.single() as FlowFrame
        val next = applied.page(PageId("n0"))!!.objects.single() as FlowFrame
        assertThat(first.rect.bottom).isWithin(1e-3f).of(bodyRect.bottom)
        assertThat(next.role).isEqualTo(FrameRole.BOX)
        assertThat(next.order).isEqualTo(1)
        assertThat(next.rect.left).isEqualTo(150f)
        assertThat(next.rect.right).isEqualTo(350f)
        assertThat(next.rect.top).isEqualTo(bodyRect.top)
        assertThat(next.rect.heightPt).isWithin(1e-3f).of(3 * u)
        assertThat(plan(applied).command).isNull()
        // Deleting the text again removes the continuation page.
        val edited = EditFlow(flowId, listOf(TextEdit(0, applied.flows[flowId]!!.markdown.length, "Short"))).execute(applied).doc
        val removed = plan(edited).command!!.execute(edited).doc
        assertThat(removed.pages.map { it.id.value }).containsExactly("p1", "p2").inOrder()
        assertThat((removed.page(PageId("p1"))!!.objects.single() as FlowFrame).rect.heightPt).isWithin(1e-3f).of(u)
    }

    @Test
    fun box_ofAFlowThatDoesNotContinue_growsPastTheZone_withoutPages() {
        val u = template.spacingPt
        val top = bodyRect.bottom - 2 * u
        val frame = FlowFrame(ObjectId("b"), flowId, RectPt(150f, top, 350f, top + u), 0, FrameRole.BOX, true)

        val grown = plan(boxDoc(lines(5), frame, FlowBox.FIXED, autoContinue = false)).command as UpdateObjects

        assertThat((grown.objects.single() as FlowFrame).rect.heightPt).isWithin(1e-3f).of(5 * u)
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

    private companion object {
        // 0.5 px at about 5 px per pt (ADR-008), the grid invariant's tolerance.
        const val BASELINE_TOLERANCE_PT = 0.1f
    }
}
