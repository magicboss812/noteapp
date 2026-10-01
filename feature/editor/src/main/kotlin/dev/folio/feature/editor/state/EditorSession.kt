package dev.folio.feature.editor.state

import androidx.annotation.MainThread
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.ink.brush.BrushCatalog
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.storage.session.DocumentSession
import dev.folio.feature.editor.canvas.CanvasController
import dev.folio.feature.editor.canvas.CanvasTool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * One editor pane (02-modules.md#editor-state): wraps the [documentSession] and adds the active tool,
 * its options and the pane's viewport. Commands, undo and redo run on the io dispatcher so the canvas
 * host's main-thread calls stay inside the `ink:commit` budget. [scope] (the ViewModel's) runs page loads.
 */
class EditorSession(
    /** The storage session of the open document; shared by both panes of a split view. */
    val documentSession: DocumentSession,
    density: Float,
    private val dispatchers: FolioDispatchers,
    private val scope: CoroutineScope,
    initialOptions: ToolOptions = ToolOptions(),
) : CanvasController {
    private val mutableTool = MutableStateFlow(EditorTool.PEN)
    private val mutableOptions = MutableStateFlow(initialOptions)

    /** The selected toolbar tool (chrome state; the canvas reads [activeTool] at stylus down). */
    val tool: StateFlow<EditorTool> = mutableTool.asStateFlow()

    /** Last options of every tool (chrome state; the canvas reads [activeBrush] and [eraserOptions]). */
    val options: StateFlow<ToolOptions> = mutableOptions.asStateFlow()

    override val document: StateFlow<Document> get() = documentSession.document
    override val viewport = Viewport(density)
    override val renderDispatcher: CoroutineDispatcher get() = dispatchers.render
    override val mainDispatcher: CoroutineDispatcher =
        (dispatchers.main as? MainCoroutineDispatcher)?.immediate ?: dispatchers.main

    // Built once per options change, not per stylus down.
    private var penBrush = initialOptions.pen.brush(BRUSH_VERSION)
    private var highlighterBrush = initialOptions.highlighter.brush(BRUSH_VERSION)

    override val activeBrush: BrushSpec
        get() = if (mutableTool.value == EditorTool.HIGHLIGHTER) highlighterBrush else penBrush

    // Unbuilt tools never become active (selectTool refuses them); PEN is the safe fallback.
    override val activeTool: CanvasTool get() = mutableTool.value.canvasTool ?: CanvasTool.PEN

    override val eraserOptions: EraserOptions get() = mutableOptions.value.eraser

    /** Whether undo has a step (chrome state). */
    val canUndo: StateFlow<Boolean> get() = documentSession.canUndo

    /** Whether redo has a step (chrome state). */
    val canRedo: StateFlow<Boolean> get() = documentSession.canRedo

    /** Selects [tool]; false (and no change) for a tool that is not built yet. */
    fun selectTool(tool: EditorTool): Boolean {
        if (!tool.available) return false
        mutableTool.value = tool
        return true
    }

    /** Applies [change] to the tool options; the next stroke uses them. */
    @MainThread
    fun updateOptions(change: (ToolOptions) -> ToolOptions) {
        val next = change(mutableOptions.value)
        penBrush = next.pen.brush(BRUSH_VERSION)
        highlighterBrush = next.highlighter.brush(BRUSH_VERSION)
        mutableOptions.value = next
    }

    /**
     * Id of the page the pane is on: the page at the vertical center of the view (the canvas page in
     * canvas mode); null before the first layout.
     */
    fun currentPageId(): PageId? {
        val index = viewport.pageIndexAt(viewport.viewHeightPx / 2f)
        return document.value.pages
            .getOrNull(index)
            ?.id
    }

    /**
     * Removes the ink of page [pageId] as one undo step (eraser "clear page"); with [highlighterOnly] only
     * highlighter strokes. False if there was nothing to remove or the command failed.
     */
    suspend fun clearPage(
        pageId: PageId,
        highlighterOnly: Boolean,
    ): Boolean {
        val loaded = withContext(dispatchers.io) { documentSession.loadPages(listOf(pageId)) }
        if (loaded is Outcome.Failure) return false
        val body = document.value.pageBodies[pageId] ?: return false
        val ids =
            body.objects
                .filter { it is InkStroke && (!highlighterOnly || it.brush.kind == BrushKind.HIGHLIGHTER) }
                .map { it.id }
        return ids.isNotEmpty() && execute(RemoveObjects(pageId, ids))
    }

    override fun loadPages(ids: Collection<PageId>) {
        scope.launch {
            val result = documentSession.loadPages(ids)
            if (result is Outcome.Failure) FolioLog.w(TAG, "loadPages: ${result.message}", result.cause)
        }
    }

    override suspend fun commitStrokes(
        pageId: PageId,
        strokes: List<InkStroke>,
    ): Boolean = execute(AddObjects(pageId, strokes))

    override suspend fun execute(command: EditCommand): Boolean =
        succeeded("execute ${command::class.simpleName}") { documentSession.execute(command) }

    /** Undoes the last step; false if there was none or it failed. */
    suspend fun undo(): Boolean = succeeded("undo") { documentSession.undo() }

    /** Redoes the last undone step; false if there was none or it failed. */
    suspend fun redo(): Boolean = succeeded("redo") { documentSession.redo() }

    // Off the main thread: the session lock, the command and the autosave scheduling (ink:commit budget).
    private suspend fun succeeded(
        what: String,
        step: suspend () -> Outcome<Unit>,
    ): Boolean {
        val result = withContext(dispatchers.io) { step() }
        if (result is Outcome.Failure) FolioLog.w(TAG, "$what: ${result.message}", result.cause)
        return result is Outcome.Success
    }

    private companion object {
        const val TAG = "EditorSession"
        val BRUSH_VERSION = BrushCatalog.DEFAULT.latestVersion
    }
}
