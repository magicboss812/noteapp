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
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.LibraryRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.repo.OpenDocuments
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

/** Menu and batch actions over a temp library: results, snackbar texts and the open-document refusal. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryDocOpsTest {
    @get:Rule val dispatchers = TestDispatchersRule(UnconfinedTestDispatcher())

    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val open = mutableSetOf<String>()
    private lateinit var root: File
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var ops: LibraryDocOps

    @Before
    fun setUp() {
        root = File(tmp.root, "Folio-Debug")
        val library = LibraryRoot(root)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(library.fs, db, clock, dispatchers.dispatchers)
        val openDocuments =
            object : OpenDocuments {
                override fun isOpen(path: String) = path in open

                override fun anyOpenUnder(folder: String) = false
            }
        documents =
            DocumentRepository(library.fs, scanner, db.dao(), clock, dispatchers.dispatchers, ManifestApp("Folio", "test"), openDocuments)
        ops = LibraryDocOps(documents, LibraryRepository(library.fs, scanner, db.dao(), dispatchers.dispatchers, documents, openDocuments))
    }

    @After
    fun tearDown() = db.close()

    private suspend fun create(title: String): String =
        (
            documents.create(NewDocumentSpec("", title, ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND)) as Outcome.Success
        ).value.path

    @Test
    fun openDocument_everyAction_saysItIsOpenAndChangesNothing() =
        runTest(dispatchers.testDispatcher) {
            val path = create("Open one")
            open += path
            val expected = "“Open one” is open in the editor. Close it first."

            assertThat(ops.rename(path, "Other")).isEqualTo(expected)
            assertThat(ops.move(listOf(path), "Folder")).isEqualTo(expected)
            assertThat(ops.setFavorite(listOf(path), true)).isEqualTo(expected)
            assertThat(ops.delete(listOf(path))).isEqualTo(expected)

            assertThat(File(root, path).exists()).isTrue()
            assertThat(db.dao().document(path)!!.favorite).isFalse()
            assertThat(documents.bin.value).isEmpty()
        }

    @Test
    fun batch_oneOpenOneClosed_doesTheClosedAndReportsTheOpen() =
        runTest(dispatchers.testDispatcher) {
            val busy = create("Busy")
            val free = create("Free")
            open += busy

            val message = ops.setFavorite(listOf(busy, free), true)

            assertThat(message).isEqualTo("1 of 2 done. “Busy” is open in the editor. Close it first.")
            assertThat(db.dao().document(free)!!.favorite).isTrue()
            assertThat(db.dao().document(busy)!!.favorite).isFalse()
        }

    @Test
    fun closedDocuments_actionsSucceed() =
        runTest(dispatchers.testDispatcher) {
            val a = create("A")
            val b = create("B")

            assertThat(ops.rename(a, "A renamed")).isNull()
            assertThat(File(root, "A renamed.folio").exists()).isTrue()
            assertThat(ops.duplicate(b)).isNull()
            assertThat(File(root, "B (2).folio").exists()).isTrue()
            assertThat(ops.delete(listOf(b, "B (2).folio"))).isEqualTo("Moved 2 notes to the bin.")
            assertThat(documents.bin.value).hasSize(2)
        }

    @Test
    fun rename_blankTitle_isRefusedWithoutTouchingTheFile() =
        runTest(dispatchers.testDispatcher) {
            val path = create("Keep")

            assertThat(ops.rename(path, "   ")).isEqualTo("A note needs a title.")
            assertThat(File(root, path).exists()).isTrue()
        }
}
