package dev.folio.feature.library.state

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.Lazy
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.Clock
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.storage.repo.DocumentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** State of the new-note sheet: hidden, or open with a [form] while [creating] says a create is running. */
sealed interface NewNoteState {
    /** No sheet. */
    data object Hidden : NewNoteState

    /** Sheet open; [error] is the message of a failed create. */
    data class Editing(
        val form: NewNoteForm,
        val creating: Boolean = false,
        val error: String? = null,
    ) : NewNoteState
}

/**
 * The new-note flow (10-editor-ui.md#new-note): opens the sheet with the last choices, creates the document in
 * [folder] and hands its path to the caller, and remembers the choices as the next defaults.
 */
@HiltViewModel
class NewNoteViewModel
    @Inject
    constructor(
        private val documents: Lazy<DocumentRepository>, // lazy: the library folder is only usable after onboarding
        private val defaults: NewNoteDefaultsStore,
        private val clock: Clock,
    ) : ViewModel() {
        private val mutableState = MutableStateFlow<NewNoteState>(NewNoteState.Hidden)

        /** The sheet state. */
        val state: StateFlow<NewNoteState> = mutableState.asStateFlow()

        /** Opens the sheet with the title "Untitled <today>" and the last choices. */
        fun show() {
            if (mutableState.value is NewNoteState.Editing) return
            viewModelScope.launch {
                val form = defaults.load(NewNoteForm.defaultTitle(clock.nowMs()))
                if (mutableState.value is NewNoteState.Hidden) mutableState.value = NewNoteState.Editing(form)
            }
        }

        /** Replaces the form while the sheet is open. */
        fun update(form: NewNoteForm) {
            val current = mutableState.value as? NewNoteState.Editing ?: return
            if (!current.creating) mutableState.value = current.copy(form = form, error = null)
        }

        /** Closes the sheet without creating anything. */
        fun dismiss() {
            if ((mutableState.value as? NewNoteState.Editing)?.creating != true) mutableState.value = NewNoteState.Hidden
        }

        /** Creates the note in [folder] and calls [onCreated] with its library path; a failure stays on the sheet. */
        fun create(
            folder: String,
            onCreated: (String) -> Unit,
        ) {
            val current = mutableState.value as? NewNoteState.Editing ?: return
            if (current.creating) return
            mutableState.value = current.copy(creating = true, error = null)
            viewModelScope.launch {
                val form = current.form
                val spec = form.toSpec(folder, NewNoteForm.defaultTitle(clock.nowMs()))
                when (val result = documents.get().create(spec)) {
                    is Outcome.Success -> {
                        defaults.save(form)
                        mutableState.value = NewNoteState.Hidden
                        onCreated(result.value.path)
                    }

                    is Outcome.Failure -> {
                        FolioLog.w(TAG, result.message, result.cause)
                        mutableState.value = current.copy(creating = false, error = "The note could not be created: ${result.message}")
                    }
                }
            }
        }

        private companion object {
            const val TAG = "NewNote"
        }
    }
