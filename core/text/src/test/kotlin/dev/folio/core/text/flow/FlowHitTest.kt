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
import dev.folio.core.model.TextZones
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import dev.folio.core.text.layout.typeface
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowHitTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val unitPt = GridUnit.of(template).unitPt
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, template)!!
    private val flowId = FlowId("body")
    private val body = FlowFrame(ObjectId("body-frame"), flowId, bodyRect, 0, FrameRole.BODY, autoGrow = false)
    private val page = PageId("p1")

    private fun layoutOf(markdown: String): FlowLayout {
        val doc: Document =
            ModelFixtures.document(
                listOf(ModelFixtures.page("p1", listOf(body))),
                flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
            )
        return checkNotNull(PageTexts.layouts(doc, layouter)[flowId])
    }

    private fun midLine(n: Int) = bodyRect.top + (n + 0.5f) * unitPt

    @Test
    fun hit_pointOnABlock_returnsItsIndexAndLine() {
        val layout = layoutOf("Alpha\n\nBeta")

        assertThat(layout.hit(page, bodyRect.left + 5f, midLine(0))).isEqualTo(BlockHit(0, 0, 0, pastEnd = false))
        assertThat(layout.hit(page, bodyRect.left + 5f, midLine(1))).isEqualTo(BlockHit(0, 1, 0, pastEnd = false))
    }

    @Test
    fun hit_pointOnALaterLineOfABlock_returnsThatLine() {
        val layout = layoutOf("word ".repeat(400).trim())

        val hit = checkNotNull(layout.hit(page, bodyRect.left + 5f, midLine(2)))

        assertThat(hit.blockIndex).isEqualTo(0)
        assertThat(hit.line).isEqualTo(2)
    }

    @Test
    fun hit_belowTheLastBlock_isThatBlockPastTheEnd() {
        val layout = layoutOf("Alpha\n\nBeta")

        val hit = checkNotNull(layout.hit(page, bodyRect.left + 5f, midLine(10)))

        assertThat(hit.blockIndex).isEqualTo(1)
        assertThat(hit.pastEnd).isTrue()
    }

    @Test
    fun hit_emptyFlow_isBlockZeroPastTheEnd() {
        val layout = layoutOf("")

        assertThat(layout.hit(page, bodyRect.left + 5f, midLine(0))).isEqualTo(BlockHit(0, 0, 0, pastEnd = true))
    }

    @Test
    fun hit_outsideTheFrameOrOnAnotherPage_isNull() {
        val layout = layoutOf("Alpha")

        assertThat(layout.hit(page, bodyRect.left - 5f, midLine(0))).isNull()
        assertThat(layout.hit(PageId("other"), bodyRect.left + 5f, midLine(0))).isNull()
    }

    private fun offsetAt(
        layout: FlowLayout,
        xPt: Float,
        line: Int = 0,
        yPt: Float = midLine(line),
    ): Int = layout.sourceOffsetAt(checkNotNull(layout.hit(page, xPt, yPt)), xPt)

    @Test
    fun sourceOffsetAt_tapLeftOfAndRightOfALine_isItsStartAndEnd() {
        val layout = layoutOf("Hello world")

        assertThat(offsetAt(layout, bodyRect.left + 1f)).isEqualTo(0)
        assertThat(offsetAt(layout, bodyRect.right - 1f)).isEqualTo(11)
    }

    @Test
    fun sourceOffsetAt_tableCell_landsInTheTappedCellText() {
        val source = "| a | bb |\n| --- | --- |\n| one | two |"
        val layout = layoutOf(source)
        val table = checkNotNull(layout.layoutOf(0)?.table)
        val secondColumn = bodyRect.left + table.columnLeftPt(1)

        assertThat(offsetAt(layout, bodyRect.left + 1f, line = 1)).isEqualTo(source.indexOf("one"))
        assertThat(offsetAt(layout, secondColumn - 1f, line = 1)).isEqualTo(source.indexOf("one") + 3)
        assertThat(offsetAt(layout, secondColumn + 1f, line = 0)).isEqualTo(source.indexOf("bb"))
        assertThat(offsetAt(layout, bodyRect.right - 1f, line = 1)).isEqualTo(source.indexOf("two") + 3)
    }

    private fun targetAt(
        layout: FlowLayout,
        xPt: Float,
        line: Int = 0,
    ): TapTarget? = layout.tapTargetAt(checkNotNull(layout.hit(page, xPt, midLine(line))), xPt)

    @Test
    fun tapTargetAt_taskMarkerGutter_isTheCheckbox() {
        val layout = layoutOf("Intro\n\n- [ ] milk")
        val item = checkNotNull(layout.layoutOf(1))

        assertThat(targetAt(layout, bodyRect.left + (item.markerLeftPt + item.textLeftPt) / 2f, line = 1)).isEqualTo(TapTarget.Checkbox(1))
        assertThat(targetAt(layout, bodyRect.left + item.textLeftPt + 2f, line = 1)).isNull()
    }

    @Test
    fun tapTargetAt_onLinks_isTheirTargetAndPlainTextIsNull() {
        val layout = layoutOf("[web](https://example.org) [note](folio://doc/abc#page=p2) [[Wiki]] tail")
        val text = checkNotNull(layout.layoutOf(0))
        val targets = (0..200).mapNotNull { targetAt(layout, bodyRect.left + text.textLeftPt + it * 2f) }.distinct()

        assertThat(targets)
            .containsExactly(TapTarget.Url("https://example.org"), TapTarget.Note("abc", "note"), TapTarget.Note(null, "Wiki"))
            .inOrder()
        assertThat(targetAt(layout, bodyRect.right - 1f)).isNull()
    }

    @Test
    fun sourceOffsetAt_tapsMovingRight_neverMoveTheCaretLeft() {
        val layout = layoutOf("Hello **brave** new world")
        val offsets = (0..60).map { offsetAt(layout, bodyRect.left + it * (bodyRect.widthPt - 1f) / 60f) }

        assertThat(offsets).isInOrder()
        assertThat(offsets.first()).isEqualTo(0)
        assertThat(offsets.last()).isEqualTo(25)
        assertThat(offsets.toSet().size).isGreaterThan(5)
    }

    @Test
    fun sourceOffsetAt_heading_neverLandsInTheHashes() {
        val layout = layoutOf("## Title")

        assertThat(offsetAt(layout, bodyRect.left + 1f)).isEqualTo(3)
        assertThat(offsetAt(layout, bodyRect.right - 1f)).isEqualTo(8)
    }

    @Test
    fun sourceOffsetAt_laterLineOfAWrappedBlock_isPastTheFirstLine() {
        val layout = layoutOf("word ".repeat(400).trim())

        val first = offsetAt(layout, bodyRect.left + 1f, line = 0)
        val third = offsetAt(layout, bodyRect.left + 1f, line = 2)

        assertThat(first).isEqualTo(0)
        assertThat(third).isGreaterThan(20)
    }

    @Test
    fun sourceOffsetAt_belowTheLastBlock_isTheEndOfItsSource() {
        val layout = layoutOf("Alpha\n\nBeta")

        assertThat(offsetAt(layout, bodyRect.left + 1f, yPt = midLine(10))).isEqualTo(4)
    }

    @Test
    fun sourceOffsetAt_emptyFlow_isZero() {
        assertThat(offsetAt(layoutOf(""), bodyRect.left + 1f)).isEqualTo(0)
    }

    @Test
    fun sourceOffsetAt_unknownBlock_isZero() {
        val layout = layoutOf("Alpha")

        assertThat(layout.sourceOffsetAt(BlockHit(0, 7, 0, pastEnd = false), bodyRect.left)).isEqualTo(0)
    }

    @Test
    fun placementOf_secondBlock_isOneUnitBelowTheFrameTop() {
        val layout = layoutOf("Alpha\n\nBeta")

        val placement = checkNotNull(layout.placementOf(1))

        assertThat(placement.pageId).isEqualTo(page)
        assertThat(placement.topPt).isEqualTo(bodyRect.top + unitPt)
        assertThat(placement.leftPt).isEqualTo(bodyRect.left)
        assertThat(placement.widthPt).isEqualTo(bodyRect.widthPt)
    }

    @Test
    fun placementOf_unknownBlock_isNull() {
        assertThat(layoutOf("Alpha").placementOf(5)).isNull()
    }

    @Test
    fun typeface_textBlock_resolvesAPlatformTypeface() {
        val layout = layoutOf("# Title")
        val block = checkNotNull(layout.layoutOf(0))

        assertThat(block.face?.bold).isTrue()
        assertThat(block.typeface(resolver)).isNotNull()
    }

    @Test
    fun typeface_blockWithoutText_isNull() {
        val block = checkNotNull(layoutOf("---").layoutOf(0))

        assertThat(block.typeface(resolver)).isNull()
    }
}
