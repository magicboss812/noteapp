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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/** Folder menu actions over a temp library: files on disk, index, bin round trip and snackbar texts. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryFolderOpsTest {
    @get:Rule val dispatchers = TestDispatchersRule(UnconfinedTestDispatcher())

    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val openFolders = mutableSetOf<String>()
    private lateinit var root: File
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var library: LibraryRepository
    private lateinit var ops: LibraryFolderOps

    @Before
    fun setUp() {
        root = File(tmp.root, "Folio-Debug")
        val libraryRoot = LibraryRoot(root)
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(libraryRoot.fs, db, clock, dispatchers.dispatchers)
        val openDocuments =
            object : OpenDocuments {
                override fun isOpen(path: String) = false

                override fun anyOpenUnder(folder: String) = folder in openFolders
            }
        documents =
            DocumentRepository(
                libraryRoot.fs,
                scanner,
                db.dao(),
                clock,
                dispatchers.dispatchers,
                ManifestApp("Folio", "test"),
                openDocuments,
            )
        library = LibraryRepository(libraryRoot.fs, scanner, db.dao(), dispatchers.dispatchers, documents, openDocuments)
        ops = LibraryFolderOps(library)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun folderPaths() = library.folderTree().first().map { it.path }

    private suspend fun createNote(
        folder: String,
        title: String,
    ): String =
        (
            documents.create(NewDocumentSpec(folder, title, ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND)) as Outcome.Success
        ).value.path

    @Test
    fun create_nestedFolders_existOnDiskAndInTheIndex() =
        runTest(dispatchers.testDispatcher) {
            assertThat(ops.create("", "School")).isNull()
            assertThat(ops.create("School", "Physics")).isNull()
            assertThat(ops.create("School/Physics", "Lab")).isNull()

            assertThat(File(root, "School/Physics/Lab").isDirectory).isTrue()
            assertThat(folderPaths()).containsExactly("School", "School/Physics", "School/Physics/Lab")
        }

    @Test
    fun create_blankName_isRefusedAndCreatesNothing() =
        runTest(dispatchers.testDispatcher) {
            assertThat(ops.create("", "   ")).isEqualTo("A folder needs a name.")

            assertThat(folderPaths()).isEmpty()
        }

    @Test
    fun create_sameNameTwice_getsASuffix() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "Ideas")
            ops.create("", "Ideas")

            assertThat(folderPaths()).containsExactly("Ideas", "Ideas (2)")
        }

    @Test
    fun setTint_isStoredInFolderJsonAndReadBack() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "Art")

            assertThat(ops.setTint("Art", tintName(dev.folio.core.designsystem.theme.FolderColor.Wine))).isNull()

            assertThat(File(root, "Art/.folder.json").readText()).contains("\"wine\"")
            assertThat(
                library
                    .folderTree()
                    .first()
                    .single()
                    .tint,
            ).isEqualTo("wine")
        }

    @Test
    fun renameAndMove_keepTheNotesInside() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "Physics")
            ops.create("", "School")
            createNote("Physics", "Waves")

            assertThat(ops.rename("Physics", "Physik")).isNull()
            assertThat(ops.move("Physik", "School")).isNull()

            assertThat(File(root, "School/Physik/Waves.folio").exists()).isTrue()
            assertThat(db.dao().document("School/Physik/Waves.folio")).isNotNull()
            assertThat(folderPaths()).containsExactly("School", "School/Physik")
        }

    @Test
    fun move_intoItselfOrItsOwnSubfolder_isRefusedWithoutThrowing() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "A")
            ops.create("A", "B")

            assertThat(ops.move("A", "A")).isEqualTo("A folder cannot move into itself.")
            assertThat(ops.move("A", "A/B")).isEqualTo("A folder cannot move into itself.")

            assertThat(folderPaths()).containsExactly("A", "A/B")
        }

    @Test
    fun delete_nestedFolderWithNotes_goesToTheBinAndRestoresWhole() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "School")
            ops.create("School", "Physics")
            createNote("School/Physics", "Waves")
            createNote("School", "Syllabus")

            assertThat(ops.delete("School")).isEqualTo("Moved “School” to the bin.")

            assertThat(File(root, "School").exists()).isFalse()
            assertThat(folderPaths()).isEmpty()
            assertThat(db.dao().document("School/Physics/Waves.folio")).isNull()
            val entry = documents.bin.value.single()
            assertThat(entry.isFolder).isTrue()

            documents.restore(entry).orThrowForTest()

            assertThat(File(root, "School/Physics/Waves.folio").exists()).isTrue()
            assertThat(File(root, "School/Syllabus.folio").exists()).isTrue()
            assertThat(db.dao().document("School/Physics/Waves.folio")).isNotNull()
            assertThat(folderPaths()).containsExactly("School", "School/Physics")
        }

    @Test
    fun delete_whenTheParentIsGoneToo_restoreRecreatesTheParents() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "School")
            ops.create("School", "Physics")
            createNote("School/Physics", "Waves")
            ops.delete("School/Physics")
            ops.delete("School")
            val inner = documents.bin.value.first { it.originalPath == "School/Physics" }

            documents.restore(inner).orThrowForTest()

            assertThat(File(root, "School/Physics/Waves.folio").exists()).isTrue()
            assertThat(db.dao().document("School/Physics/Waves.folio")).isNotNull()
        }

    @Test
    fun folderWithAnOpenNote_cannotBeRenamedMovedOrDeleted() =
        runTest(dispatchers.testDispatcher) {
            ops.create("", "Busy")
            ops.create("", "Other")
            createNote("Busy", "Open one")
            openFolders += "Busy"
            val expected = "“Busy” is open in the editor. Close it first."

            assertThat(ops.rename("Busy", "Free")).isEqualTo(expected)
            assertThat(ops.move("Busy", "Other")).isEqualTo(expected)
            assertThat(ops.delete("Busy")).isEqualTo(expected)

            assertThat(File(root, "Busy/Open one.folio").exists()).isTrue()
            assertThat(documents.bin.value).isEmpty()
        }

    private fun Outcome<*>.orThrowForTest() {
        check(this is Outcome.Success) { "expected success but was $this" }
    }
}
