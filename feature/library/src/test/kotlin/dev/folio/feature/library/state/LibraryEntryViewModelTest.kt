package dev.folio.feature.library.state

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dagger.Lazy
import dev.folio.core.common.JavaFileFolioFs
import dev.folio.core.common.Outcome
import dev.folio.core.format.manifest.ManifestApp
import dev.folio.core.storage.index.IndexDb
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.library.LibraryRoot
import dev.folio.core.storage.repo.DocumentRepository
import dev.folio.core.storage.repo.LibraryRepository
import dev.folio.core.storage.repo.NewDocumentSpec
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.Recovery
import dev.folio.core.storage.work.RecoveryEvents
import dev.folio.core.storage.work.WorkingCopyStore
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

/** Onboarding/ready decision and the placeholder document list over a temp library and in-memory index. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
class LibraryEntryViewModelTest {
    @get:Rule val dispatchers = TestDispatchersRule(UnconfinedTestDispatcher())

    @get:Rule val tmp = TemporaryFolder()

    private val clock = FakeClock()
    private var granted = false
    private lateinit var root: File
    private lateinit var library: LibraryRoot
    private lateinit var db: IndexDb
    private lateinit var scanner: LibraryScanner
    private lateinit var documents: DocumentRepository
    private lateinit var vm: LibraryEntryViewModel

    @Before
    fun setUp() {
        root = File(tmp.root, "Folio-Debug")
        library = LibraryRoot(root)
        val appFs = JavaFileFolioFs(tmp.newFolder("app"))
        val recovery = Recovery(WorkingCopyStore(appFs, library.fs), Packer(library.fs, appFs, clock), clock, RecoveryEvents())
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), IndexDb::class.java).build()
        scanner = LibraryScanner(library.fs, db, clock, dispatchers.dispatchers)
        documents = DocumentRepository(library.fs, scanner, db.dao(), clock, dispatchers.dispatchers, ManifestApp("Folio", "test"))
        val repository = LibraryRepository(library.fs, scanner, db.dao(), dispatchers.dispatchers, documents)
        vm =
            LibraryEntryViewModel(
                LibraryAccess({ granted }, library),
                recovery,
                dispatchers.dispatchers,
                Lazy { scanner },
                Lazy { repository },
            )
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun refresh_afterGrant_switchesFromOnboardingToReady() =
        runTest(dispatchers.testDispatcher) {
            assertThat(vm.state.value).isEqualTo(LibraryAccessState.Checking)
            vm.refresh()
            assertThat(vm.state.first { it != LibraryAccessState.Checking }).isEqualTo(LibraryAccessState.NeedsPermission(library.label))
            granted = true
            vm.refresh()
            assertThat(vm.state.first { it is LibraryAccessState.Ready }).isEqualTo(LibraryAccessState.Ready(root.path))
        }

    @Test
    fun refresh_ready_listsDocumentsOfAllFoldersNewestFirst() =
        runTest(dispatchers.testDispatcher) {
            create("", "Older")
            clock.advanceMs(MINUTE_MS)
            create("perf", "Newer")
            granted = true

            vm.refresh()
            val listed = vm.documents.first { it.size == 2 }

            assertThat(listed.map { it.title }).containsExactly("Newer", "Older").inOrder()
            assertThat(listed[0].folder).isEqualTo("perf")
            assertThat(listed[0].pageCount).isEqualTo(2)
        }

    private suspend fun create(
        folder: String,
        title: String,
    ) {
        val spec = NewDocumentSpec(folder, title, ModelFixtures.A4, ModelFixtures.LINED_BACKGROUND, 2)
        assertThat(documents.create(spec)).isInstanceOf(Outcome.Success::class.java)
    }

    private companion object {
        const val MINUTE_MS = 60_000L
    }
}
