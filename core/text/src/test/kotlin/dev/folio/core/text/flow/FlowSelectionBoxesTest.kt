package dev.folio.core.text.flow

import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FlowSelectionBoxesTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template)!!
    private val flowId = FlowId("body")
    private val page = PageId("p1")

    private fun layoutOf(
        markdown: String,
        pages: Int = 1,
    ): FlowLayout {
        val frames = List(pages) { FlowFrame(ObjectId("frame$it"), flowId, bodyRect, it, FrameRole.BODY, autoGrow = false) }
        val doc =
            ModelFixtures.document(
                List(pages) { ModelFixtures.page("p${it + 1}", listOf(frames[it])) },
                flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
            )
        return checkNotNull(PageTexts.layouts(doc, layouter)[flowId])
    }

    private fun FlowLayout.selection(
        from: Int,
        fromOffset: Int,
        to: Int,
        toOffset: Int,
    ) = TextSelection(Caret(markdown.blocks[from].id, fromOffset), Caret(markdown.blocks[to].id, toOffset))

    @Test
    fun selectionBoxes_overThreeBlocks_oneBoxPerLineFromTheAnchorToTheFocus() {
        val layout = layoutOf("alpha\n\nbeta\n\ngamma")

        val boxes = layout.selectionBoxes(layout.selection(0, 2, 2, 3))

        assertThat(boxes).hasSize(3)
        assertThat(boxes.map { it.pageId }.distinct()).containsExactly(page)
        val (first, middle, last) = boxes
        assertThat(first.rect.left).isGreaterThan(bodyRect.left)
        assertThat(middle.rect.left).isEqualTo(bodyRect.left)
        assertThat(middle.rect.right).isGreaterThan(middle.rect.left)
        assertThat(last.rect.left).isEqualTo(bodyRect.left)
        assertThat(last.rect.right).isLessThan(middle.rect.right + 20f)
        assertThat(first.rect.bottom).isEqualTo(middle.rect.top)
        assertThat(middle.rect.bottom).isEqualTo(last.rect.top)
    }

    @Test
    fun selectionBoxes_skipBlockAndBackwardSelection_leavesThatBlockOut() {
        val layout = layoutOf("alpha\n\nbeta\n\ngamma")

        val boxes = layout.selectionBoxes(layout.selection(2, 3, 0, 2), skipBlock = 2)

        assertThat(boxes).hasSize(2)
        assertThat(boxes.last().rect.left).isEqualTo(bodyRect.left)
    }

    @Test
    fun selectionBoxes_collapsed_isEmpty() {
        val layout = layoutOf("alpha\n\nbeta")

        assertThat(layout.selectionBoxes(layout.selection(1, 2, 1, 2))).isEmpty()
    }

    @Test
    fun selectionBoxes_acrossAPageBoundary_givesBoxesOnBothPages() {
        val lines = TextZones.capacityUnits(bodyRect.heightPt, ModelFixtures.LINED_BACKGROUND.template.spacingPt)
        val layout = layoutOf((1..lines + 3).joinToString("\n\n") { "Line $it" }, pages = 2)
        val last = layout.markdown.blocks.lastIndex

        val boxes = layout.selectionBoxes(layout.selection(0, 0, last, 4))

        assertThat(boxes.map { it.pageId }.distinct()).containsExactly(PageId("p1"), PageId("p2")).inOrder()
    }

    @Test
    fun selectionBoxes_overTrailingEmptyBlocks_showsANarrowMarkAtTheLeftNotTheWholeWidth() {
        val layout = layoutOf("alpha\n\n\n\n\n\n\n")
        val last = layout.markdown.blocks.lastIndex
        assertThat(last).isGreaterThan(0)
        val unitPt = ModelFixtures.LINED_BACKGROUND.template.spacingPt

        val boxes = layout.selectionBoxes(layout.selection(0, 0, last, 0))
        val end = checkNotNull(layout.caretSpot(Caret(layout.markdown.blocks[last].id, 0)))

        boxes.drop(1).forEach { assertThat(it.rect.widthPt).isAtMost(unitPt) }
        assertThat(end.xPt).isEqualTo(bodyRect.left)
    }

    @Test
    fun caretSpot_inTheSecondBlock_isOnItsLineAtTheCharacter() {
        val layout = layoutOf("alpha\n\nbeta")
        val beta = layout.markdown.blocks[1]

        val start = checkNotNull(layout.caretSpot(Caret(beta.id, 0)))
        val inside = checkNotNull(layout.caretSpot(Caret(beta.id, 2)))

        val box = layout.selectionBoxes(layout.selection(1, 0, 1, 4)).single()
        assertThat(start.pageId).isEqualTo(page)
        assertThat(start.xPt).isEqualTo(bodyRect.left)
        assertThat(inside.xPt).isGreaterThan(start.xPt)
        assertThat(start.topPt).isEqualTo(box.rect.top)
        assertThat(start.bottomPt).isEqualTo(box.rect.bottom)
    }
}
