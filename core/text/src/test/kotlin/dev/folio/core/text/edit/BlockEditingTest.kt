package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlockEditingTest {
    private fun enter(text: String): String = marked(text).let { BlockEditing.enter(it.doc, it.selection) }.render()

    private fun softBreak(text: String): String = marked(text).let { BlockEditing.softBreak(it.doc, it.selection) }.render()

    private fun backspace(text: String): String? = marked(text).let { BlockEditing.backspace(it.doc, it.selection) }?.render()

    private fun deleteForward(text: String): String? = marked(text).let { BlockEditing.deleteForward(it.doc, it.selection) }?.render()

    private fun indent(
        text: String,
        outdent: Boolean = false,
    ): String? = marked(text).let { BlockEditing.indent(it.doc, it.caret, outdent) }?.render()

    // Enter: paragraphs and headings

    @Test
    fun enter_paragraphMiddle_splitsIntoTwoBlocks() {
        assertThat(enter("ab^cd")).isEqualTo("ab\n\n^cd")
    }

    @Test
    fun enter_paragraphEnd_addsEmptyParagraphBelow() {
        assertThat(enter("ab^")).isEqualTo("ab\n\n^\n")
    }

    @Test
    fun enter_paragraphStart_addsEmptyParagraphAbove() {
        assertThat(enter("^ab")).isEqualTo("\n^ab")
    }

    @Test
    fun enter_emptyFlow_addsEmptyParagraph() {
        assertThat(enter("^")).isEqualTo("\n^\n")
    }

    @Test
    fun enter_midFlow_keepsTheBlocksAround() {
        assertThat(enter("a\n\nb^c\n\nd")).isEqualTo("a\n\nb\n\n^c\n\nd")
    }

    @Test
    fun enter_headingEnd_startsAnEmptyParagraph() {
        assertThat(enter("# Title^")).isEqualTo("# Title\n\n^\n")
    }

    @Test
    fun enter_headingMiddle_splitsTheHeadingText() {
        assertThat(enter("# Ti^tle")).isEqualTo("# Ti\n\n^tle")
    }

    @Test
    fun enter_insideHeadingMarker_addsParagraphAbove() {
        assertThat(enter("#^ Title")).isEqualTo("\n#^ Title")
    }

    @Test
    fun enter_withSelection_deletesItFirst() {
        assertThat(enter("a«bc»d")).isEqualTo("a\n\n^d")
    }

    // Enter: lists

    @Test
    fun enter_bulletItemEnd_continuesTheList() {
        assertThat(enter("- a^")).isEqualTo("- a\n- ^")
    }

    @Test
    fun enter_bulletItemMiddle_splitsTheItem() {
        assertThat(enter("- a^b")).isEqualTo("- a\n- ^b")
    }

    @Test
    fun enter_taskItem_continuesWithAnUncheckedBox() {
        assertThat(enter("- [x] done^")).isEqualTo("- [x] done\n- [ ] ^")
    }

    @Test
    fun enter_nestedItem_keepsTheLevel() {
        assertThat(enter("- a\n  - b^")).isEqualTo("- a\n  - b\n  - ^")
    }

    @Test
    fun enter_orderedItem_numbersTheNextOneAndRenumbersTheRest() {
        assertThat(enter("1. a^\n2. b\n3. c")).isEqualTo("1. a\n2. ^\n3. b\n4. c")
    }

    @Test
    fun enter_orderedItemMiddleOfList_skipsDeeperItemsWhenRenumbering() {
        assertThat(enter("1. a^\n   - x\n2. b")).isEqualTo("1. a\n2. ^\n   - x\n3. b")
    }

    @Test
    fun enter_emptyTopLevelItem_leavesTheList() {
        assertThat(enter("- a\n- ^")).isEqualTo("- a\n\n^\n")
    }

    @Test
    fun enter_emptyNestedItem_movesOneLevelOut() {
        assertThat(enter("- a\n  - ^")).isEqualTo("- a\n- ^")
    }

    @Test
    fun enter_beforeItemText_addsAnEmptyItemAbove() {
        assertThat(enter("- a\n- ^b")).isEqualTo("- a\n- \n- ^b")
    }

    // Enter: quotes, code, math, tables

    @Test
    fun enter_quoteEnd_startsAContinuationQuote() {
        assertThat(enter("> a^")).isEqualTo("> a\n\n> ^")
    }

    @Test
    fun enter_emptyQuote_leavesIt() {
        assertThat(enter("> a\n\n> ^")).isEqualTo("> a\n\n^\n")
    }

    @Test
    fun enter_lonelyFenceOpener_addsTheClosingFence() {
        assertThat(enter("```^")).isEqualTo("```\n^\n```")
    }

    @Test
    fun enter_lonelyFenceOpenerWithLanguage_closesWithTheSameFence() {
        assertThat(enter("````kotlin^")).isEqualTo("````kotlin\n^\n````")
    }

    @Test
    fun enter_insideCode_insertsAPlainNewline() {
        assertThat(enter("```\nco^de\n```")).isEqualTo("```\nco\n^de\n```")
    }

    @Test
    fun enter_afterClosingFence_leavesTheBlock() {
        assertThat(enter("```\ncode\n```^")).isEqualTo("```\ncode\n```\n\n^\n")
    }

    @Test
    fun enter_lonelyMathOpener_addsTheClosingDelimiter() {
        assertThat(enter("$$^")).isEqualTo("$$\n^\n$$")
    }

    @Test
    fun enter_afterClosedMath_leavesTheBlock() {
        assertThat(enter("$$\nx_2\n$$^")).isEqualTo("$$\nx_2\n$$\n\n^\n")
    }

    @Test
    fun enter_pipeRowEnd_becomesATableWithAnEmptyRow() {
        assertThat(enter("| a | b |^")).isEqualTo("| a | b |\n| --- | --- |\n| ^ |  |")
    }

    @Test
    fun enter_tableRow_addsAnEmptyRowBelow() {
        assertThat(enter("| a |\n| --- |\n| b^ |")).isEqualTo("| a |\n| --- |\n| b |\n| ^ |")
    }

    @Test
    fun enter_emptyLastTableRow_endsTheTable() {
        assertThat(enter("| a |\n| --- |\n| b |\n| ^ |")).isEqualTo("| a |\n| --- |\n| b |\n\n^\n")
    }

    // Shift+Enter

    @Test
    fun softBreak_paragraph_insertsAHardBreak() {
        assertThat(softBreak("a^b")).isEqualTo("a  \n^b")
    }

    @Test
    fun softBreak_endOfParagraph_isAnEnter() {
        assertThat(softBreak("a^")).isEqualTo("a\n\n^\n")
    }

    @Test
    fun softBreak_quote_keepsTheQuoteMarker() {
        assertThat(softBreak("> a^")).isEqualTo("> a  \n> ^")
    }

    @Test
    fun softBreak_listItem_indentsTheNextLineUnderTheText() {
        assertThat(softBreak("- a^b")).isEqualTo("- a  \n  ^b")
    }

    @Test
    fun softBreak_endOfListItem_isAnEnter() {
        assertThat(softBreak("- a^")).isEqualTo("- a\n- ^")
    }

    @Test
    fun softBreak_code_insertsANewline() {
        assertThat(softBreak("```\na^\n```")).isEqualTo("```\na\n^\n```")
    }

    // Backspace and Delete

    @Test
    fun backspace_blockStart_mergesIntoThePreviousBlock() {
        assertThat(backspace("a\n\n^b")).isEqualTo("a^b")
    }

    @Test
    fun backspace_blockStart_keepsTheBlocksAround() {
        assertThat(backspace("x\n\na\n\n^b\n\ny")).isEqualTo("x\n\na^b\n\ny")
    }

    @Test
    fun backspace_insideText_isLeftToTheField() {
        assertThat(backspace("a^b")).isNull()
    }

    @Test
    fun backspace_startOfFlow_doesNothing() {
        assertThat(backspace("^a")).isNull()
    }

    @Test
    fun backspace_emptyParagraph_removesIt() {
        assertThat(backspace("a\n\n^\n")).isEqualTo("a^\n\n")
    }

    @Test
    fun backspace_afterListItemMarker_removesTheMarker() {
        assertThat(backspace("- ^a")).isEqualTo("^a")
    }

    @Test
    fun backspace_nestedItemMarker_outdentsFirst() {
        assertThat(backspace("- x\n  - ^a")).isEqualTo("- x\n- ^a")
    }

    @Test
    fun backspace_afterHeadingMarker_makesAParagraph() {
        assertThat(backspace("## ^Title")).isEqualTo("^Title")
    }

    @Test
    fun backspace_quote_stripsTheMarkerOfEveryLine() {
        assertThat(backspace("> ^a  \n> b")).isEqualTo("^a  \nb")
    }

    @Test
    fun backspace_listItemStart_stripsTheMarkerBeforeMerging() {
        assertThat(backspace("a\n\n- ^b")).isEqualTo("a\n\n^b")
    }

    @Test
    fun backspace_afterRule_removesTheRule() {
        assertThat(backspace("---\n\n^b")).isEqualTo("^b")
    }

    @Test
    fun backspace_afterCode_movesTheCaretInsteadOfMerging() {
        assertThat(backspace("```\nx\n```\n\n^b")).isEqualTo("```\nx\n```^\n\nb")
    }

    @Test
    fun backspace_selection_deletesIt() {
        assertThat(backspace("a«b\n\nc»d")).isEqualTo("a^d")
    }

    @Test
    fun deleteForward_blockEnd_pullsTheNextBlockUp() {
        assertThat(deleteForward("a^\n\nb")).isEqualTo("a^b")
    }

    @Test
    fun deleteForward_nextBlockIsAListItem_dropsItsMarker() {
        assertThat(deleteForward("a^\n\n- b")).isEqualTo("a^b")
    }

    @Test
    fun deleteForward_lastBlock_doesNothing() {
        assertThat(deleteForward("a^")).isNull()
    }

    @Test
    fun deleteForward_insideText_isLeftToTheField() {
        assertThat(deleteForward("a^b")).isNull()
    }

    // Selections across blocks

    @Test
    fun delete_acrossBlocks_joinsTheOuterParts() {
        val m = marked("x\n\na«b\n\nc»d\n\ny")

        assertThat(BlockEditing.delete(m.doc, m.selection).render()).isEqualTo("x\n\na^d\n\ny")
    }

    @Test
    fun delete_backwardSelection_worksTheSame() {
        val m = marked("a»b\n\nc«d")

        assertThat(BlockEditing.delete(m.doc, m.selection).render()).isEqualTo("a^d")
    }

    @Test
    fun delete_collapsed_changesNothing() {
        val m = marked("ab^c")

        assertThat(BlockEditing.delete(m.doc, m.selection).document).isSameInstanceAs(m.doc)
    }

    @Test
    fun copy_acrossBlocks_isTheMarkdownBetweenTheEnds() {
        val m = marked("a«b\n\n- c\n- d»e")

        assertThat(BlockEditing.copy(m.doc, m.selection)).isEqualTo("b\n\n- c\n- d")
    }

    @Test
    fun copy_insideABlock_isTheSelectedSource() {
        val m = marked("x **«bo»ld**")

        assertThat(BlockEditing.copy(m.doc, m.selection)).isEqualTo("bo")
    }

    @Test
    fun insert_multiBlockText_splitsAtTheCaret() {
        val m = marked("a^b")

        assertThat(BlockEditing.insert(m.doc, m.selection, "x\n\ny").render()).isEqualTo("ax\n\ny^b")
    }

    @Test
    fun insert_listPaste_createsOneBlockPerItem() {
        val m = marked("a\n\n\n^")

        val result = BlockEditing.insert(m.doc, m.selection, "- x\n- y\n- z")

        assertThat(result.document.blocks.map { it.source }).containsExactly("a", "- x", "- y", "- z").inOrder()
        assertThat(result.render()).isEqualTo("a\n\n- x\n- y\n- z^\n")
    }

    @Test
    fun insert_overSelection_replacesIt() {
        val m = marked("a«bc»d")

        assertThat(BlockEditing.insert(m.doc, m.selection, "XY").render()).isEqualTo("aXY^d")
    }

    @Test
    fun normalizePaste_windowsLineEndingsAndControlCharacters_areCleaned() {
        assertThat(BlockEditing.normalizePaste("a\r\nb\rc\u0000d\u000Ce")).isEqualTo("a\nb\ncde")
    }

    @Test
    fun setSource_textWithBlankLine_splitsKeepingTheFirstId() {
        val m = marked("a^\n\nz")
        val id = m.caret.blockId

        val result = BlockEditing.setSource(m.doc, id, "a1\n\na2", 6)

        assertThat(result.document.blocks.map { it.source }).containsExactly("a1", "a2", "z").inOrder()
        assertThat(result.document.blocks[0].id).isEqualTo(id)
        assertThat(result.render()).isEqualTo("a1\n\na2^\n\nz")
    }

    // Indent, shortcuts, caret movement

    @Test
    fun indent_secondItem_nestsUnderTheFirst() {
        assertThat(indent("- a\n- b^")).isEqualTo("- a\n  - b^")
    }

    @Test
    fun indent_firstItem_isRefused() {
        assertThat(indent("- a^")).isNull()
    }

    @Test
    fun indent_twoLevelsBelowTheItemAbove_isRefused() {
        assertThat(indent("- a\n  - b^")).isNull()
    }

    @Test
    fun indent_outdent_movesOneLevelOut() {
        assertThat(indent("- a\n  - b^", outdent = true)).isEqualTo("- a\n- b^")
    }

    @Test
    fun indent_outdentTopLevel_isRefused() {
        assertThat(indent("- a^", outdent = true)).isNull()
    }

    @Test
    fun indent_paragraph_isRefused() {
        assertThat(indent("a^")).isNull()
    }

    @Test
    fun shortcut_emptyBracketsAndSpace_becomeATaskItem() {
        val m = marked("[] ^")

        assertThat(BlockEditing.shortcut(m.doc, m.caret)?.render()).isEqualTo("- [ ] ^")
    }

    @Test
    fun shortcut_dashBracketsAndSpace_becomeATaskItem() {
        val m = marked("- [] ^")

        assertThat(BlockEditing.shortcut(m.doc, m.caret)?.render()).isEqualTo("- [ ] ^")
    }

    @Test
    fun shortcut_notAfterATrigger_doesNothing() {
        val m = marked("a [] ^")

        assertThat(BlockEditing.shortcut(m.doc, m.caret)).isNull()
    }

    @Test
    fun left_blockStart_goesToTheEndOfThePreviousBlock() {
        val m = marked("ab\n\n^c")

        val caret = BlockEditing.left(m.doc, m.caret)

        assertThat(caret).isEqualTo(Caret(m.doc.blocks[0].id, 2))
    }

    @Test
    fun right_blockEnd_goesToTheStartOfTheNextBlock() {
        val m = marked("ab^\n\nc")

        val caret = BlockEditing.right(m.doc, m.caret)

        assertThat(caret).isEqualTo(Caret(m.doc.blocks[1].id, 0))
    }

    @Test
    fun left_startOfFlow_hasNowhereToGo() {
        val m = marked("^ab")

        assertThat(BlockEditing.left(m.doc, m.caret)).isNull()
    }
}
