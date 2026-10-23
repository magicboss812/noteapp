package dev.folio.feature.library.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.Clock
import dev.folio.core.common.FolioLog
import dev.folio.core.storage.repo.DocumentSort
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.collections.immutable.PersistentSet
import kotlinx.collections.immutable.persistentSetOf
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
@Suppress("TooManyFunctions") // one small setter per browse choice; splitting would only move them
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
                LibraryBrowseState(
                    t.tab,
                    t.folder,
                    prefs.sort,
                    prefs.viewMode,
                    t.selectMode,
                    prefs.sidebarCollapsed,
                    name.orEmpty(),
                    t.selected,
                    t.tag,
                )
            }.stateIn(viewModelScope, SharingStarted.Eagerly, LibraryBrowseState())

        /** Current wall-clock time for the date line and card timestamps. */
        fun nowMs(): Long = clock.nowMs()

        /** Shows [tab]; leaves select mode because the selection belongs to the old list. */
        fun selectTab(tab: LibraryTab) = transient.update { it.copy(tab = tab, selectMode = false, selected = persistentSetOf()) }

        /** Shows the All tab at [folder] ("" = library root); the selection belongs to the old folder and is dropped. */
        fun openFolder(folder: String) =
            transient.update { it.copy(tab = LibraryTab.ALL, folder = folder, selectMode = false, selected = persistentSetOf()) }

        /** Filters the Tags tab by [tag]; choosing the active tag again, or null, shows every tagged note. */
        fun selectTag(tag: String?) =
            transient.update {
                it.copy(tag = if (tag == it.tag) null else tag, selectMode = false, selected = persistentSetOf())
            }

        /** Toggles select mode; leaving it drops the selection. */
        fun toggleSelectMode() = transient.update { it.copy(selectMode = !it.selectMode, selected = persistentSetOf()) }

        /** Ticks or unticks [path]; ticking outside select mode enters it (long press on a card). */
        fun toggleSelected(path: String) =
            transient.update {
                it.copy(selectMode = true, selected = if (path in it.selected) it.selected.remove(path) else it.selected.add(path))
            }

        /** Ticks every path of [paths] (the visible notes). */
        fun selectAll(paths: Collection<String>) = transient.update { it.copy(selected = it.selected.addAll(paths)) }

        /** Unticks everything and leaves select mode (after a batch action). */
        fun clearSelection() = transient.update { it.copy(selectMode = false, selected = persistentSetOf()) }

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
            val folder: String = "",
            val selectMode: Boolean = false,
            val selected: PersistentSet<String> = persistentSetOf(),
            val tag: String? = null,
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
