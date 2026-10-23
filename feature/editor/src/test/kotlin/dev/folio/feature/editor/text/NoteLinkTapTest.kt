package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.text.flow.TapTarget
import dev.folio.core.text.markdown.linkedDocIds
import dev.folio.feature.editor.state.EditorSession
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith

/** Taps on checkboxes and links, the `[[` query and broken note links of an editor session's text. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NoteLinkTapTest : EditorTextFixture() {
    @Test
    fun tapAt_checkbox_togglesTheTaskWithoutFocusing() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "- [ ] buy milk\n\nplain")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            val item =
                checkNotNull(
                    text.snapshot.value.layouts[body]
                        ?.layoutOf(0),
                )
            val gutterX = bodyRect.left + (item.markerLeftPt + item.textLeftPt) / 2f

            assertThat(texts.tapAt(page, gutterX, firstLineY())).isTrue()
            yield()

            assertThat(markdown(session.documentSession)).isEqualTo("- [x] buy milk\n\nplain")
            assertThat(text.focus.value).isNull()
            assertThat(texts.tapAt(page, gutterX, firstLineY())).isTrue()
            yield()
            assertThat(markdown(session.documentSession)).isEqualTo("- [ ] buy milk\n\nplain")
            assertThat(texts.tapAt(page, bodyRect.left + 4f, secondLineY())).isFalse()
        }

    @Test
    fun tapAt_links_goToLinkTaps_butNotInTheFocusedBlock() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "[web](https://example.org) text\n\n[[Optics]] text")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            val taps = async(start = CoroutineStart.UNDISPATCHED) { texts.linkTaps.take(2).toList() }

            assertThat(texts.tapAt(page, bodyRect.left + 4f, firstLineY())).isTrue()
            assertThat(texts.tapAt(page, bodyRect.left + 4f, secondLineY())).isTrue()

            assertThat(taps.await()).containsExactly(TapTarget.Url("https://example.org"), TapTarget.Note(null, "Optics")).inOrder()
            assertThat(text.focus.value).isNull()
            texts.focusAt(page, bodyRect.left + 4f, firstLineY())
            assertThat(texts.tapAt(page, bodyRect.left + 4f, firstLineY())).isFalse()
        }

    @Test
    fun noteQuery_followsTheTypedBracketsAndInsertNoteLinkReplacesThem() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "abc")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            texts.focusAt(page, bodyRect.left + 4f, firstLineY())

            texts.edit(FieldEdit.Typed("abc [[Opt", 9))
            assertThat(texts.noteQuery.first()).isEqualTo("Opt")
            texts.select(9, 9)
            assertThat(texts.noteQuery.first()).isEqualTo("Opt")
            texts.select(2, 2)
            assertThat(texts.noteQuery.first()).isNull()

            texts.edit(FieldEdit.Typed("abc [[Opti", 10))
            texts.insertNoteLink("Optics", "id-1")
            yield()

            assertThat(texts.noteQuery.first()).isNull()
            assertThat(markdown(session.documentSession)).isEqualTo("abc [Optics](folio://doc/id-1)")
            assertThat(text.focus.value?.caret).isEqualTo("abc [Optics](folio://doc/id-1)".length)
        }

    @Test
    fun missingLinks_askedWhenTheLinkedIdsChange_onlyThen() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "[A](folio://doc/a) and [B](folio://doc/b)\n\nplain")
            val asked = ArrayList<Set<String>>()
            val documentSession = (sessions.open(path) as Outcome.Success).value
            val session =
                EditorSession(documentSession, 2f, main.dispatchers, backgroundScope, textEngine = engine) { ids ->
                    asked += ids
                    ids - "a"
                }
            val text = checkNotNull(session.text)
            text.awaitReady()
            val page =
                documentSession.document.value.pages[0]
                    .id
            val texts = checkNotNull(session.textController)

            texts.focusAt(page, bodyRect.left + 4f, secondLineY())
            texts.edit(FieldEdit.Typed("plainer", 7))
            yield()
            assertThat(asked).containsExactly(setOf("a", "b"))

            texts.edit(FieldEdit.Typed("plainer [C](folio://doc/c)", 26))
            yield()
            text.snapshot.first { snapshot ->
                snapshot.layouts[body]
                    ?.markdown
                    ?.blocks
                    ?.lastOrNull()
                    ?.content
                    ?.linkedDocIds() == setOf("c")
            }
            assertThat(asked).containsExactly(setOf("a", "b"), setOf("a", "b", "c")).inOrder()
        }
}
