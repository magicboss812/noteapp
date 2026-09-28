package dev.folio.feature.library.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.storage.library.LibraryAccess
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.work.Recovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Decides between storage onboarding and the library; re-checked on every resume. The first time the
 * library is usable, start-up recovery packs unsaved working copies (04-file-format.md#crash-recovery).
 */
@HiltViewModel
class LibraryEntryViewModel
    @Inject
    constructor(
        private val access: LibraryAccess,
        private val recovery: Recovery,
        private val dispatchers: FolioDispatchers,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<LibraryAccessState>(LibraryAccessState.Checking)

        /** Current access state. */
        val state: StateFlow<LibraryAccessState> = mutableState.asStateFlow()

        /** Re-checks the permission (and prepares the root once granted). */
        fun refresh() {
            viewModelScope.launch {
                val checked =
                    withContext(dispatchers.io) {
                        access.check().also { if (it is LibraryAccessState.Ready) recovery.runOnce() }
                    }
                mutableState.value = checked
            }
        }
    }
