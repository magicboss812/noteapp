package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.markdown.BlockContent
import dev.folio.core.text.markdown.MarkdownDocument
import org.junit.Test

class NoteLinkEditTest {
    private fun toggle(markdown: String): String? {
        val doc = MarkdownDocument.parse(markdown)
        return LinkEdits.toggleTask(doc, doc.blocks[0].id)?.document?.toMarkdown()
    }

    @Test
    fun toggleTask_uncheckedItem_becomesChecked() {
        assertThat(toggle("- [ ] milk")).isEqualTo("- [x] milk")
    }

    @Test
    fun toggleTask_checkedItem_becomesUnchecked() {
        assertThat(toggle("- [X] milk")).isEqualTo("- [ ] milk")
        assertThat(toggle("  1. [x] nested")).isEqualTo("  1. [ ] nested")
    }

    @Test
    fun toggleTask_checkedItem_reparsesAsChecked() {
        val doc = MarkdownDocument.parse("* [ ] a [ ] b")
        val result = checkNotNull(LinkEdits.toggleTask(doc, doc.blocks[0].id))

        assertThat(result.document.toMarkdown()).isEqualTo("* [x] a [ ] b")
        assertThat((result.document.blocks[0].content as BlockContent.ListItem).checked).isTrue()
    }

    @Test
    fun toggleTask_notATask_isRefused() {
        assertThat(toggle("- milk")).isNull()
        assertThat(toggle("[ ] milk")).isNull()
    }

    @Test
    fun insertNoteLink_typedQuery_becomesALinkWithTheCaretAfterIt() {
        val doc = MarkdownDocument.parse("See [[Phy")
        val result = LinkEdits.insertNoteLink(doc, doc.blocks[0].id, 4, 9, "Physics", "d-1")

        assertThat(result?.render()).isEqualTo("See [Physics](folio://doc/d-1)^")
    }

    @Test
    fun insertNoteLink_closingBracketsAfterTheCaret_areReplacedToo() {
        val doc = MarkdownDocument.parse("See [[Phy]] now")
        val result = LinkEdits.insertNoteLink(doc, doc.blocks[0].id, 4, 9, "A [b]*", "d-2")

        assertThat(result?.render()).isEqualTo("See [A \\[b\\]\\*](folio://doc/d-2)^ now")
    }

    @Test
    fun noteLinkQueryStart_insideAnOpenQuery_isTheFirstBracket() {
        assertThat(LinkEdits.noteLinkQueryStart("See [[Phy", 9)).isEqualTo(4)
        assertThat(LinkEdits.noteLinkQueryStart("[[", 2)).isEqualTo(0)
    }

    @Test
    fun noteLinkQueryStart_closedEscapedOrAbsent_isNull() {
        assertThat(LinkEdits.noteLinkQueryStart("See [[Phy]] x", 13)).isNull()
        assertThat(LinkEdits.noteLinkQueryStart("See \\[[Phy", 10)).isNull()
        assertThat(LinkEdits.noteLinkQueryStart("See [Phy", 8)).isNull()
    }
}
