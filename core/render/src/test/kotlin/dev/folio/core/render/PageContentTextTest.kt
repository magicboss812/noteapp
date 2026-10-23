package dev.folio.core.render

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.text.flow.PageText
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageContentTextTest {
    private val pages = TextPages()

    @Test
    fun isEmptyIn_textHangingBelowItsFrame_isNotEmpty() {
        val frame = pages.box("tight", 0, topLines = 0, lines = 1)
        val content = pages.content("Alpha", listOf(frame))
        val hang =
            content.text.runs
                .single()
                .overhangPt
        assertThat(hang).isGreaterThan(1f)

        val belowFrame = RectPt(frame.rect.left, frame.rect.bottom + 0.25f * hang, frame.rect.left + 20f, frame.rect.bottom + 0.75f * hang)

        assertThat(content.isEmptyIn(belowFrame)).isFalse()
        assertThat(PageContent.of(pages.page(listOf(frame))).isEmptyIn(belowFrame)).isTrue()
        assertThat(content.objectsIn(belowFrame).toList()).containsExactly(0)
    }

    @Test
    fun isEmptyIn_pageWithoutText_staysAsFrameBounds() {
        val content = PageContent.of(pages.page())

        assertThat(content.isEmptyIn(RectPt(0f, 0f, 10f, 10f))).isTrue()
        assertThat(content.isEmptyIn(pages.bodyRect)).isFalse()
    }

    @Test
    fun changedBounds_editedBlock_listsThatBlockOnly() {
        val before = pages.content("Alpha\n\nBeta\n\nGamma")
        val after = pages.content("Alpha\n\nBeta two\n\nGamma")

        val changed = PageContent.changedBounds(before, after)

        assertThat(changed).containsExactly(before.text.runs[1].bounds, after.text.runs[1].bounds)
    }

    @Test
    fun changedBounds_sameTextAndPage_isEmpty() {
        val content = pages.content("Alpha\n\nBeta")

        assertThat(PageContent.changedBounds(content, content)).isEmpty()
    }

    @Test
    fun next_sameTextAndPage_keepsTheSnapshot_newTextMakesANewOne() {
        val content = pages.content("Alpha")
        val edited = pages.text("Alpha two")

        assertThat(content.next(content.page, content.text)).isSameInstanceAs(content)
        val next = content.next(content.page, edited)
        assertThat(next).isNotSameInstanceAs(content)
        assertThat(next.text).isSameInstanceAs(edited)
        assertThat(PageContent.of(content.page).text).isSameInstanceAs(PageText.EMPTY)
    }
}
