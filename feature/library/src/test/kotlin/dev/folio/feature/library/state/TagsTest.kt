package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.storage.repo.DocumentSort
import kotlinx.collections.immutable.persistentListOf
import org.junit.Test

class TagsTest {
    @Test
    fun normalizeTag_trimsHashAndWhitespace() {
        assertThat(normalizeTag("  #Physics  lab ")).isEqualTo("Physics lab")
        assertThat(normalizeTag("##x")).isEqualTo("x")
    }

    @Test
    fun normalizeTag_blankOrOnlyHash_isNull() {
        assertThat(normalizeTag("   ")).isNull()
        assertThat(normalizeTag(" # ")).isNull()
    }

    @Test
    fun normalizeTag_longInput_isCutAtTheLimit() {
        assertThat(normalizeTag("a".repeat(TAG_MAX_LENGTH + 10))).hasLength(TAG_MAX_LENGTH)
    }

    @Test
    fun addTag_appendsNewTag() {
        assertThat(addTag(listOf("a"), " b ")).containsExactly("a", "b").inOrder()
    }

    @Test
    fun addTag_duplicateIgnoringCase_isNotAddedTwice() {
        assertThat(addTag(listOf("Physics"), "physics")).containsExactly("Physics")
    }

    @Test
    fun addTag_matchingKnownTag_takesTheKnownSpelling() {
        assertThat(addTag(emptyList(), "physics", known = listOf("Physics", "School"))).containsExactly("Physics")
    }

    @Test
    fun addTag_blankInput_changesNothing() {
        assertThat(addTag(listOf("a"), "  ")).containsExactly("a")
    }

    @Test
    fun removeTag_dropsOnlyThatTag() {
        assertThat(removeTag(listOf("a", "b"), "a")).containsExactly("b")
    }

    @Test
    fun tagSuggestions_skipCurrentAndRankPrefixMatchesFirst() {
        val all = listOf("lab", "physics", "school", "physical-education", "chemistry")

        assertThat(tagSuggestions(all, current = listOf("School"), query = "ph")).containsExactly("physical-education", "physics").inOrder()
        assertThat(tagSuggestions(all, current = emptyList(), query = "ics")).containsExactly("physics")
    }

    @Test
    fun tagSuggestions_emptyQuery_listsRemainingTagsLimitedToEight() {
        val all = (1..12).map { "t%02d".format(it) }

        val suggestions = tagSuggestions(all, current = listOf("t01"), query = "")

        assertThat(suggestions).hasSize(8)
        assertThat(suggestions).doesNotContain("t01")
    }

    @Test
    fun tagCounts_mostUsedFirstThenAlphabetical() {
        val docs =
            listOf(
                doc("1", tags = listOf("school", "physics")),
                doc("2", tags = listOf("physics")),
                doc("3", tags = listOf("art")),
                doc("4"),
            )

        assertThat(tagCounts(docs))
            .containsExactly(TagCount("physics", 2), TagCount("art", 1), TagCount("school", 1))
            .inOrder()
    }

    @Test
    fun visibleDocuments_tagsTab_noTagListsEveryTaggedNote() {
        val docs = listOf(doc("a", tags = listOf("x")), doc("b"), doc("c", tags = listOf("y")))

        assertThat(visibleDocuments(docs, LibraryTab.TAGS, DocumentSort.NAME).map { it.path }).containsExactly("a", "c").inOrder()
    }

    @Test
    fun visibleDocuments_tagsTab_withTagFiltersToThatTag() {
        val docs = listOf(doc("a", tags = listOf("x")), doc("b", tags = listOf("x", "y")), doc("c", tags = listOf("y")))

        assertThat(visibleDocuments(docs, LibraryTab.TAGS, DocumentSort.NAME, tag = "y").map { it.path })
            .containsExactly("b", "c")
            .inOrder()
    }

    private fun doc(
        path: String,
        tags: List<String> = emptyList(),
    ) = LibraryDocItem(path, path, "", 1, tags = persistentListOf(*tags.toTypedArray()))
}
