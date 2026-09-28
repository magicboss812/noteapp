package dev.folio.core.storage.repo

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.format.container.DocumentCodec
import dev.folio.core.format.container.FolioContainerReader
import dev.folio.core.format.container.FolioEntries
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DocumentRepositoryTest : RepositoryTestBase() {
    @Test
    fun create_writesIndexedFile_nameConflictGetsSuffix() =
        runTest {
            val first = documents.create(spec("Physics: Week 1", "School")).orThrow()
            val second = documents.create(spec("Physics: Week 1", "School")).orThrow()

            assertThat(first.path).isEqualTo("School/Physics- Week 1.folio")
            assertThat(second.path).isEqualTo("School/Physics- Week 1 (2).folio")
            val row = db.dao().document(first.path)!!
            assertThat(row.title).isEqualTo("Physics: Week 1")
            assertThat(row.docId).isEqualTo(first.docId.value)
            assertThat(row.pageCount).isEqualTo(1)
            assertThat(readTitle(first.path)).isEqualTo("Physics: Week 1")
        }

    @Test
    fun duplicate_newIdAndSuffixedTitle_originalUntouched() =
        runTest {
            val original = documents.create(spec("Notes")).orThrow()
            val before = file(original.path).readBytes()

            val copy = documents.duplicate(original.path).orThrow()

            assertThat(copy.path).isEqualTo("Notes (2).folio")
            assertThat(copy.docId).isNotEqualTo(original.docId)
            assertThat(readTitle(copy.path)).isEqualTo("Notes (2)")
            assertThat(file(original.path).readBytes()).isEqualTo(before)
            assertThat(
                db
                    .dao()
                    .documentsById(copy.docId.value)
                    .single()
                    .path,
            ).isEqualTo(copy.path)
        }

    @Test
    fun rename_changesFileTitleIndexAndSearchText() =
        runTest {
            val doc = documents.create(spec("Draft")).orThrow()
            documents.create(spec("Final")).orThrow()

            val renamed = documents.rename(doc.path, "Final").orThrow()

            assertThat(renamed).isEqualTo("Final (2).folio")
            assertThat(file(doc.path).exists()).isFalse()
            assertThat(readTitle(renamed)).isEqualTo("Final")
            assertThat(db.dao().document(doc.path)).isNull()
            assertThat(db.dao().document(renamed)!!.docId).isEqualTo(doc.docId.value)
            val searchText = FolioContainerReader.open(file(renamed)).orThrow().use { it.read(FolioEntries.SEARCH_TEXT, 1_000).orThrow() }
            assertThat(searchText.decodeToString().lines().first()).isEqualTo("Final")
        }

    @Test
    fun move_intoFolderWithSameName_getsSuffix() =
        runTest {
            val a = documents.create(spec("Lab")).orThrow()
            documents.create(spec("Lab", "Chemistry")).orThrow()

            val moved = documents.move(a.path, "Chemistry").orThrow()

            assertThat(moved).isEqualTo("Chemistry/Lab (2).folio")
            assertThat(db.dao().document(moved)!!.folderPath).isEqualTo("Chemistry")
            assertThat(db.dao().document(a.path)).isNull()
        }

    @Test
    fun deleteAndRestore_returnsToOriginalPath_evenIfFolderWasRemoved() =
        runTest {
            val doc = documents.create(spec("Essay", "English/2026")).orThrow()

            val entry = documents.delete(doc.path).orThrow()

            assertThat(entry.trashPath).startsWith(".trash/")
            assertThat(file("${entry.trashPath}.trash.json").exists()).isTrue()
            assertThat(db.dao().document(doc.path)).isNull()
            assertThat(
                documents.bin.value
                    .single()
                    .originalPath,
            ).isEqualTo(doc.path)
            assertThat(
                documents.bin.value
                    .single()
                    .docId,
            ).isEqualTo(doc.docId.value)

            file("English").deleteRecursively()
            val restored = documents.restore(documents.bin.value.single()).orThrow()

            assertThat(restored).isEqualTo(doc.path)
            assertThat(file(doc.path).exists()).isTrue()
            assertThat(db.dao().document(doc.path)).isNotNull()
            assertThat(documents.bin.value).isEmpty()
        }

    @Test
    fun restore_originalPathTaken_getsSuffix() =
        runTest {
            val doc = documents.create(spec("Plan")).orThrow()
            val entry = documents.delete(doc.path).orThrow()
            documents.create(spec("Plan")).orThrow()

            assertThat(documents.restore(entry).orThrow()).isEqualTo("Plan (2).folio")
        }

    @Test
    fun deleteForeverAndEmptyBin_removeFilesAndSidecars() =
        runTest {
            val a = documents.delete(documents.create(spec("A")).orThrow().path).orThrow()
            documents.delete(documents.create(spec("B")).orThrow().path).orThrow()
            documents.delete(documents.create(spec("C")).orThrow().path).orThrow()

            documents.deleteForever(a).orThrow()
            assertThat(file(a.trashPath).exists()).isFalse()
            assertThat(documents.bin.value).hasSize(2)

            assertThat(documents.emptyBin().orThrow()).isEqualTo(2)
            assertThat(file(".trash").list()!!.toList()).isEmpty()
        }

    @Test
    fun purgeBin_deletesOnlyEntriesOlderThanRetention() =
        runTest {
            documents.delete(documents.create(spec("Old")).orThrow().path).orThrow()
            clock.advanceMs(31L * DAY_MS)
            documents.delete(documents.create(spec("New")).orThrow().path).orThrow()

            assertThat(documents.purgeBin(null).orThrow()).isEqualTo(0)
            assertThat(documents.purgeBin(30).orThrow()).isEqualTo(1)
            assertThat(documents.bin.value.map { it.name }).containsExactly("New.folio")
        }

    @Test
    fun bin_flowReflectsExternalState() =
        runTest {
            documents.delete(documents.create(spec("X")).orThrow().path).orThrow()
            assertThat(documents.bin.first()).hasSize(1)
        }

    private fun readTitle(path: String): String =
        FolioContainerReader.open(file(path)).orThrow().use { DocumentCodec.readManifest(it).orThrow().title }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
