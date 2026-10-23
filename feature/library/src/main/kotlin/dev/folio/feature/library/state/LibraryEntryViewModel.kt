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
import dev.folio.core.storage.work.BinMaintenance
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

/** One document in the library lists. */
@Immutable
data class LibraryDocItem(
    /** Library-relative path; opens the editor. */
    val path: String,
    val title: String,
    /** Containing folder ("" = root). */
    val folder: String,
    val pageCount: Int,
    val modifiedMs: Long = 0L,
    val createdMs: Long = 0L,
    val favorite: Boolean = false,
    /** First lines of the note, from the manifest; the card preview when there is no cover. */
    val textPreview: String = "",
    /** The file stores a `thumbs/cover.webp`. */
    val hasCover: Boolean = false,
    /** Tags from the manifest, sorted. */
    val tags: ImmutableList<String> = persistentListOf(),
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
        private val binMaintenance: Lazy<BinMaintenance>,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<LibraryAccessState>(LibraryAccessState.Checking)
        private val mutableDocuments = MutableStateFlow<ImmutableList<LibraryDocItem>>(persistentListOf())
        private val mutableFolders = MutableStateFlow<ImmutableList<LibraryFolderItem>>(persistentListOf())
        private var listing: Job? = null
        private var binMaintained = false

        /** Folders for the sidebar tree; empty until the library is ready. */
        val folders: StateFlow<ImmutableList<LibraryFolderItem>> = mutableFolders.asStateFlow()

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
                    if (!binMaintained) {
                        binMaintained = true
                        launch { binMaintenance.get().runAtStart() }
                    }
                    scanner.get().incrementalScan()
                    if (listing == null) {
                        listing = launch { allDocuments().collect { mutableDocuments.value = it } }
                        launch {
                            library.get().folderTree().collect { rows ->
                                mutableFolders.value =
                                    folderItems(rows.map { it.path }, rows.associate { it.path to it.tint })
                            }
                        }
                    }
                }
            }
        }

        private fun allDocuments(): Flow<ImmutableList<LibraryDocItem>> {
            val repo = library.get()
            val perFolder =
                repo.folderTree().flatMapLatest { folders ->
                    val lists = (listOf(ROOT) + folders.map { it.path }).map { repo.documents(DocumentFilter.InFolder(it)) }
                    combine(lists) { rows -> rows.flatMap { it }.sortedByDescending { it.modifiedMs } }
                }
            return combine(perFolder, repo.tagAssignments()) { rows, tags ->
                rows.map { it.toItem(tags[it.path].orEmpty()) }.toImmutableList()
            }
        }

        private fun DocumentRow.toItem(tags: List<String>) =
            LibraryDocItem(
                path,
                title,
                folderPath,
                pageCount,
                modifiedMs,
                createdMs,
                favorite,
                textPreview,
                coverThumbPath != null,
                tags.toImmutableList(),
            )

        private companion object {
            const val ROOT = ""
        }
    }
