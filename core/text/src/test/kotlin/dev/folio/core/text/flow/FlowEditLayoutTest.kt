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
import dev.folio.core.model.TextAlign
import dev.folio.core.model.TextZones
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.edit.Caret
import dev.folio.core.text.edit.TextSelection
import dev.folio.core.text.fonts.FontMetricsCache
import dev.folio.core.text.layout.BlockLayouter
import org.junit.Test
import org.junit.runner.RunWith

/** The edited block laid out as its source and paginated with its flow (P07-T10, A-058). */
@RunWith(AndroidJUnit4::class)
class FlowEditLayoutTest {
    private val resolver = createFontFamilyResolver(ApplicationProvider.getApplicationContext())
    private val layouter = FlowLayouter(BlockLayouter(resolver, FontMetricsCache(resolver)))
    private val bodyRect = TextZones.bodyRect(ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND.template)!!
    private val unitPt = ModelFixtures.LINED_BACKGROUND.template.spacingPt
    private val flowId = FlowId("body")

    private fun docOf(
        markdown: String,
        pages: Int = 1,
    ): Document {
        val frames = List(pages) { FlowFrame(ObjectId("frame$it"), flowId, bodyRect, it, FrameRole.BODY, autoGrow = false) }
        return ModelFixtures.document(
            List(pages) { ModelFixtures.page("p${it + 1}", listOf(frames[it])) },
            flows = listOf(ModelFixtures.flow(flowId.value, markdown)),
        )
    }

    private fun Document.layout(editBlock: Int? = null): FlowLayout {
        val plain = checkNotNull(PageTexts.flowLayout(this, flowId, layouter))
        if (editBlock == null) return plain
        val id = plain.markdown.blocks[editBlock].id
        return checkNotNull(PageTexts.flowLayout(this, flowId, layouter, editBlockId = id))
    }

    private val longParagraph = (1..120).joinToString(" ") { "word$it" }

    @Test
    fun editedParagraph_longerThanTheRestOfThePage_splitsOverTwoFrames() {
        val lines = TextZones.capacityUnits(bodyRect.heightPt, unitPt)
        val doc = docOf((1 until lines - 2).joinToString("\n\n") { "Line $it" } + "\n\n" + longParagraph, pages = 2)
        val last =
            doc
                .layout()
                .markdown.blocks.lastIndex

        val layout = doc.layout(editBlock = last)

        assertThat(checkNotNull(layout.layoutOf(last)).editsSource).isTrue()
        assertThat(checkNotNull(layout.layoutOf(last)).lineCount).isGreaterThan(3)
        assertThat(layout.slices(0).last().blockIndex).isEqualTo(last)
        assertThat(layout.slices(1).first().blockIndex).isEqualTo(last)
        assertThat(layout.slices(0).last().toUnit).isEqualTo(layout.slices(1).first().fromUnit)
        assertThat(layout.runs(1, FlowLayout.NO_BLOCK, FlowLayout.NO_CELL)).isNotEmpty()
    }

    @Test
    fun retype_inAnAutoWidthBox_laysTheLineOutAtTheWidestBordersWithoutWrapping() {
        val template = ModelFixtures.LINED_BACKGROUND.template
        val rect = TextZones.newBoxAt(ModelFixtures.A4, template, 200f, bodyRect.top + 3.5f * unitPt, 0f, TextAlign.START)
        val frame = FlowFrame(ObjectId("b"), flowId, rect, 0, FrameRole.BOX, autoGrow = true)
        val flow = ModelFixtures.flow(flowId.value, "H").copy(autoContinue = true, box = FlowBox.created(rect.left))
        val doc = ModelFixtures.document(listOf(ModelFixtures.page("p1", listOf(frame))), flows = listOf(flow))

        val typed = checkNotNull(layouter.retype(doc.layout(editBlock = 0), 0, "Hi there, friend"))

        assertThat(typed.layout.lineCount).isEqualTo(1)
        val run = typed.pieces.single().run
        assertThat(run.leftPt).isEqualTo(rect.left)
        assertThat(run.widthPt).isGreaterThan(rect.widthPt)
    }

    @Test
    fun retype_atTheBottomOfAPage_continuesOnTheNextPageLikeTheReflow() {
        val lines = TextZones.capacityUnits(bodyRect.heightPt, unitPt)
        val head = (1 until lines - 1).joinToString("\n\n") { "Line $it" } + "\n\n"
        val doc = docOf(head + "short", pages = 2)
        val last =
            doc
                .layout()
                .markdown.blocks.lastIndex
        val before = doc.layout(editBlock = last)

        val typed = checkNotNull(layouter.retype(before, last, longParagraph))
        val reflowed = checkNotNull(docOf(head + longParagraph, pages = 2).layout(editBlock = last).editedBlock(last))

        assertThat(typed.pieces.map { it.pageId }).containsExactly(PageId("p1"), PageId("p2")).inOrder()
        assertThat(typed.pieces).isEqualTo(reflowed.pieces)
        val end = checkNotNull(typed.caretSpot(longParagraph.length))
        assertThat(end).isEqualTo(reflowed.caretSpot(longParagraph.length))
        assertThat(end.pageId).isEqualTo(PageId("p2"))
        assertThat(end.topPt).isAtLeast(bodyRect.top)
    }

    @Test
    fun editedParagraph_withoutMarkers_breaksLinesLikeTheShownLayout() {
        val doc = docOf(longParagraph)

        val shown = checkNotNull(doc.layout().layoutOf(0))
        val edited = checkNotNull(doc.layout(editBlock = 0).layoutOf(0))

        assertThat(edited.editsSource).isTrue()
        assertThat(edited.plainText).isEqualTo(shown.plainText)
        assertThat(edited.lineCount).isEqualTo(shown.lineCount)
        for (offset in longParagraph.indices) assertThat(edited.lineForOffset(offset)).isEqualTo(shown.lineForOffset(offset))
    }

    @Test
    fun editedEmptyBlock_caretHasTheLineHeightOfBodyText() {
        val doc = docOf("alpha\n\n\n\n\n\nbeta")
        val empty =
            doc
                .layout()
                .markdown.blocks
                .indexOfFirst { it.source.isBlank() }
        assertThat(empty).isGreaterThan(0)

        val layout = doc.layout(editBlock = empty)
        val edited = checkNotNull(layout.layoutOf(empty))
        val body = checkNotNull(layout.layoutOf(0))
        val caret = checkNotNull(layout.caretSpot(Caret(layout.markdown.blocks[empty].id, 0)))

        assertThat(edited.fontSizePt).isEqualTo(body.fontSizePt)
        assertThat(edited.face?.family).isEqualTo(body.face?.family)
        assertThat(caret.bottomPt - caret.topPt).isEqualTo(body.boxUnits * unitPt)
        assertThat(caret.xPt).isEqualTo(bodyRect.left)
    }

    @Test
    fun editedBlockWithMarkersAndMath_selectionBoxesMeetTheCaretSpotsAtTheSelectedCharacters() {
        val source = "a ==x== and \$a_2\$ then word here"
        val layout = docOf(source).layout(editBlock = 0)
        val block = layout.markdown.blocks[0]
        val edited = checkNotNull(layout.layoutOf(0))
        val start = source.indexOf("word")
        val end = start + "word".length

        val box = layout.selectionBoxes(TextSelection(Caret(block.id, start), Caret(block.id, end))).single()
        val anchor = checkNotNull(layout.caretSpot(Caret(block.id, start)))
        val focus = checkNotNull(layout.caretSpot(Caret(block.id, end)))

        assertThat(edited.plainText).isEqualTo(source)
        assertThat(box.rect.left).isEqualTo(anchor.xPt)
        assertThat(box.rect.right).isEqualTo(focus.xPt)
        assertThat(anchor.xPt).isEqualTo(bodyRect.left + edited.textLeftPt + checkNotNull(edited.text).xPt(start))
        assertThat(box.rect.top).isEqualTo(anchor.topPt)
        assertThat(box.pageId).isEqualTo(PageId("p1"))
    }

    @Test
    fun tapOnEditedBlock_givesTheSourceOffsetUnderTheTap() {
        val source = "**bold** and ==mark== end"
        val layout = docOf(source).layout(editBlock = 0)
        val edited = checkNotNull(layout.layoutOf(0))
        val target = source.indexOf("mark") + 2
        val xPt = bodyRect.left + edited.textLeftPt + checkNotNull(edited.text).xPt(target) + 0.1f
        val yPt = bodyRect.top + unitPt / 2

        val hit = checkNotNull(layout.hit(PageId("p1"), xPt, yPt))

        assertThat(layout.sourceOffsetAt(hit, xPt)).isEqualTo(target)
    }
}
