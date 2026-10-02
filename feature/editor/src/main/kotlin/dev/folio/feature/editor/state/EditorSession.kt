package dev.folio.feature.editor.state

import androidx.annotation.MainThread
import dev.folio.core.common.FolioDispatchers
import dev.folio.core.common.FolioLog
import dev.folio.core.common.Outcome
import dev.folio.core.ink.brush.BrushCatalog
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.Background
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageId
import dev.folio.core.model.TemplateKind
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.MovePages
import dev.folio.core.model.edit.PageOps
import dev.folio.core.model.edit.RemoveObjects
import dev.folio.core.render.template.TemplatePresets
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.render.viewport.pageIndexAt
import dev.folio.core.storage.session.DocumentSession
import dev.folio.feature.editor.canvas.CanvasCommand
import dev.folio.feature.editor.canvas.CanvasController
import dev.folio.feature.editor.canvas.CanvasTool
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.MainCoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
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
@Suppress("TooManyFunctions") // pane API: tool state, history, and one method per page operation of the page panel
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

    private val canvasCommands = MutableSharedFlow<CanvasCommand>(extraBufferCapacity = COMMAND_BUFFER)

    override val viewCommands: Flow<CanvasCommand> get() = canvasCommands

    /** Asks the canvas host to run [command]; dropped while no host is attached. */
    fun sendCanvasCommand(command: CanvasCommand) {
        canvasCommands.tryEmit(command)
    }

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

    private val pageJumpRequests = MutableSharedFlow<PageId>(extraBufferCapacity = COMMAND_BUFFER)

    override val pageJumps: Flow<PageId> get() = pageJumpRequests

    /** Scrolls the canvas to page [id] (dropped while no host is attached). */
    fun goToPage(id: PageId) {
        pageJumpRequests.tryEmit(id)
    }

    /**
     * Adds an empty page [side] of [anchor] with its spec and background (after a PDF page: the document's new-page
     * defaults), then shows it; the new id, or null if it failed.
     */
    suspend fun addPage(
        anchor: PageId,
        side: PageOps.Side,
    ): PageId? {
        val command = build { PageOps.blankNear(document.value, anchor, side, NEW_PAGE_BACKGROUND) }
        if (command == null || !execute(command)) return null
        val added = command.pages.single().id
        goToPage(added)
        return added
    }

    /** Copies [ids] after the last of them as one undo step; the id of the first copy, or null if it failed. */
    suspend fun duplicatePages(ids: Collection<PageId>): PageId? {
        if (!loaded(ids)) return null
        val command = build { PageOps.duplicate(document.value, ids) }
        if (command == null || !execute(command)) return null
        return command.pages.first().id
    }

    /** Deletes [ids] as one undo step; false if that would remove every page or it failed. */
    suspend fun deletePages(ids: Collection<PageId>): Boolean {
        if (!loaded(ids)) return false
        val command = build { PageOps.delete(document.value, ids) } ?: return false
        return execute(command)
    }

    /** Moves page [id] to position [toIndex] among the pages as one undo step. */
    suspend fun movePage(
        id: PageId,
        toIndex: Int,
    ): Boolean = execute(MovePages(listOf(id), toIndex))

    /** Applies [settings] chosen for [page] to [applyTo] as one undo step; false if nothing changed or it failed. */
    suspend fun applyPageSettings(
        settings: PageSettings,
        page: PageId,
        applyTo: ApplyTo,
    ): Boolean {
        if (!loaded(settings.pagesToLoad(document.value, page, applyTo))) return false
        val command = build { settings.commandFor(document.value, page, applyTo) } ?: return false
        return execute(command)
    }

    private suspend fun loaded(ids: Collection<PageId>): Boolean {
        val result = withContext(dispatchers.io) { documentSession.loadPages(ids) }
        if (result is Outcome.Failure) FolioLog.w(TAG, "loadPages: ${result.message}", result.cause)
        return result is Outcome.Success
    }

    // Builders reject unknown ids (a page deleted meanwhile) and pages evicted since loading.
    private inline fun <T : Any> build(block: () -> T?): T? =
        try {
            block()
        } catch (e: IllegalArgumentException) {
            FolioLog.w(TAG, "page edit rejected: ${e.message}")
            null
        } catch (e: IllegalStateException) {
            FolioLog.w(TAG, "page edit rejected: ${e.message}")
            null
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
        const val COMMAND_BUFFER = 16
        val BRUSH_VERSION = BrushCatalog.DEFAULT.latestVersion

        // Where a page added next to a PDF page starts: blank white A4 with the default lined template.
        val NEW_PAGE_BACKGROUND = Background(PaperColor.WHITE.argb, TemplatePresets.default(TemplateKind.LINED), null)
    }
}
