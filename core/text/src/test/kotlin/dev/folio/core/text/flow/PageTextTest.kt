package dev.folio.core.text.flow

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.Document
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.GridUnit
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageTextTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val unitPt = GridUnit.of(template).unitPt
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, template)!!
    private val flowId = FlowId("body")
    private val body = FlowFrame(ObjectId("body-frame"), flowId, bodyRect, 0, FrameRole.BODY, autoGrow = false)

    private fun doc(
        markdown: String,
        vararg objects: PageObject,
    ): Document =
        ModelFixtures.document(
            listOf(ModelFixtures.page("p1", objects.toList())),
            flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
        )

    private fun Document.withMarkdown(markdown: String): Document {
        val flow = checkNotNull(flows[flowId])
        return copy(flows = flows.put(flowId, flow.copy(markdown = markdown)))
    }

    private fun textOf(d: Document): PageText = checkNotNull(PageTexts.of(d, layouter)[PageId("p1")])

    @Test
    fun runs_wholeBlocks_sitOnTheFrameGridFromTheSlotTop() {
        val text = textOf(doc("Alpha\n\nBeta", body))

        val runs = text.runsOf(body.id)
        assertThat(runs).hasSize(2)
        assertThat(runs.map { it.topPt }).containsExactly(bodyRect.top, bodyRect.top + unitPt).inOrder()
        assertThat(runs.map { it.leftPt }.toSet()).containsExactly(bodyRect.left)
        assertThat(runs.map { it.widthPt }.toSet()).containsExactly(bodyRect.widthPt)
        assertThat(runs.map { it.partial }.toSet()).containsExactly(false)
        // Every line box of the run ends on a rule of the grid.
        assertThat(runs[1].sliceBottomPt - bodyRect.top).isWithin(1e-3f).of(2 * unitPt)
    }

    @Test
    fun runs_blockContinuedInAnotherFrame_startsMidBlockAtTheSlotTop() {
        val first = FlowFrame(ObjectId("a"), flowId, RectPt(100f, bodyRect.top, 300f, bodyRect.top + 2 * unitPt), 0, FrameRole.BOX, false)
        val secondTop = bodyRect.top + 6 * unitPt
        val second = FlowFrame(ObjectId("b"), flowId, RectPt(100f, secondTop, 300f, secondTop + 2 * unitPt), 1, FrameRole.BOX, false)
        val d = doc("lorem ipsum dolor sit amet ".repeat(20).trim(), first, second)
        val layout =
            layouter.layout(
                MarkdownDocument.parse(d.flows.getValue(flowId).markdown),
                ModelFixtures.FLOW_STYLE.toTextStyle(),
                0,
                FlowFrames.slotsOf(d, flowId),
            )

        val head = layout.runs(0).single()
        val tail = layout.runs(1).single()
        assertThat(head.firstLine).isEqualTo(0)
        assertThat(head.lineCount).isEqualTo(2)
        assertThat(tail.firstLine).isEqualTo(2)
        assertThat(tail.lineCount).isEqualTo(2)
        assertThat(listOf(head.partial, tail.partial)).containsExactly(true, true)
        // The continuation's first line box starts at the frame top, so the block's own top is two line boxes higher.
        assertThat(tail.sliceTopPt).isWithin(1e-3f).of(secondTop)
        assertThat(tail.topPt).isWithin(1e-3f).of(secondTop - 2 * unitPt)
    }

    @Test
    fun pageTexts_stickyNotesAreSkipped_andPagesWithoutTextAreAbsent() {
        val sticky = StickyNote(ObjectId("s"), FlowId("sticky"), RectPt(300f, 400f, 420f, 520f), 0f, 0)
        val d =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p1", listOf(body, sticky)), ModelFixtures.page("p2")),
                flows = listOf(ModelFixtures.flow(flowId.value, "Alpha"), ModelFixtures.flow("sticky", "Note")),
            )

        val texts = PageTexts.of(d, layouter)

        assertThat(texts.keys).containsExactly(PageId("p1"))
        assertThat(
            texts
                .getValue(PageId("p1"))
                .runs
                .map { it.frameId }
                .toSet(),
        ).containsExactly(body.id)
    }

    @Test
    fun isEmptyIn_coversTheInkBoundsOfTheRuns() {
        val text = textOf(doc("Alpha", body))

        assertThat(text.isEmptyIn(RectPt(bodyRect.left, bodyRect.top, bodyRect.left + 20f, bodyRect.top + unitPt))).isFalse()
        assertThat(text.isEmptyIn(RectPt(0f, 700f, 50f, 800f))).isTrue()
        assertThat(PageText.EMPTY.isEmptyIn(RectPt(0f, 0f, 595f, 842f))).isTrue()
    }

    @Test
    fun changedBounds_unchangedFlow_isEmpty() {
        val d = doc("Alpha\n\nBeta\n\nGamma", body)

        assertThat(PageText.changedBounds(textOf(d), textOf(d))).isEmpty()
    }

    @Test
    fun changedBounds_editWithTheSameLineCount_listsOnlyThatBlock() {
        val before = textOf(doc("Alpha\n\nBeta\n\nGamma", body))
        val after = textOf(doc("Alpha\n\nBeta two\n\nGamma", body))

        val changed = PageText.changedBounds(before, after)

        val beta = before.runs[1].bounds
        val newBeta = after.runs[1].bounds
        assertThat(changed).containsExactly(beta, newBeta)
    }

    @Test
    fun changedBounds_insertedLine_listsTheEditedBlockAndEveryShiftedOne() {
        val before = textOf(doc("Alpha\n\nBeta\n\nGamma\n\nDelta", body))
        val after = textOf(doc("Alpha\n\nBeta\\\nsecond line\n\nGamma\n\nDelta", body))

        val changed = PageText.changedBounds(before, after)

        // Alpha is untouched; Beta changed; Gamma and Delta moved down one line box.
        assertThat(changed).containsExactly(
            before.runs[1].bounds,
            after.runs[1].bounds,
            before.runs[2].bounds,
            after.runs[2].bounds,
            before.runs[3].bounds,
            after.runs[3].bounds,
        )
        assertThat(changed).doesNotContain(before.runs[0].bounds)
    }

    @Test
    fun changedBounds_removedBlock_listsTheOldBounds() {
        val before = textOf(doc("Alpha\n\nBeta", body))
        val after = textOf(doc("Alpha", body))

        assertThat(PageText.changedBounds(before, after)).containsExactly(before.runs[1].bounds)
    }
}
