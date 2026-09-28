package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.library.LibraryRoot
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
            val vm = LibraryEntryViewModel(LibraryAccess({ granted }, LibraryRoot(root)), dispatchers.dispatchers)
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
