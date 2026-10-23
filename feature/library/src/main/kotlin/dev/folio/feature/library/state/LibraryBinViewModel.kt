package dev.folio.feature.library.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.Clock
import dev.folio.core.storage.repo.BinRetention
import dev.folio.core.storage.repo.BinSettings
import dev.folio.core.storage.repo.DocumentRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** The Bin tab: deleted items with days left, restore, delete forever, empty bin and the retention choice. */
@HiltViewModel
class LibraryBinViewModel
    @Inject
    constructor(
        documents: Lazy<DocumentRepository>,
        private val binSettings: BinSettings,
        private val clock: Clock,
    ) : ViewModel() {
        private val repo by lazy { documents.get() }
        private val ops by lazy { LibraryBinOps(repo) }
        private val mutableMessages = Channel<String>(Channel.BUFFERED)

        /** Snackbar texts, one per finished action. */
        val messages: Flow<String> = mutableMessages.receiveAsFlow()

        /** Bin contents newest first; empty until [refresh] ran once the library is usable. */
        val state: StateFlow<LibraryBinState> =
            combine(
                flow { emitAll(repo.bin) }, // lazy: the repository is only built once the library is usable
                binSettings.retention,
            ) { entries, retention -> LibraryBinState(binItems(entries, retention, clock.nowMs()), retention) }
                .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), LibraryBinState())

        /** Re-reads the bin folder (tab opened, app resumed). */
        fun refresh() {
            viewModelScope.launch { repo.refreshBin() }
        }

        fun restore(item: LibraryBinItem) = run { ops.restore(item) }

        fun deleteForever(item: LibraryBinItem) = run { ops.deleteForever(item) }

        fun emptyBin() = run { ops.emptyBin() }

        fun setRetention(retention: BinRetention) {
            viewModelScope.launch { binSettings.set(retention) }
        }

        private fun run(action: suspend () -> String?) {
            viewModelScope.launch { action()?.let { mutableMessages.send(it) } }
        }

        private companion object {
            const val STOP_TIMEOUT_MS = 5_000L
        }
    }
