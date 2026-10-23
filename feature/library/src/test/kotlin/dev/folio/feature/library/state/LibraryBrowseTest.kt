package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.designsystem.theme.FolderColor
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

    @Test
    fun folderItems_carryTheStoredTint() {
        val items = folderItems(listOf("a", "a/b"), mapOf("a/b" to "wine"))

        assertThat(items.map { it.tint }).containsExactly(null, "wine").inOrder()
        assertThat(items[1].parent).isEqualTo("a")
        assertThat(items[0].parent).isEqualTo("")
    }

    @Test
    fun visibleDocuments_allTabWithFolder_listsOnlyThatFoldersNotes() {
        val nested =
            listOf(
                doc("top.folio", "Top", modifiedMs = 1, createdMs = 1),
                doc("a/one.folio", "One", modifiedMs = 2, createdMs = 2, folder = "a"),
                doc("a/b/two.folio", "Two", modifiedMs = 3, createdMs = 3, folder = "a/b"),
            )

        assertThat(visibleDocuments(nested, LibraryTab.ALL, DocumentSort.RECENT, folder = "").map { it.title }).containsExactly("Top")
        assertThat(visibleDocuments(nested, LibraryTab.ALL, DocumentSort.RECENT, folder = "a").map { it.title }).containsExactly("One")
        assertThat(visibleDocuments(nested, LibraryTab.ALL, DocumentSort.RECENT).map { it.title }).hasSize(3)
        // Favorites span every folder.
        assertThat(visibleDocuments(nested.map { it.copy(favorite = true) }, LibraryTab.FAVORITES, DocumentSort.RECENT, folder = "a"))
            .hasSize(3)
    }

    @Test
    fun childFolders_keepsOnlyTheDirectChildren() {
        val all = folderItems(listOf("a", "a/b", "a/b/c", "z"))

        assertThat(childFolders(all, "").map { it.path }).containsExactly("a", "z").inOrder()
        assertThat(childFolders(all, "a").map { it.path }).containsExactly("a/b")
        assertThat(childFolders(all, "a/b/c")).isEmpty()
    }

    @Test
    fun existingFolder_fallsBackToRootWhenTheFolderIsGone() {
        val all = folderItems(listOf("a", "a/b"))

        assertThat(existingFolder(all, "a/b")).isEqualTo("a/b")
        assertThat(existingFolder(all, "")).isEqualTo("")
        assertThat(existingFolder(all, "gone")).isEqualTo("")
    }

    @Test
    fun breadcrumb_listsEveryFolderFromTheRoot() {
        assertThat(breadcrumb("")).isEmpty()
        assertThat(breadcrumb("a/b/c"))
            .containsExactly(Crumb("a", "a"), Crumb("b", "a/b"), Crumb("c", "a/b/c"))
            .inOrder()
    }

    @Test
    fun folderSummaries_countNestedNotesAndPickTheNewest() {
        val folders = folderItems(listOf("a", "a/b", "ab"))
        val notes =
            listOf(
                doc("a/old.folio", "Old", modifiedMs = 1, createdMs = 1, folder = "a"),
                doc("a/b/new.folio", "New", modifiedMs = 9, createdMs = 2, folder = "a/b"),
                doc("ab/other.folio", "Other", modifiedMs = 5, createdMs = 3, folder = "ab"),
            )

        val byPath = folderSummaries(folders, notes).associateBy { it.folder.path }

        assertThat(byPath.getValue("a").noteCount).isEqualTo(2)
        assertThat(byPath.getValue("a").latest?.title).isEqualTo("New")
        // "ab" is a sibling, not inside "a".
        assertThat(byPath.getValue("ab").noteCount).isEqualTo(1)
        assertThat(byPath.getValue("a/b").noteCount).isEqualTo(1)
    }

    @Test
    fun folderColor_matchesTheStoredNameOrFallsBack() {
        assertThat(folderColor("wine")).isEqualTo(FolderColor.Wine)
        assertThat(folderColor("PURPLE")).isEqualTo(FolderColor.Purple)
        assertThat(folderColor(null)).isEqualTo(FolderColor.Teal)
        assertThat(folderColor("blue")).isEqualTo(FolderColor.Teal)
        FolderColor.entries.forEach { assertThat(folderColor(tintName(it))).isEqualTo(it) }
    }

    private fun doc(
        path: String,
        title: String,
        modifiedMs: Long,
        createdMs: Long,
        favorite: Boolean = false,
        folder: String = "",
    ) = LibraryDocItem(path, title, folder, 1, modifiedMs, createdMs, favorite)
}
