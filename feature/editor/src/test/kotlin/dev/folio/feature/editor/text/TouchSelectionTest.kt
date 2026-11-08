package dev.folio.feature.editor.text

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.edit.Caret
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.junit.Test
import org.junit.runner.RunWith

/** Finger word selection (07-text-engine.md#selection-as-built): long-press, its drag by words, through the controller. */
@RunWith(AndroidJUnit4::class)
class TouchSelectionTest : EditorTextFixture() {
    @Test
    fun selectWordAt_onTheEditedBlock_selectsTheWord() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha beta\n\ngamma")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())

            assertThat(texts.selectWordAt(page, bodyRect.left + 4f, firstLineY())).isTrue()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.anchor).isEqualTo(Caret(focus.blockId, 0))
            assertThat(focus.caret).isEqualTo(5)
            assertThat(texts.copySelection()).isEqualTo("alpha")
        }

    @Test
    fun selectWordAt_onABlockNotEdited_selectsNothing() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\ngamma")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())

            assertThat(texts.selectWordAt(page, bodyRect.left + 4f, secondLineY())).isFalse()
            assertThat(checkNotNull(text.focus.value).anchor).isNull()
        }

    @Test
    fun longPressDrag_overTheBlockBoundary_extendsByWords() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha\n\nbeta gamma")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, firstLineY())
            texts.selectWordAt(page, bodyRect.left + 4f, firstLineY())

            texts.extendWordsTo(page, bodyRect.left + 4f, secondLineY())
            yield()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.blockIndex).isEqualTo(1)
            assertThat(focus.caret).isEqualTo(4)
            assertThat(texts.copySelection()).isEqualTo("alpha\n\nbeta")
        }

    @Test
    fun longPressDrag_backOverTheBlockBoundary_keepsTheWordSelected() =
        runTest(main.testDispatcher) {
            val path = note(1, listOf(0), "alpha one\n\nbeta")
            val (session, text, page) = focusedFlow(path, this)
            val texts = checkNotNull(session.textController)
            text.focusAt(page, bodyRect.left + 4f, secondLineY())
            texts.selectWordAt(page, bodyRect.left + 4f, secondLineY())

            texts.extendWordsTo(page, bodyRect.left + 4f, firstLineY())
            yield()

            val focus = checkNotNull(text.focus.value)
            assertThat(focus.blockIndex).isEqualTo(0)
            assertThat(focus.caret).isEqualTo(0)
            assertThat(texts.copySelection()).isEqualTo("alpha one\n\nbeta")
        }
}
