package dev.folio.feature.library.state

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.storage.index.DocumentRow
import dev.folio.core.storage.index.LibraryScanner
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.repo.DocumentFilter
import dev.folio.core.storage.repo.LibraryRepository
import dev.folio.core.storage.work.Recovery
import dev.folio.core.storage.work.RecoveryEvents
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** One document in the placeholder list. */
@Immutable
data class LibraryDocItem(
    /** Library-relative path; opens the editor. */
    val path: String,
    val title: String,
    /** Containing folder ("" = root). */
    val folder: String,
    val pageCount: Int,
)

/**
 * Decides between storage onboarding and the library; re-checked on every resume. The first time the
 * library is usable, start-up recovery packs unsaved working copies (04-file-format.md#crash-recovery).
 * Until the P05 library screen, [documents] lists every indexed document, newest first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LibraryEntryViewModel
    @Inject
    constructor(
        private val access: LibraryAccess,
        private val recovery: Recovery,
        private val recoveryEvents: RecoveryEvents,
        private val dispatchers: FolioDispatchers,
        // Lazy: the index is only built once the library folder is usable.
        private val scanner: Lazy<LibraryScanner>,
        private val library: Lazy<LibraryRepository>,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<LibraryAccessState>(LibraryAccessState.Checking)
        private val mutableDocuments = MutableStateFlow<ImmutableList<LibraryDocItem>>(persistentListOf())
        private var listing: Job? = null

        /** Current access state. */
        val state: StateFlow<LibraryAccessState> = mutableState.asStateFlow()

        /** Indexed documents, newest first; empty until the library is ready. */
        val documents: StateFlow<ImmutableList<LibraryDocItem>> = mutableDocuments.asStateFlow()

        /** Snackbar for what start-up recovery did, null when nothing is pending. */
        val recoveryNotice: StateFlow<RecoveryNotice?> =
            recoveryEvents.pending
                .map(::recoveryNotice)
                .stateIn(viewModelScope, SharingStarted.Eagerly, null)

        /** [notice] was shown: forgets the events it reported; events that arrived meanwhile stay pending. */
        fun recoveryNoticeShown(notice: RecoveryNotice) {
            notice.events.forEach(recoveryEvents::consume)
        }

        /** Re-checks the permission (and prepares the root once granted), then rescans the library. */
        fun refresh() {
            viewModelScope.launch {
                val checked =
                    withContext(dispatchers.io) {
                        access.check().also { if (it is LibraryAccessState.Ready) recovery.runOnce() }
                    }
                mutableState.value = checked
                if (checked is LibraryAccessState.Ready) {
                    scanner.get().incrementalScan()
                    if (listing == null) listing = launch { allDocuments().collect { mutableDocuments.value = it } }
                }
            }
        }

        private fun allDocuments(): Flow<ImmutableList<LibraryDocItem>> {
            val repo = library.get()
            return repo.folderTree().flatMapLatest { folders ->
                val lists = (listOf(ROOT) + folders.map { it.path }).map { repo.documents(DocumentFilter.InFolder(it)) }
                combine(lists) { perFolder ->
                    perFolder
                        .flatMap { it }
                        .sortedByDescending { it.modifiedMs }
                        .map { it.toItem() }
                        .toImmutableList()
                }
            }
        }

        private fun DocumentRow.toItem() = LibraryDocItem(path, title, folderPath, pageCount)

        private companion object {
            const val ROOT = ""
        }
    }
