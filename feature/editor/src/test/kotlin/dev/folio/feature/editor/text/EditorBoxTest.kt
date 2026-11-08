package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.Borders
import dev.folio.core.model.Document
import dev.folio.core.model.FlowBox
import dev.folio.core.model.FlowFrame
import dev.folio.core.model.FlowId
import dev.folio.core.model.FrameRole
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.TextZones
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.Batch
import dev.folio.core.model.edit.UpdateFlows
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.testing.ModelFixtures
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.roundToInt

/** Moving and resizing a free text box (P07-T13, P07-T15): placed rects, knob borders, fill width, reflow and one undo step each. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditorBoxTest : EditorTextFixture() {
    private val boxFlow = FlowId("box")
    private val boxId = ObjectId("box-frame")
    private val spec = ModelFixtures.A4
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val u = template.spacingPt

    private suspend fun boxNote(
        markdown: String,
        autoWidth: Boolean = false,
    ): String {
        val path =
            (documents.create(NewDocumentSpec("", "Box", spec, ModelFixtures.LINED_BACKGROUND, 2)) as Outcome.Success)
                .value.path
        val session = (sessions.open(path) as Outcome.Success).value
        val first =
            session.document.value.pages[0]
                .id
        session.loadPages(listOf(first))
        val rect = TextZones.newBoxRect(spec, template, 100f, 200f)
        val frame = FlowFrame(boxId, boxFlow, rect, 0, FrameRole.BOX, autoGrow = true)
        val flow =
            ModelFixtures
                .flow(
                    "box",
                    markdown,
                ).let { if (autoWidth) it.copy(box = FlowBox.created(rect.left), autoContinue = true) else it }
        session.execute(Batch(listOf(UpdateFlows(listOf(flow)), AddObjects(first, listOf(frame)))))
        sessions.close(session)
        return path
    }

    private fun Document.box(): Pair<PageId, FlowFrame>? =
        pageBodies.entries.firstNotNullOfOrNull { (id, page) ->
            page.objects
                .filterIsInstance<FlowFrame>()
                .firstOrNull { it.id == boxId }
                ?.let { id to it }
        }

    private fun onRule(yPt: Float): Boolean {
        val n = (yPt - template.marginTopPt) / u
        return abs(n - n.roundToInt()) < 1e-3f
    }

    @Test
    fun moveBoxCommand_xUnsnapped_topInWholeUnitsOnARule_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Box text"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())
            val target = TextZones.movedBox(spec, template, before.rect, 150.7f, 333.3f)

            val command = checkNotNull(text.moveBoxCommand(boxFlow, page, target))
            assertThat(session.execute(command)).isTrue()

            val (_, moved) = checkNotNull(documentSession.document.value.box())
            assertThat(moved.rect.left).isEqualTo(150.7f)
            assertThat(onRule(moved.rect.top)).isTrue()
            val units = (moved.rect.top - before.rect.top) / u
            assertThat(abs(units - units.roundToInt())).isLessThan(1e-3f)
            assertThat(moved.rect.widthPt).isWithin(1e-3f).of(before.rect.widthPt)
            assertThat(session.undo()).isTrue()
            assertThat(
                documentSession.document.value
                    .box()
                    ?.second,
            ).isEqualTo(before)
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun moveBoxCommand_keyedBetweenTheLines_storesTheOffset_oneUndoStep_aSnappedMoveClearsIt() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Box text"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())
            // Shift+Down left the box 0.4 U below its line (P07-T14, A-057).
            val keyed = TextZones.keyedBox(spec, template, before.rect, before.rect.left + 3f, before.rect.top + 0.4f * u)

            assertThat(session.execute(checkNotNull(text.moveBoxCommand(boxFlow, page, keyed)))).isTrue()

            val (_, shifted) = checkNotNull(documentSession.document.value.box())
            assertThat(shifted.rect.top).isWithin(1e-3f).of(before.rect.top + 0.4f * u)
            assertThat(shifted.rect.heightPt).isWithin(1e-3f).of(before.rect.heightPt)
            assertThat(
                documentSession.document.value.flows[boxFlow]!!
                    .box.offsetPt,
            ).isWithin(1e-3f).of(0.4f * u)
            assertThat(session.undo()).isTrue()
            assertThat(
                documentSession.document.value
                    .box()
                    ?.second,
            ).isEqualTo(before)
            assertThat(
                documentSession.document.value.flows[boxFlow]!!
                    .box.offsetPt,
            ).isEqualTo(0f)
            assertThat(documentSession.canUndo.value).isFalse()

            assertThat(session.redo()).isTrue()
            val snapped = TextZones.lineStepTop(template, shifted.rect.top, down = false)
            val back = TextZones.keyedBox(spec, template, shifted.rect, shifted.rect.left, snapped)
            assertThat(session.execute(checkNotNull(text.moveBoxCommand(boxFlow, page, back)))).isTrue()
            assertThat(onRule(checkNotNull(documentSession.document.value.box()).second.rect.top)).isTrue()
            assertThat(
                documentSession.document.value.flows[boxFlow]!!
                    .box.offsetPt,
            ).isEqualTo(0f)
        }

    @Test
    fun moveBoxCommand_autoWidthBox_anchorMovesAlong_reflowKeepsTheNewPlace() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Short", autoWidth = true), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())
            val anchor =
                checkNotNull(
                    documentSession.document.value.flows[boxFlow]
                        ?.box
                        ?.anchorXPt,
                )
            val target = TextZones.movedBox(spec, template, before.rect, before.rect.left + 37.3f, before.rect.top + 2f * u)

            assertThat(session.execute(checkNotNull(text.moveBoxCommand(boxFlow, page, target)))).isTrue()

            val (_, moved) = checkNotNull(documentSession.document.value.box())
            assertThat(moved.rect.left).isWithin(1e-3f).of(before.rect.left + 37.3f)
            assertThat(moved.rect.top).isWithin(1e-3f).of(before.rect.top + 2f * u)
            val box = checkNotNull(documentSession.document.value.flows[boxFlow]).box
            assertThat(box.anchorXPt!!).isWithin(1e-3f).of(anchor + 37.3f)
            assertThat(box.offsetPt).isEqualTo(0f)
        }

    @Test
    fun moveBoxCommand_ontoTheNextPage_leavesTheFirstPage_textShownThere_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Moved text"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val doc = documentSession.document.value
            val (first, before) = checkNotNull(doc.box())
            val second = doc.pages[1].id
            val target = TextZones.movedBox(spec, template, before.rect, 80f, 120f)

            assertThat(session.execute(checkNotNull(text.moveBoxCommand(boxFlow, second, target)))).isTrue()

            val (on, moved) = checkNotNull(documentSession.document.value.box())
            assertThat(on).isEqualTo(second)
            assertThat(moved.rect.top).isEqualTo(target.top)
            assertThat(onRule(moved.rect.top)).isTrue()
            assertThat(
                documentSession.document.value
                    .page(first)!!
                    .objects
                    .filterIsInstance<FlowFrame>(),
            ).isEmpty()
            val shown = text.snapshot.first { second in it.pages.keys }
            assertThat(shown.pages.keys).doesNotContain(first)
            assertThat(session.undo()).isTrue()
            assertThat(documentSession.document.value.box()).isEqualTo(first to before)
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun widthCommand_narrower_unsnapped_reflowsTallerInWholeUnits_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("word ".repeat(40).trim()), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (_, before) = checkNotNull(documentSession.document.value.box())
            val right = before.rect.left + 30.37f * MM

            assertThat(session.execute(checkNotNull(text.widthCommand(boxFlow, Borders(before.rect.left, right))))).isTrue()

            val (_, resized) = checkNotNull(documentSession.document.value.box())
            assertThat(resized.rect.left).isEqualTo(before.rect.left)
            assertThat(resized.rect.right).isEqualTo(right)
            assertThat(resized.rect.heightPt).isGreaterThan(before.rect.heightPt)
            val units = resized.rect.heightPt / u
            assertThat(abs(units - units.roundToInt())).isLessThan(1e-3f)
            assertThat(onRule(resized.rect.top)).isTrue()
            assertThat(onRule(resized.rect.bottom)).isTrue()
            assertThat(session.undo()).isTrue()
            assertThat(
                documentSession.document.value
                    .box()
                    ?.second,
            ).isEqualTo(before)
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun widthCommand_autoWidthBox_endsAutoWidth_theReflowKeepsTheKnobBorders() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Short", autoWidth = true), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (_, before) = checkNotNull(documentSession.document.value.box())
            val wide = Borders(before.rect.left, before.rect.left + 90f * MM)

            assertThat(session.execute(checkNotNull(text.widthCommand(boxFlow, wide)))).isTrue()

            val (_, resized) = checkNotNull(documentSession.document.value.box())
            assertThat(resized.rect.right).isEqualTo(wide.rightPt)
            assertThat(checkNotNull(documentSession.document.value.flows[boxFlow]).box.autoWidth).isFalse()
        }

    @Test
    fun fillCommand_customFillAdjustCustomFill_followsTheSpecSequence_oneUndoStepEach() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Fill me"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (_, before) = checkNotNull(documentSession.document.value.box())
            val zone = TextZones.boxZone(spec, template)

            fun borders(): Pair<Float, Float> = checkNotNull(documentSession.document.value.box()).second.rect.let { it.left to it.right }

            fun fill(): Boolean = checkNotNull(documentSession.document.value.flows[boxFlow]).box.fill

            assertThat(session.execute(checkNotNull(text.fillCommand(boxFlow)))).isTrue()
            assertThat(fill()).isTrue()
            assertThat(borders()).isEqualTo(zone.left to zone.right)

            val adjusted = Borders(zone.left + 20f * MM, zone.right)
            assertThat(session.execute(checkNotNull(text.widthCommand(boxFlow, adjusted)))).isTrue()
            assertThat(fill()).isTrue()

            assertThat(session.execute(checkNotNull(text.fillCommand(boxFlow)))).isTrue()
            assertThat(fill()).isFalse()
            assertThat(borders()).isEqualTo(before.rect.left to before.rect.right)

            assertThat(session.execute(checkNotNull(text.fillCommand(boxFlow)))).isTrue()
            assertThat(borders()).isEqualTo(adjusted.leftPt to adjusted.rightPt)

            assertThat(session.undo()).isTrue()
            assertThat(fill()).isFalse()
            assertThat(borders()).isEqualTo(before.rect.left to before.rect.right)
        }

    @Test
    fun widthPreview_laysTheBoxOutAtTheKnobBorders_documentUnchanged() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("word ".repeat(40).trim()), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val doc = documentSession.document.value
            val (_, before) = checkNotNull(doc.box())
            val narrow = Borders(before.rect.left, before.rect.left + 30f * MM)

            val preview = checkNotNull(text.widthPreview(boxFlow, narrow))

            val runs = checkNotNull(preview.runs[boxId])
            assertThat(runs).isNotEmpty()
            assertThat(runs.all { it.leftPt == narrow.leftPt && it.widthPt == narrow.widthPt }).isTrue()
            assertThat(checkNotNull(preview.outlines[boxId]).heightPt).isGreaterThan(before.rect.heightPt)
            assertThat(documentSession.document.value).isSameInstanceAs(doc)
            assertThat(documentSession.canUndo.value).isFalse()
        }

    @Test
    fun widthAndFill_sameBordersOrNotABox_isNull() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Still"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())

            assertThat(text.widthCommand(boxFlow, Borders(before.rect.left, before.rect.right))).isNull()
            assertThat(text.widthCommand(FlowId("nope"), Borders(0f, 100f))).isNull()
            assertThat(text.fillCommand(FlowId("nope"))).isNull()
            assertThat(text.moveBoxCommand(boxFlow, page, before.rect)).isNull()
            assertThat(text.moveBoxCommand(FlowId("nope"), page, before.rect)).isNull()
        }

    @Test
    fun deleteBoxCommand_removesFrameAndFlow_oneUndoStepRestoresBoth() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Gone soon"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val before = documentSession.document.value
            val (page, frame) = checkNotNull(before.box())

            assertThat(session.execute(checkNotNull(text.deleteBoxCommand(boxFlow)))).isTrue()
            assertThat(documentSession.document.value.box()).isNull()
            assertThat(documentSession.document.value.flows).doesNotContainKey(boxFlow)

            assertThat(session.undo()).isTrue()
            assertThat(documentSession.document.value.box()).isEqualTo(page to frame)
            assertThat(documentSession.document.value.flows[boxFlow]).isEqualTo(before.flows[boxFlow])
            assertThat(documentSession.canUndo.value).isFalse()
        }

    private companion object {
        const val MM = 72f / 25.4f
    }
}
