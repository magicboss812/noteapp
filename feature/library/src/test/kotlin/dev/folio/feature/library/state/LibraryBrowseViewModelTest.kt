package dev.folio.feature.library.state

import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import dev.folio.core.storage.repo.DocumentSort
import dev.folio.core.storage.settings.SettingsStore
import dev.folio.core.testing.FakeClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LibraryBrowseViewModelTest {
    private val store = MapSettings()
    private val dispatcher = StandardTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun state_nothingStored_isDefaults() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.state.value).isEqualTo(LibraryBrowseState())
        }

    @Test
    fun choices_storedAndReadBackByNextViewModel() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.setSort(DocumentSort.NAME)
            advanceUntilIdle()
            vm.toggleViewMode()
            advanceUntilIdle()
            vm.toggleSidebar()
            advanceUntilIdle()

            val next = viewModel()
            advanceUntilIdle()

            assertThat(next.state.value.sort).isEqualTo(DocumentSort.NAME)
            assertThat(next.state.value.viewMode).isEqualTo(LibraryViewMode.LIST)
            assertThat(next.state.value.sidebarCollapsed).isTrue()
        }

    @Test
    fun selectTab_leavesSelectMode() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.toggleSelectMode()
            advanceUntilIdle()
            assertThat(vm.state.value.selectMode).isTrue()

            vm.selectTab(LibraryTab.FAVORITES)
            advanceUntilIdle()

            assertThat(vm.state.value.tab).isEqualTo(LibraryTab.FAVORITES)
            assertThat(vm.state.value.selectMode).isFalse()
        }

    @Test
    fun selectTag_choosesThenClearsOnSecondTapAndDropsTheSelection() =
        runTest(dispatcher) {
            val vm = viewModel()
            advanceUntilIdle()
            vm.toggleSelected("a.folio")
            advanceUntilIdle()

            vm.selectTag("physics")
            advanceUntilIdle()
            assertThat(vm.state.value.tag).isEqualTo("physics")
            assertThat(vm.state.value.selected).isEmpty()

            vm.selectTag("school")
            advanceUntilIdle()
            assertThat(vm.state.value.tag).isEqualTo("school")

            vm.selectTag("school")
            advanceUntilIdle()
            assertThat(vm.state.value.tag).isNull()
        }

    @Test
    fun openFolder_showsAllTabAtThatFolderAndDropsTheSelection() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.selectTab(LibraryTab.BIN)
            vm.toggleSelected("a.folio")
            advanceUntilIdle()

            vm.openFolder("Physics/Lab")
            advanceUntilIdle()

            assertThat(vm.state.value.tab).isEqualTo(LibraryTab.ALL)
            assertThat(vm.state.value.folder).isEqualTo("Physics/Lab")
            assertThat(vm.state.value.selectMode).isFalse()
            assertThat(vm.state.value.selected).isEmpty()

            vm.openFolder("")
            advanceUntilIdle()
            assertThat(vm.state.value.folder).isEqualTo("")
        }

    @Test
    fun toggleSelected_entersSelectModeAndTogglesPath() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.toggleSelected("a.folio")
            vm.toggleSelected("b.folio")
            advanceUntilIdle()
            assertThat(vm.state.value.selectMode).isTrue()
            assertThat(vm.state.value.selected).containsExactly("a.folio", "b.folio")

            vm.toggleSelected("a.folio")
            advanceUntilIdle()
            assertThat(vm.state.value.selected).containsExactly("b.folio")
        }

    @Test
    fun selectAllAndClear_addTheVisibleThenLeaveSelectMode() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.toggleSelected("a.folio")
            vm.selectAll(listOf("b.folio", "c.folio"))
            advanceUntilIdle()
            assertThat(vm.state.value.selected).containsExactly("a.folio", "b.folio", "c.folio")

            vm.clearSelection()
            advanceUntilIdle()
            assertThat(vm.state.value.selected).isEmpty()
            assertThat(vm.state.value.selectMode).isFalse()
        }

    @Test
    fun leavingSelectMode_dropsTheSelection() =
        runTest(dispatcher) {
            val vm = viewModel()
            vm.toggleSelected("a.folio")
            advanceUntilIdle()

            vm.toggleSelectMode()
            advanceUntilIdle()
            assertThat(vm.state.value.selectMode).isFalse()
            assertThat(vm.state.value.selected).isEmpty()

            vm.toggleSelected("a.folio")
            vm.selectTab(LibraryTab.FAVORITES)
            advanceUntilIdle()
            assertThat(vm.state.value.selected).isEmpty()
        }

    @Test
    fun state_unreadableChoices_fallBackToDefaults() =
        runTest(dispatcher) {
            store.write(LibraryBrowseViewModel.PREFS_KEY, "{not json")
            val vm = viewModel()
            advanceUntilIdle()

            assertThat(vm.state.value.sort).isEqualTo(DocumentSort.RECENT)
        }

    @Test
    fun state_storedName_feedsGreeting() =
        runTest(dispatcher) {
            store.write(LibraryBrowseViewModel.NAME_KEY, "Martin")
            val vm = viewModel()
            advanceUntilIdle()

            assertThat(greeting(vm.state.value.userName)).isEqualTo("Hello, Martin")
            assertThat(store.read(LibraryBrowseViewModel.NAME_KEY).first()).isEqualTo("Martin")
        }

    private fun viewModel() = LibraryBrowseViewModel(store, FakeClock())

    private class MapSettings : SettingsStore {
        private val values = MutableStateFlow<Map<String, String>>(emptyMap())

        override fun read(key: String): Flow<String?> = values.map { it[key] }

        override suspend fun write(
            key: String,
            value: String,
        ): Outcome<Unit> {
            values.value = values.value + (key to value)
            return Outcome.Success(Unit)
        }
    }
}
