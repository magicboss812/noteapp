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
