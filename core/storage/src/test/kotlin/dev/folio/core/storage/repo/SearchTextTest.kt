package dev.folio.core.storage.repo

import com.google.common.truth.Truth.assertThat
import dev.folio.core.storage.index.DocStatus
import dev.folio.core.storage.index.DocumentRow
import org.junit.Test

class SearchTextTest {
    @Test
    fun parseSnippet_markedWords_becomeRanges() {
        val (s, e) = SearchText.MARK_START to SearchText.MARK_END
        val marked = "…the ${s}velo${e}city of\n  ${s}vec${e}tors…"

        val parsed = SearchText.parseSnippet(marked)

        assertThat(parsed.text).isEqualTo("…the velocity of vectors…")
        assertThat(parsed.ranges.map { parsed.text.substring(it.first, it.last + 1) }).containsExactly("velo", "vec").inOrder()
    }

    @Test
    fun parseSnippet_emptyAndUnbalancedMarks_areIgnored() {
        val (s, e) = SearchText.MARK_START to SearchText.MARK_END
        val marked = "$s${e}plain $e${s}tail"

        val parsed = SearchText.parseSnippet(marked)

        assertThat(parsed.text).isEqualTo("plain tail")
        assertThat(parsed.ranges).isEmpty()
    }

    @Test
    fun highlightTitle_marksEveryWordAPrefixMatches() {
        val result = SearchText.highlightTitle("Physics: Kinematics 101", SearchText.tokens("kin phy"))

        assertThat(result.ranges.map { result.text.substring(it.first, it.last + 1) }).containsExactly("Physics", "Kinematics").inOrder()
    }

    @Test
    fun titleHit_requiresEveryTokenAsWordPrefix() {
        val tokens = SearchText.tokens("kin phys")

        assertThat(SearchText.titleHit("Physics Kinematics", tokens)).isTrue()
        assertThat(SearchText.titleHit("Physics", tokens)).isFalse()
        assertThat(SearchText.titleHit("Anything", emptyList())).isFalse()
    }

    @Test
    fun rank_titleMatchesFirstThenInputOrder() {
        val bodyOnlyNewest = row("Lecture notes", "a")
        val titleOlder = row("Velocity", "b")
        val bodyOnlyOlder = row("Mechanics", "c")

        val ranked =
            SearchText.rank(
                listOf(bodyOnlyNewest to "x", titleOlder to "y", bodyOnlyOlder to "z"),
                SearchText.tokens("velo"),
            )

        assertThat(ranked.map { it.doc.path }).containsExactly("b", "a", "c").inOrder()
    }

    private fun row(
        title: String,
        path: String,
    ) = DocumentRow(
        path = path,
        docId = path,
        folderPath = "",
        title = title,
        createdMs = 0,
        modifiedMs = 0,
        favorite = false,
        pageCount = 1,
        textPreview = "",
        fileSize = 0,
        fileMtime = 0,
        coverThumbPath = null,
        formatVersion = 1,
        status = DocStatus.OK,
    )
}
