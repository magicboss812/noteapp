package dev.folio.feature.library.state

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.library.LibraryRoot
import dev.folio.core.storage.repo.BinRetention
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.testing.TestDispatchersRule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Restore, delete forever and empty bin over a temp library: results, snackbar texts and the item mapping. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryBinOpsTest {
    @get:Rule val dispatchers = TestDispatchersRule(UnconfinedTestDispatcher())

    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private lateinit var root: File
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var ops: LibraryBinOps

    @Before
    fun setUp() {
        root = File(tmp.root, "Folio-Debug")
        val library = LibraryRoot(root)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(library.fs, db, clock, dispatchers.dispatchers)
        documents = DocumentRepository(library.fs, scanner, db.dao(), clock, dispatchers.dispatchers, ManifestApp("Folio", "test"))
        ops = LibraryBinOps(documents)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun trashed(
        title: String,
        folder: String = "",
    ): LibraryBinItem {
        val path =
            (
                documents.create(
                    NewDocumentSpec(folder, title, ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND),
                ) as Outcome.Success
            ).value.path
        documents.delete(path)
        return binItems(documents.bin.value, BinRetention.DAYS_30, clock.nowMs()).first { it.name == title }
    }

    @Test
    fun restore_putsTheNoteBackAndNamesTheFolder() =
        runTest(dispatchers.testDispatcher) {
            val rootNote = trashed("Root note")
            val inFolder = trashed("Lab", folder = "Physics")

            assertThat(ops.restore(rootNote)).isEqualTo("Restored “Root note”.")
            assertThat(ops.restore(inFolder)).isEqualTo("Restored “Lab” to Physics.")

            assertThat(File(root, "Root note.folio").exists()).isTrue()
            assertThat(File(root, "Physics/Lab.folio").exists()).isTrue()
            assertThat(db.dao().document("Physics/Lab.folio")).isNotNull()
            assertThat(documents.bin.value).isEmpty()
        }

    @Test
    fun deleteForever_removesOnlyThatEntry() =
        runTest(dispatchers.testDispatcher) {
            val a = trashed("A")
            trashed("B")

            assertThat(ops.deleteForever(a)).isEqualTo("Deleted “A” forever.")

            assertThat(documents.bin.value.map { it.name }).containsExactly("B.folio")
        }

    @Test
    fun staleItem_isReportedInsteadOfFailing() =
        runTest(dispatchers.testDispatcher) {
            val a = trashed("A")
            documents.emptyBin()

            assertThat(ops.restore(a)).isEqualTo("That item is no longer in the bin.")
            assertThat(ops.deleteForever(a)).isEqualTo("That item is no longer in the bin.")
        }

    @Test
    fun emptyBin_countsWhatItDeleted() =
        runTest(dispatchers.testDispatcher) {
            trashed("A")
            assertThat(ops.emptyBin()).isEqualTo("Deleted 1 item forever.")

            trashed("B")
            trashed("C")
            assertThat(ops.emptyBin()).isEqualTo("Deleted 2 items forever.")
            assertThat(documents.bin.value).isEmpty()
        }

    @Test
    fun binItems_mapAgeAndDaysLeft() =
        runTest(dispatchers.testDispatcher) {
            trashed("Old")
            clock.advanceMs(12 * DAY_MS)

            val item = binItems(documents.bin.value, BinRetention.DAYS_30, clock.nowMs()).single()
            assertThat(item.name).isEqualTo("Old")
            assertThat(item.daysAgo).isEqualTo(12)
            assertThat(item.daysLeft).isEqualTo(18)
            assertThat(binItems(documents.bin.value, BinRetention.NEVER, clock.nowMs()).single().daysLeft).isNull()
        }

    @Test
    fun texts_readNaturally() {
        assertThat(deletedText(0)).isEqualTo("Deleted today")
        assertThat(deletedText(1)).isEqualTo("Deleted yesterday")
        assertThat(deletedText(5)).isEqualTo("Deleted 5 days ago")
        assertThat(daysLeftText(0)).isEqualTo("Last day")
        assertThat(daysLeftText(1)).isEqualTo("1 day left")
        assertThat(daysLeftText(12)).isEqualTo("12 days left")
        assertThat(BinRetention.DAYS_90.label).isEqualTo("90 days")
        assertThat(BinRetention.NEVER.label).isEqualTo("Never")
    }

    private companion object {
        const val DAY_MS = 24L * 60 * 60 * 1000
    }
}
