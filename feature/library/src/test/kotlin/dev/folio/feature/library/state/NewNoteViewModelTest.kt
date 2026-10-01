package dev.folio.feature.library.state

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.model.Orientation
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.library.LibraryRoot
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.testing.FakeClock
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

/** The new-note flow over a temp library and an in-memory index. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class NewNoteViewModelTest {
    @get:Rule val dispatchers = TestDispatchersRule(UnconfinedTestDispatcher())

    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private val settings = FakeSettingsStore()
    private lateinit var root: File
    private lateinit var db: IndexDb
    private lateinit var documents: DocumentRepository
    private lateinit var vm: NewNoteViewModel

    @Before
    fun setUp() {
        root = File(tmp.root, "Folio-Debug")
        val library = LibraryRoot(root)
        library.fs.mkdirs("")
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        val scanner = LibraryScanner(library.fs, db, clock, dispatchers.dispatchers)
        documents = DocumentRepository(library.fs, scanner, db.dao(), clock, dispatchers.dispatchers, ManifestApp("Folio", "test"))
        vm = NewNoteViewModel(Lazy { documents }, NewNoteDefaultsStore(settings), clock)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun show_opensWithTodaysTitleAndDefaults() {
        vm.show()

        val editing = vm.state.value as NewNoteState.Editing
        assertThat(editing.form).isEqualTo(NewNoteForm(NewNoteForm.defaultTitle(clock.nowMs())))
    }

    @Test
    fun create_writesTheFileOpensItAndRemembersTheChoices() =
        runTest(dispatchers.testDispatcher) {
            vm.show()
            vm.update(
                (vm.state.value as NewNoteState.Editing).form.copy(title = "Physics", size = PaperSize.A5, template = TemplateKind.GRID),
            )
            var opened: String? = null

            vm.create("") { opened = it }
            vm.state.first { it == NewNoteState.Hidden } // indexing runs on Room's own threads

            assertThat(opened).isEqualTo("Physics.folio")
            assertThat(File(root, "Physics.folio").isFile).isTrue()
            vm.show()
            val next = (vm.state.first { it is NewNoteState.Editing } as NewNoteState.Editing).form
            assertThat(next.size).isEqualTo(PaperSize.A5)
            assertThat(next.template).isEqualTo(TemplateKind.GRID)
            assertThat(next.title).startsWith("Untitled")
        }

    @Test
    fun create_infiniteLandscape_writesTheFile() =
        runTest(dispatchers.testDispatcher) {
            vm.show()
            vm.update(
                (vm.state.value as NewNoteState.Editing).form.copy(
                    title = "Board",
                    orientation = Orientation.LANDSCAPE,
                    pageType = NotePageType.INFINITE,
                ),
            )

            vm.create("") {}
            vm.state.first { it == NewNoteState.Hidden }

            assertThat(File(root, "Board.folio").isFile).isTrue()
        }

    @Test
    fun dismiss_createsNothing() {
        vm.show()
        vm.dismiss()

        assertThat(vm.state.value).isEqualTo(NewNoteState.Hidden)
        assertThat(root.listFiles { f -> f.name.endsWith(".folio") }).isEmpty()
    }
}
