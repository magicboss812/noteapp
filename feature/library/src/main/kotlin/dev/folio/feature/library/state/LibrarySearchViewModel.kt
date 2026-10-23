package dev.folio.feature.library.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.storage.repo.LibraryRepository
import dev.folio.core.storage.settings.SettingsStore
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * The search overlay (09-storage-library.md#search): query, folder and tag filters, instant results from the
 * index, and the last [RecentSearches.MAX] searches stored in the app settings. Nothing touches the index while
 * the overlay is closed or the query has no searchable character.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibrarySearchViewModel
    @Inject
    constructor(
        private val settings: SettingsStore,
        // Lazy: the index is only built once the library folder is usable.
        private val library: Lazy<LibraryRepository>,
    ) : ViewModel() {
        private val input = MutableStateFlow(Input())

        private val results =
            input
                .map { Triple(it.query, it.folder, it.tag) }
                .distinctUntilChanged()
                .flatMapLatest { (query, folder, tag) ->
                    if (isSearchable(query)) {
                        library.get().search(query, folder, tag).map { hits -> hits.map { it.toItem() }.toImmutableList() }
                    } else {
                        flowOf(persistentListOf())
                    }
                }

        private val tags =
            input
                .map { it.open }
                .distinctUntilChanged()
                .flatMapLatest { open -> if (open) library.get().tags() else flowOf(emptyList()) }

        /** Current overlay state. */
        val state: StateFlow<SearchUiState> =
            combine(input, results, settings.read(RECENT_KEY).map(RecentSearches::decode), tags) { i, found, recent, known ->
                SearchUiState(i.open, i.query, i.folder, i.tag, found, recent.toImmutableList(), known.toImmutableList())
            }.stateIn(viewModelScope, SharingStarted.Eagerly, SearchUiState())

        /** Opens the overlay with an empty query and no filters. */
        fun open() {
            input.value = Input(open = true)
        }

        /** Closes the overlay and forgets the query. */
        fun close() {
            input.value = Input()
        }

        /** Replaces the query text. */
        fun setQuery(query: String) = input.update { it.copy(query = query) }

        /** Limits the search to [folder] and its subfolders; null searches everywhere. */
        fun setFolder(folder: String?) = input.update { it.copy(folder = folder?.ifEmpty { null }) }

        /** Limits the search to notes with [tag]; null drops the limit. */
        fun setTag(tag: String?) = input.update { it.copy(tag = tag) }

        /** Fills the query from a recent search. */
        fun useRecent(query: String) = setQuery(query)

        /** Stores the current query as a recent search (search key pressed or a result opened). */
        fun commitQuery() {
            val query = input.value.query
            viewModelScope.launch {
                val recent = RecentSearches.decode(settings.read(RECENT_KEY).first())
                settings.write(RECENT_KEY, RecentSearches.encode(RecentSearches.push(recent, query)))
            }
        }

        /** Empties the recent searches. */
        fun clearRecent() {
            viewModelScope.launch { settings.write(RECENT_KEY, RecentSearches.encode(emptyList())) }
        }

        private data class Input(
            val open: Boolean = false,
            val query: String = "",
            val folder: String? = null,
            val tag: String? = null,
        )

        internal companion object {
            const val RECENT_KEY = "library.recentSearches"
        }
    }
