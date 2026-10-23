package dev.folio.feature.library.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.Clock
import dev.folio.core.common.FolioLog
import dev.folio.core.storage.repo.DocumentSort
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import javax.inject.Inject

/**
 * Browse state of the library: tab and select mode live here, sort order, view mode and the collapsed sidebar are
 * stored in the app settings (one JSON key), the greeting name comes from [NAME_KEY] (set by the settings screen).
 */
@HiltViewModel
class LibraryBrowseViewModel
    @Inject
    constructor(
        private val settings: SettingsStore,
        private val clock: Clock,
    ) : ViewModel() {
        private val transient = MutableStateFlow(Transient())

        /** Current browse state; defaults until the settings are read. */
        val state: StateFlow<LibraryBrowseState> =
            combine(settings.read(PREFS_KEY).map(::decode), settings.read(NAME_KEY), transient) { prefs, name, t ->
                LibraryBrowseState(t.tab, prefs.sort, prefs.viewMode, t.selectMode, prefs.sidebarCollapsed, name.orEmpty())
            }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryBrowseState())

        /** Current wall-clock time for the date line and card timestamps. */
        fun nowMs(): Long = clock.nowMs()

        /** Shows [tab]; leaves select mode because the selection belongs to the old list. */
        fun selectTab(tab: LibraryTab) = transient.update { it.copy(tab = tab, selectMode = false) }

        /** Toggles select mode. */
        fun toggleSelectMode() = transient.update { it.copy(selectMode = !it.selectMode) }

        /** Stores the sort order. */
        fun setSort(sort: DocumentSort) = store { it.copy(sort = sort) }

        /** Switches between grid and list. */
        fun toggleViewMode() =
            store {
                it.copy(
                    viewMode =
                        if (it.viewMode ==
                            LibraryViewMode.GRID
                        ) {
                            LibraryViewMode.LIST
                        } else {
                            LibraryViewMode.GRID
                        },
                )
            }

        /** Collapses or expands the sidebar. */
        fun toggleSidebar() = store { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }

        private fun store(change: (Prefs) -> Prefs) {
            val current = state.value
            val next = change(Prefs(current.sort, current.viewMode, current.sidebarCollapsed))
            viewModelScope.launch { settings.write(PREFS_KEY, encode(next)) }
        }

        private data class Transient(
            val tab: LibraryTab = LibraryTab.ALL,
            val selectMode: Boolean = false,
        )

        @Serializable
        private data class Prefs(
            val sort: DocumentSort = DocumentSort.RECENT,
            val viewMode: LibraryViewMode = LibraryViewMode.GRID,
            val sidebarCollapsed: Boolean = false,
        )

        internal companion object {
            const val PREFS_KEY = "library.browse"

            /** Display name for the greeting; plain text. */
            const val NAME_KEY = "profile.name"
            private const val TAG = "LibraryBrowse"
            private val json =
                Json {
                    ignoreUnknownKeys = true
                    encodeDefaults = true
                }

            private fun encode(prefs: Prefs) = json.encodeToString(Prefs.serializer(), prefs)

            private fun decode(text: String?): Prefs =
                if (text == null) {
                    Prefs()
                } else {
                    try {
                        json.decodeFromString(Prefs.serializer(), text)
                    } catch (e: SerializationException) {
                        FolioLog.w(TAG, "stored library view choices unreadable, using defaults", e)
                        Prefs()
                    }
                }
        }
    }
