package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.Document
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

/** Moving and resizing a free text box (P06-T17): snapped rects, reflow and one undo step each. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class EditorBoxTest : EditorTextFixture() {
    private val boxFlow = FlowId("box")
    private val boxId = ObjectId("box-frame")
    private val spec = ModelFixtures.A4
    private val template = ModelFixtures.LINED_BACKGROUND.template
    private val u = template.spacingPt

    private suspend fun boxNote(markdown: String): String {
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
        session.execute(Batch(listOf(UpdateFlows(listOf(ModelFixtures.flow("box", markdown))), AddObjects(first, listOf(frame)))))
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
    fun boxCommand_move_snapsToARuleAndAMillimeter_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Box text"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())
            val target = TextZones.movedBox(spec, template, before.rect, 150.7f, 333.3f)

            val command = checkNotNull(text.boxCommand(boxId, page, page, target))
            assertThat(session.execute(command)).isTrue()

            val (_, moved) = checkNotNull(documentSession.document.value.box())
            assertThat(moved.rect.left).isEqualTo(target.left)
            assertThat(onRule(moved.rect.top)).isTrue()
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
    fun boxCommand_moveOntoTheNextPage_leavesTheFirstPage_textShownThere_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Moved text"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val doc = documentSession.document.value
            val (first, before) = checkNotNull(doc.box())
            val second = doc.pages[1].id
            val target = TextZones.movedBox(spec, template, before.rect, 80f, 120f)

            assertThat(session.execute(checkNotNull(text.boxCommand(boxId, first, second, target)))).isTrue()

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
    fun boxCommand_resizeNarrower_reflowsTallerInWholeUnits_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("word ".repeat(40).trim()), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())
            val target = TextZones.resizedBox(spec, template, before.rect, before.rect.left, before.rect.left + 30f * MM)

            assertThat(session.execute(checkNotNull(text.boxCommand(boxId, page, page, target)))).isTrue()

            val (_, resized) = checkNotNull(documentSession.document.value.box())
            assertThat(resized.rect.right).isEqualTo(target.right)
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
    fun boxCommand_sameRectOrNotABox_isNull() =
        runTest(main.testDispatcher) {
            val (session, documentSession) = open(boxNote("Still"), this)
            val text = checkNotNull(session.text)
            text.awaitReady()
            val (page, before) = checkNotNull(documentSession.document.value.box())

            assertThat(text.boxCommand(boxId, page, page, before.rect)).isNull()
            assertThat(text.boxCommand(ObjectId("nope"), page, page, RectPt(0f, 0f, 10f, 10f))).isNull()
        }

    private companion object {
        const val MM = 72f / 25.4f
    }
}
