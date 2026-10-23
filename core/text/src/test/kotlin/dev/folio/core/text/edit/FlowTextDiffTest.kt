package dev.folio.core.text.edit

import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.edit.TextEdit
import org.junit.Test
import kotlin.random.Random

class FlowTextDiffTest {
    @Test
    fun between_equalTexts_isNull() {
        assertThat(FlowTextDiff.between("abc", "abc")).isNull()
    }

    @Test
    fun between_insertion_isAnEmptyRangeReplacement() {
        assertThat(FlowTextDiff.between("ac", "abc")).isEqualTo(TextEdit(1, 1, "b"))
    }

    @Test
    fun between_deletion_isAReplacementByNothing() {
        assertThat(FlowTextDiff.between("abc", "ac")).isEqualTo(TextEdit(1, 2, ""))
    }

    @Test
    fun between_replacementInTheMiddle_coversOnlyTheChange() {
        assertThat(FlowTextDiff.between("one two three", "one 2 three")).isEqualTo(TextEdit(4, 7, "2"))
    }

    @Test
    fun between_repeatedCharacters_stillAppliesCleanly() {
        val edit = FlowTextDiff.between("aaa", "aaaa")!!

        assertThat("aaa".replaceRange(edit.start, edit.end, edit.replacement)).isEqualTo("aaaa")
    }

    @Test
    fun between_fromAndToEmpty_isTheWholeText() {
        assertThat(FlowTextDiff.between("", "hi")).isEqualTo(TextEdit(0, 0, "hi"))
        assertThat(FlowTextDiff.between("hi", "")).isEqualTo(TextEdit(0, 2, ""))
    }

    @Test
    fun between_surrogatePairs_areNeverSplit() {
        val before = "a😀b"
        val after = "a😁b"

        val edit = FlowTextDiff.between(before, after)!!

        assertThat(edit).isEqualTo(TextEdit(1, 3, "😁"))
    }

    @Test
    fun between_randomTexts_applyingTheEditGivesTheNewText() {
        val random = Random(7)
        val alphabet = "ab \n😀"

        fun text() = String(CharArray(random.nextInt(12)) { alphabet[random.nextInt(alphabet.length)] })
        repeat(2000) {
            val before = text()
            val after = text()

            val edit = FlowTextDiff.between(before, after)

            val applied = edit?.let { before.replaceRange(it.start, it.end, it.replacement) } ?: before
            assertThat(applied).isEqualTo(after)
        }
    }
}
