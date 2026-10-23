package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.storage.repo.DocumentSort
import org.junit.Test
import java.time.ZoneOffset
import java.time.ZonedDateTime

class LibraryBrowseTest {
    private val docs =
        listOf(
            doc("b.folio", "banana", modifiedMs = 30, createdMs = 10, favorite = true),
            doc("a.folio", "Apple", modifiedMs = 20, createdMs = 30),
            doc("c.folio", "cherry", modifiedMs = 10, createdMs = 20, favorite = true),
        )

    @Test
    fun greeting_withName_saysHello() {
        assertThat(greeting("  Martin ")).isEqualTo("Hello, Martin")
    }

    @Test
    fun greeting_blankName_fallsBackToYourNotes() {
        assertThat(greeting("")).isEqualTo("Your notes")
        assertThat(greeting("   ")).isEqualTo("Your notes")
    }

    @Test
    fun dateSubtitle_formatsWeekdayDayAndMonth() {
        val ms = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        assertThat(dateSubtitle(ms, ZoneOffset.UTC)).isEqualTo("Friday, 23 October")
    }

    @Test
    fun cardTimestamp_sameYearShowsTime_earlierYearShowsYear() {
        val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val thisYear = ZonedDateTime.of(2026, 3, 4, 14, 5, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
        val lastYear = ZonedDateTime.of(2025, 12, 31, 23, 59, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()

        assertThat(cardTimestamp(thisYear, now, ZoneOffset.UTC)).isEqualTo("4 Mar, 14:05")
        assertThat(cardTimestamp(lastYear, now, ZoneOffset.UTC)).isEqualTo("31 Dec 2025")
    }

    @Test
    fun visibleDocuments_recent_newestModifiedFirst() {
        assertThat(visibleDocuments(docs, LibraryTab.ALL, DocumentSort.RECENT).map { it.title })
            .containsExactly("banana", "Apple", "cherry")
            .inOrder()
    }

    @Test
    fun visibleDocuments_created_newestCreatedFirst() {
        assertThat(visibleDocuments(docs, LibraryTab.ALL, DocumentSort.CREATED).map { it.title })
            .containsExactly("Apple", "cherry", "banana")
            .inOrder()
    }

    @Test
    fun visibleDocuments_name_ignoresCase() {
        assertThat(visibleDocuments(docs, LibraryTab.ALL, DocumentSort.NAME).map { it.title })
            .containsExactly("Apple", "banana", "cherry")
            .inOrder()
    }

    @Test
    fun visibleDocuments_favoritesTab_keepsOnlyFavorites() {
        assertThat(visibleDocuments(docs, LibraryTab.FAVORITES, DocumentSort.RECENT).map { it.title })
            .containsExactly("banana", "cherry")
            .inOrder()
    }

    @Test
    fun visibleDocuments_tagsAndBinTabs_listNoDocuments() {
        assertThat(visibleDocuments(docs, LibraryTab.TAGS, DocumentSort.RECENT)).isEmpty()
        assertThat(visibleDocuments(docs, LibraryTab.BIN, DocumentSort.RECENT)).isEmpty()
    }

    @Test
    fun folderItems_nestsChildrenUnderParentsSortedByName() {
        val items = folderItems(listOf("work/notes", "Zoo", "work", "alpha", "work/Ideas"))

        assertThat(items.map { it.path to it.depth })
            .containsExactly("alpha" to 0, "work" to 0, "work/Ideas" to 1, "work/notes" to 1, "Zoo" to 0)
            .inOrder()
        assertThat(items[2].name).isEqualTo("Ideas")
    }

    private fun doc(
        path: String,
        title: String,
        modifiedMs: Long,
        createdMs: Long,
        favorite: Boolean = false,
    ) = LibraryDocItem(path, title, "", 1, modifiedMs, createdMs, favorite)
}
