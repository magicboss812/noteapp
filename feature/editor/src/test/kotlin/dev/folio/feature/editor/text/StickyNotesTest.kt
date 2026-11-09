package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.model.PageId
import dev.folio.core.model.StickyNote
import dev.folio.core.model.TextZones
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.state.EditorSession
import dev.folio.feature.editor.state.StickyOptions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith

/** The Sticky tool (A-068, 07-text-engine.md#flows-and-frames): a tap starts a sticky note with its text focused. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class StickyNotesTest : EditorTextFixture() {
    private val template = ModelFixtures.LINED_BACKGROUND.template

    private suspend fun ready(test: TestScope): Triple<EditorSession, EditorText, PageId> {
        val created = documents.create(NewDocumentSpec("", "Blank", ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, 1))
        val (session, documentSession) = open((created as Outcome.Success).value.path, test)
        val text = checkNotNull(session.text)
        text.awaitReady()
        val page =
            documentSession.document.value.pages
                .single()
                .id
        documentSession.loadPages(listOf(page))
        return Triple(session, text, page)
    }

    private fun EditorSession.stickies(page: PageId): List<StickyNote> =
        documentSession.document.value
            .page(page)!!
            .objects
            .filterIsInstance<StickyNote>()

    @Test
    fun placeSticky_emptySpot_addsAStickyInTheOptionsLookWithItsTextFocused_oneUndoStep() =
        runTest(main.testDispatcher) {
            val (session, text, page) = ready(this)
            session.updateOptions { it.copy(sticky = StickyOptions(color = 2, tilt = true)) }

            checkNotNull(session.textController).placeSticky(page, 100f, 200f)
            val focus = text.focus.first { it != null }!!

            val sticky = session.stickies(page).single()
            assertThat(sticky.flow).isEqualTo(focus.flowId)
            assertThat(sticky.rect).isEqualTo(TextZones.newStickyAt(ModelFixtures.A4, template, 100f, 200f))
            assertThat(sticky.argb).isEqualTo(StickyNote.COLORS[2])
            assertThat(sticky.rotationDeg).isEqualTo(StickyNote.TILT_DEG)
            val flow = session.documentSession.document.value.flows[focus.flowId]!!
            assertThat(flow.autoContinue).isFalse()
            assertThat(flow.style.textArgb).isEqualTo(StickyNote.INK_ARGB)
            // The field sits on the sticky's first line, inside its padding.
            val content = TextZones.stickyContentRect(sticky, ModelFixtures.LINED_BACKGROUND.template.spacingPt)
            val field = text.field.first { it != null }!!
            assertThat(field.placement.topPt).isWithin(0.5f).of(content.top)
            // The straight sticky is what the canvas shows while its text is edited.
            val canvas = text.snapshot.first { it.canvas[page]?.isStraight(sticky.id) == true }
            assertThat(canvas.pages[page]?.isStraight(sticky.id)).isFalse()

            assertThat(session.undo()).isTrue()
            assertThat(session.stickies(page)).isEmpty()
            assertThat(session.documentSession.document.value.flows).isEmpty()
        }

    @Test
    fun placeSticky_typedText_staysInTheSticky_andAnEmptyStickyIsKeptOnBlur() =
        runTest(main.testDispatcher) {
            val (session, text, page) = ready(this)
            val controller = checkNotNull(session.textController)
            controller.placeSticky(page, 100f, 200f)
            val focus = text.focus.first { it != null }!!

            controller.edit(FieldEdit.Typed("Call Ana", "Call Ana".length))
            yield()

            assertThat(
                session.documentSession.document.value.flows[focus.flowId]!!
                    .markdown,
            ).isEqualTo("Call Ana")
            controller.placeSticky(page, 400f, 600f)
            text.focus.first { it != null && it.flowId != focus.flowId }
            controller.deselect()
            yield()
            assertThat(session.stickies(page)).hasSize(2)
        }

    @Test
    fun tapBox_onASticky_editsItAtOnce() =
        runTest(main.testDispatcher) {
            val (session, text, page) = ready(this)
            val controller = checkNotNull(session.textController)
            controller.placeSticky(page, 100f, 200f)
            val flowId = text.focus.first { it != null }!!.flowId
            controller.edit(FieldEdit.Typed("Note", 4))
            yield()
            controller.deselect()
            text.modes.first { it == BoxMode.None }
            val sticky = session.stickies(page).single()

            // Below the text, still on the sticky: no Preview step.
            controller.tapBox(page, sticky.rect.left + 10f, sticky.rect.bottom - 10f, 0f, textTool = false)

            assertThat(text.modes.first { it != BoxMode.None }).isEqualTo(BoxMode.Edit(flowId))
        }
}
