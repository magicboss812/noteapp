package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.JavaFileFolioFs
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.library.LibraryRoot
import dev.folio.core.storage.work.Packer
import dev.folio.core.storage.work.Recovery
import dev.folio.core.storage.work.RecoveryEvents
import dev.folio.core.storage.work.WorkingCopyStore
import dev.folio.core.testing.FakeClock
import dev.folio.core.testing.TestDispatchersRule
import kotlinx.coroutines.test.runTest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class LibraryEntryViewModelTest {
    @get:Rule val dispatchers = TestDispatchersRule()

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun refresh_afterGrant_switchesFromOnboardingToReady() =
        runTest(dispatchers.testDispatcher) {
            var granted = false
            val root = File(tmp.root, "Folio-Debug")
            val library = LibraryRoot(root)
            val appFs = JavaFileFolioFs(tmp.newFolder("app"))
            val clock = FakeClock()
            val recovery = Recovery(WorkingCopyStore(appFs, library.fs), Packer(library.fs, appFs, clock), clock, RecoveryEvents())
            val vm = LibraryEntryViewModel(LibraryAccess({ granted }, library), recovery, dispatchers.dispatchers)
            assertThat(vm.state.value).isEqualTo(LibraryAccessState.Checking)
            vm.refresh()
            testScheduler.advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(LibraryAccessState.NeedsPermission)
            granted = true
            vm.refresh()
            testScheduler.advanceUntilIdle()
            assertThat(vm.state.value).isEqualTo(LibraryAccessState.Ready(root.path))
        }
}
