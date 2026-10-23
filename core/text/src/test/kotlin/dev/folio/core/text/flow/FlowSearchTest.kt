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
import dev.folio.core.model.TextZones
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowSearchTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val reflow = FlowReflow(FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver))))
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, template)!!
    private val capacity = TextZones.capacityUnits(bodyRect.heightPt, template.spacingPt)
    private val body = FlowId("body")

    private var pages = 0

    /** A body flow of [lineCount] one-line paragraphs ("Line n", [extra] appended to line n) on as many pages as it needs. */
    private fun note(
        lineCount: Int,
        extra: Map<Int, String> = emptyMap(),
    ): Document {
        val markdown = (1..lineCount).joinToString("\n\n") { n -> "Line $n" + extra[n].orEmpty() }
        val frame = FlowFrame(ObjectId("t"), body, bodyRect, 0, FrameRole.BODY, autoGrow = false)
        val d = ModelFixtures.document(listOf(ModelFixtures.page("p1", listOf(frame))), listOf(ModelFixtures.flow(body.value, markdown)))
        val plan = reflow.plan(d, body, newPageId = { PageId("n${pages++}") })
        return plan.command?.execute(d)?.doc ?: d
    }

    private fun layout(
        d: Document,
        flow: FlowId = body,
    ) = reflow.plan(d, flow).layout!!

    @Test
    fun firstPage_isThePageOfTheFirstMatchingLine_ignoringCase() {
        val d = note(2 * capacity + 5, mapOf(capacity + 3 to " needle", 2 * capacity + 2 to " Needle"))
        assertThat(FlowSearch.firstPage(layout(d), "NEEDLE")).isEqualTo(PageId("n0"))
        assertThat(FlowSearch.firstPage(layout(d), "Line 1")).isEqualTo(PageId("p1"))
    }

    @Test
    fun firstPage_anyTermMatches_earliestInFlowOrder() {
        val d = note(2 * capacity + 5, mapOf(capacity + 3 to " apple", 2 * capacity + 2 to " pear"))
        assertThat(FlowSearch.firstPage(layout(d), "pear  apple")).isEqualTo(PageId("n0"))
    }

    @Test
    fun firstPage_miss_orBlankQuery_isNull() {
        val d = note(capacity + 2)
        assertThat(FlowSearch.firstPage(layout(d), "absent")).isNull()
        assertThat(FlowSearch.firstPage(layout(d), "  ")).isNull()
    }

    @Test
    fun firstPage_textOverflowingAFixedBox_isOnNoPage() {
        val flow = FlowId("box")
        val box = FlowFrame(ObjectId("b"), flow, RectPt(100f, 100f, 300f, 100f + template.spacingPt), 0, FrameRole.BOX, false)
        val d =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p1", listOf(box))),
                listOf(ModelFixtures.flow(flow.value, "Shown\n\nHidden")),
            )
        assertThat(FlowSearch.firstPage(layout(d, flow), "shown")).isEqualTo(PageId("p1"))
        assertThat(FlowSearch.firstPage(layout(d, flow), "hidden")).isNull()
    }

    @Test
    fun firstPage_ofTheDocument_isTheEarliestPageOverAllFlows() {
        val grown = note(capacity + 5, mapOf(capacity + 3 to " needle"))
        val flow = FlowId("box")
        val box = FlowFrame(ObjectId("b"), flow, RectPt(100f, 100f, 300f, 100f + template.spacingPt), 0, FrameRole.BOX, true)
        val p1 = grown.page(PageId("p1"))!!
        val d =
            grown.copy(
                flows = grown.flows.put(flow, ModelFixtures.flow(flow.value, "A needle here")),
                pageBodies = grown.pageBodies.put(p1.id, p1.copy(objects = p1.objects.add(box))),
            )
        assertThat(FlowSearch.firstPage(d, listOf(layout(d), layout(d, flow)), "needle")).isEqualTo(PageId("p1"))
        assertThat(FlowSearch.firstPage(d, listOf(layout(d)), "needle")).isEqualTo(PageId("n0"))
        assertThat(FlowSearch.firstPage(d, listOf(layout(d), layout(d, flow)), "nothing")).isNull()
    }
}
