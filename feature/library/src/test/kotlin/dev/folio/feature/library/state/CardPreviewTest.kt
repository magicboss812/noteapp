package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CardPreviewTest {
    private fun doc(
        hasCover: Boolean = false,
        text: String = "",
    ) = LibraryDocItem("a.folio", "A", "", 1, textPreview = text, hasCover = hasCover)

    @Test
    fun cardPreview_coverStored_winsOverText() {
        assertThat(cardPreview(doc(hasCover = true, text = "Some text"))).isEqualTo(CardPreview.Cover)
    }

    @Test
    fun cardPreview_noCoverButText_isExcerpt() {
        assertThat(cardPreview(doc(text = "# Week 1\n- read ch. 2"))).isEqualTo(CardPreview.Excerpt("Week 1\n• read ch. 2"))
    }

    @Test
    fun cardPreview_noCoverNoText_isBlank() {
        assertThat(cardPreview(doc())).isEqualTo(CardPreview.Blank)
        assertThat(cardPreview(doc(text = "  \n\n  "))).isEqualTo(CardPreview.Blank)
    }

    @Test
    fun excerptOf_longText_isCappedByLinesAndCharacters() {
        val manyLines = (1..20).joinToString("\n") { "line $it" }
        assertThat(excerptOf(manyLines)!!.lines()).hasSize(8)
        assertThat(excerptOf("x".repeat(500))!!).hasLength(180)
    }

    @Test
    fun excerptOf_headingMarkOnly_isDropped() {
        assertThat(excerptOf("###   ")).isNull()
    }
}
