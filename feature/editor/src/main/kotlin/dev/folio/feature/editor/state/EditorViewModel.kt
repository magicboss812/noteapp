package dev.folio.feature.editor.state

import androidx.compose.runtime.Immutable
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.storage.session.DocumentSessions
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Lifecycle of the editor's document. */
enum class EditorStatus { OPENING, READY, FAILED }

/** Chrome state of the editor screen; never changes per pen point. */
@Immutable
data class EditorUiState(
    val status: EditorStatus = EditorStatus.OPENING,
    /** Why the document could not be opened ([EditorStatus.FAILED] only). */
    val error: String? = null,
    val tool: EditorTool = EditorTool.PEN,
)

/**
 * Editor screen state for the document at library path [docPath] (02-modules.md#editor-state). Opens the
 * document through [DocumentSessions] and wraps it in an [EditorSession] (one pane until split view,
 * P10); leaving the screen for good releases the session, which packs the document.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel(assistedFactory = EditorViewModel.Factory::class)
class EditorViewModel
    @AssistedInject
    constructor(
        @Assisted private val docPath: String,
        @Assisted private val density: Float,
        private val sessions: DocumentSessions,
        private val dispatchers: FolioDispatchers,
    ) : ViewModel() {
        /** Creates the ViewModel of one editor destination. */
        @AssistedFactory
        interface Factory {
            /** [density] sizes the pane's viewport (px per dp). */
            fun create(
                docPath: String,
                density: Float,
            ): EditorViewModel
        }

        private val mutableSession = MutableStateFlow<EditorSession?>(null)
        private val failure = MutableStateFlow<String?>(null)

        /** The open pane, null while opening or after a failure; the canvas host binds to it. */
        val session: StateFlow<EditorSession?> = mutableSession.asStateFlow()

        /** Chrome state. */
        val state: StateFlow<EditorUiState> =
            combine(mutableSession, failure, ::Pair)
                .flatMapLatest { (session, error) ->
                    when {
                        error != null -> flowOf(EditorUiState(EditorStatus.FAILED, error))
                        session == null -> flowOf(EditorUiState())
                        else -> session.tool.map { EditorUiState(EditorStatus.READY, tool = it) }
                    }
                }.stateIn(viewModelScope, SharingStarted.Eagerly, EditorUiState())

        init {
            viewModelScope.launch {
                when (val result = sessions.open(docPath)) {
                    is Outcome.Success -> {
                        mutableSession.value = EditorSession(result.value, density, dispatchers, viewModelScope)
                    }

                    is Outcome.Failure -> {
                        FolioLog.w(TAG, "open $docPath: ${result.message}", result.cause)
                        failure.value = result.message
                    }
                }
            }
        }

        /** Selects [tool] if it is built. */
        fun selectTool(tool: EditorTool) {
            mutableSession.value?.selectTool(tool)
        }

        override fun onCleared() {
            mutableSession.value?.let { sessions.release(it.documentSession) }
        }

        private companion object {
            const val TAG = "EditorViewModel"
        }
    }
