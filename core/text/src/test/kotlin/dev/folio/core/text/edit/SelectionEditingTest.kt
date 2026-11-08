package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.text.markdown.SpanKind
import org.junit.Test

class SelectionEditingTest {
    private fun selectAll(text: String): String =
        marked(text).let { EditResult(it.doc, SelectionEditing.selectAll(it.doc, it.selection)).render() }

    private fun inline(
        text: String,
        kind: SpanKind,
    ): String? = marked(text).let { SelectionEditing.toggleInline(it.doc, it.selection, kind) }?.render()

    @Test
    fun selectAll_caretInABlock_selectsTheBlock() {
        assertThat(selectAll("# Title\n\nab^c\n\nend")).isEqualTo("# Title\n\n«abc»\n\nend")
    }

    @Test
    fun selectAll_wholeBlockSelected_selectsTheFlow() {
        assertThat(selectAll("# Title\n\n«abc»\n\nend")).isEqualTo("«# Title\n\nabc\n\nend»")
    }

    @Test
    fun selectAll_selectionOverTwoBlocks_selectsTheFlow() {
        assertThat(selectAll("# Ti«tle\n\nab»c\n\nend")).isEqualTo("«# Title\n\nabc\n\nend»")
    }

    @Test
    fun entering_forward_landsAtTheBlockStart() {
        val doc = marked("one\n\ntwo^").doc
        assertThat(SelectionEditing.entering(doc, 1, forward = true)).isEqualTo(Caret(doc.blocks[1].id, 0))
    }

    @Test
    fun entering_backwardOverATable_landsAtTheEndOfTheBlockBefore() {
        val doc = marked("one\n\n| a | b |\n| - | - |\n| 1 | 2 |\n\ntwo^").doc
        assertThat(SelectionEditing.entering(doc, 1, forward = false)).isEqualTo(Caret(doc.blocks[0].id, 3))
    }

    @Test
    fun entering_forwardOnlyTablesLeft_isNull() {
        val doc = marked("one^\n\n| a | b |\n| - | - |\n| 1 | 2 |").doc
        assertThat(SelectionEditing.entering(doc, 1, forward = true)).isNull()
    }

    @Test
    fun toggleInline_threeBlocks_boldsEachPart() {
        assertThat(inline("# Ti«tle\n\n- item\n\nab»c", SpanKind.BOLD)).isEqualTo("# Ti**«tle**\n\n- **item**\n\n**ab»**c")
    }

    @Test
    fun toggleInline_allPartsBold_unbolds() {
        assertThat(inline("**«one**\n\n**two»**", SpanKind.BOLD)).isEqualTo("«one\n\ntwo»")
    }

    @Test
    fun toggleInline_skipsCodeBlocks() {
        assertThat(inline("«one\n\n```\ncode\n```\n\ntwo»", SpanKind.ITALIC)).isEqualTo("*«one*\n\n```\ncode\n```\n\n*two»*")
    }

    @Test
    fun toggleInline_backwardSelection_staysBackward() {
        assertThat(inline("»one\n\ntwo«", SpanKind.STRIKE)).isEqualTo("~~»one~~\n\n~~two«~~")
    }
}
