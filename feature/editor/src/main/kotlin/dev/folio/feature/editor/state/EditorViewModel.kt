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
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.updateAndGet
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
    val options: ToolOptions = ToolOptions(),
    val popover: OptionsPopover? = null,
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    /** Whether the shortcut help sheet is open. */
    val showHelp: Boolean = false,
    /** Toolbar placement per orientation (10-editor-ui.md#toolbar-docking); known before the document opens. */
    val docks: ToolbarDocks = ToolbarDocks(),
)

/** Popover or dialog opened from the tool options row (10-editor-ui.md#tool-options). */
sealed interface OptionsPopover {
    /** Pen settings: pressure curve and the selected width preset. */
    data object PenSettings : OptionsPopover

    /** Edits width preset [index] of [tool] (long-press on a width preset). */
    data class WidthEditor(
        val tool: EditorTool,
        val index: Int,
    ) : OptionsPopover

    /** Color picker for [tool]: edits dot [index] (long-press), or adds a color when null (the + button). */
    data class ColorPicker(
        val tool: EditorTool,
        val index: Int?,
    ) : OptionsPopover

    /** Confirmation before the eraser's "clear page". */
    data object ClearPage : OptionsPopover
}

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
        private val toolOptions: ToolOptionsStore,
        private val toolbarDocks: ToolbarDockStore,
    ) : ViewModel() {
        private val shortcuts = ShortcutRegistry()

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
        private val mutablePopover = MutableStateFlow<OptionsPopover?>(null)
        private val mutableHelp = MutableStateFlow(false)
        private val docks = MutableStateFlow(ToolbarDocks())

        // Stored one at a time in order; conflated, so only the latest of quick changes is written.
        private val dockSaves = Channel<ToolbarDocks>(Channel.CONFLATED)

        /** The open pane, null while opening or after a failure; the canvas host binds to it. */
        val session: StateFlow<EditorSession?> = mutableSession.asStateFlow()

        /** Chrome state. */
        val state: StateFlow<EditorUiState> =
            combine(mutableSession, failure, ::Pair)
                .flatMapLatest { (session, error) ->
                    when {
                        error != null -> {
                            flowOf(EditorUiState(EditorStatus.FAILED, error))
                        }

                        session == null -> {
                            flowOf(EditorUiState())
                        }

                        else -> {
                            val history = combine(session.canUndo, session.canRedo, ::Pair)
                            combine(
                                session.tool,
                                session.options,
                                mutablePopover,
                                history,
                                mutableHelp,
                            ) { tool, options, popover, h, help ->
                                EditorUiState(EditorStatus.READY, null, tool, options, popover, h.first, h.second, help)
                            }
                        }
                    }
                }.combine(docks) { state, docks -> state.copy(docks = docks) }
                .stateIn(viewModelScope, SharingStarted.Eagerly, EditorUiState())

        init {
            viewModelScope.launch {
                // Only replaces the defaults: a dock change made before the read finished wins.
                val stored = toolbarDocks.docks.first()
                docks.compareAndSet(ToolbarDocks(), stored)
            }
            viewModelScope.launch { for (changed in dockSaves) toolbarDocks.save(changed) }
            viewModelScope.launch {
                // Read while the document opens; defaults if nothing is stored.
                val stored = async { toolOptions.options.first() }
                when (val result = sessions.open(docPath)) {
                    is Outcome.Success -> {
                        val initial = stored.await()
                        val session = EditorSession(result.value, density, dispatchers, viewModelScope, initial)
                        mutableSession.value = session
                        // The session is the only writer while open; conflated so a slider drag stores its last value.
                        // Skips the loaded instance itself, not the first emission: an edit can land before this collects.
                        session.options
                            .dropWhile { it === initial }
                            .conflate()
                            .collect { toolOptions.save(it) }
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
            val session = mutableSession.value ?: return
            if (tool == session.tool.value && tool == EditorTool.PEN) {
                // Tapping the selected pen again opens its settings (10-editor-ui.md#toolbar).
                mutablePopover.value = OptionsPopover.PenSettings
            } else if (session.selectTool(tool)) {
                mutablePopover.value = null
            }
        }

        /** Applies [change] to the tool options (stored as they change). */
        fun updateOptions(change: (ToolOptions) -> ToolOptions) {
            mutableSession.value?.updateOptions(change)
        }

        /** Opens [popover] over the options row, or closes it (null). */
        fun showPopover(popover: OptionsPopover?) {
            mutablePopover.value = popover
        }

        /** Moves the toolbar of [orientation] to [placement] and stores it. */
        fun placeToolbar(
            orientation: ScreenOrientation,
            placement: ToolbarPlacement,
        ) {
            dockSaves.trySend(docks.updateAndGet { it.with(orientation, placement) })
        }

        /** Undoes the last step (toolbar button). */
        fun undo() {
            val session = mutableSession.value ?: return
            viewModelScope.launch { session.undo() }
        }

        /** Redoes the last undone step (toolbar button). */
        fun redo() {
            val session = mutableSession.value ?: return
            viewModelScope.launch { session.redo() }
        }

        /** Opens or closes the shortcut help sheet. */
        fun showHelp(show: Boolean) {
            mutableHelp.value = show
        }

        /**
         * Runs the shortcut for a key press; false if no shortcut is bound (or nothing to do for Esc), so
         * the key goes on to whoever else wants it. Needs the document open.
         */
        fun onKey(
            keyCode: Int,
            ctrl: Boolean,
            shift: Boolean,
            alt: Boolean,
        ): Boolean {
            val session = mutableSession.value ?: return false
            val action = shortcuts.actionFor(keyCode, ctrl, shift, alt) ?: return false
            return perform(session, action)
        }

        private fun perform(
            session: EditorSession,
            action: EditorAction,
        ): Boolean {
            when (action) {
                EditorAction.Undo -> {
                    undo()
                }

                EditorAction.Redo -> {
                    redo()
                }

                is EditorAction.SelectTool -> {
                    selectTool(action.tool)
                }

                is EditorAction.Canvas -> {
                    session.sendCanvasCommand(action.command)
                }

                EditorAction.ToggleHelp -> {
                    mutableHelp.value = !mutableHelp.value
                }

                EditorAction.Escape -> {
                    // Closes one layer per press: help first, then a popover.
                    return when {
                        mutableHelp.value -> {
                            mutableHelp.value = false
                            true
                        }

                        mutablePopover.value != null -> {
                            mutablePopover.value = null
                            true
                        }

                        else -> {
                            false
                        }
                    }
                }
            }
            return true
        }

        /** Removes the ink (or only the highlighter ink) of the current page as one undo step. */
        fun clearCurrentPage() {
            val session = mutableSession.value ?: return
            val page = session.currentPageId() ?: return
            viewModelScope.launch { session.clearPage(page, session.options.value.eraser.highlighterOnly) }
        }

        override fun onCleared() {
            mutableSession.value?.let { sessions.release(it.documentSession) }
        }

        private companion object {
            const val TAG = "EditorViewModel"
        }
    }
