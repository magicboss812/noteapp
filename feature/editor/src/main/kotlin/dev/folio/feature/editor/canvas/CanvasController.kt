package dev.folio.feature.editor.canvas

import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.render.viewport.Viewport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow

/** What the stylus tip does on the canvas (the toolbar picks it, P04). */
enum class CanvasTool {
    /** Draws ink with [CanvasController.activeBrush]. */
    PEN,

    /** Erases with [CanvasController.eraserOptions]. */
    ERASER,
}

/** Viewport actions the editor asks of the canvas host (keyboard shortcuts, later page navigation UI). */
enum class CanvasCommand {
    ZOOM_IN,
    ZOOM_OUT,
    FIT_WIDTH,
    PREVIOUS_PAGE,
    NEXT_PAGE,
    FIRST_PAGE,
    LAST_PAGE,
}

/**
 * The only API the canvas host sees (02-modules.md#editor-state); implemented by `EditorSession`.
 * Later tasks add `hitTest`, `currentTool()` and `requestRender(bounds)`.
 */
interface CanvasController {
    /**
     * Adds finished pen [strokes] on top of page [pageId] as one undo step (`AddObjects`); [document]
     * shows them afterwards. False if the session rejected the command. Called on the main thread inside
     * the `ink:commit` budget (2 ms p95): implementations run the command off the main thread.
     */
    suspend fun commitStrokes(
        pageId: PageId,
        strokes: List<InkStroke>,
    ): Boolean

    /**
     * Runs [command] as one undo step (an erase gesture); [document] shows the result afterwards. False if
     * the session rejected it. Called on the main thread: implementations run the command off it.
     */
    suspend fun execute(command: EditCommand): Boolean

    /** Brush of the pen tool, read at every stylus down (tool state arrives with the toolbar, P04). */
    val activeBrush: BrushSpec

    /** Tool of the stylus tip, read at every stylus down; the pen's eraser end always erases. */
    val activeTool: CanvasTool

    /** Eraser settings, read at every eraser down. */
    val eraserOptions: EraserOptions

    /** Stylus choices (hover ring, pen button), read at every stylus down and hover event; settings arrive in P11-T01. */
    val stylusPreferences: StylusPreferences get() = StylusPreferences.DEFAULT

    /** The open document; the host lays out its pages and tiles the loaded page bodies. */
    val document: StateFlow<Document>

    /** Pan and zoom state of this pane, owned by the session so it survives the view. */
    val viewport: Viewport

    /** Where tiles render (`FolioDispatchers.render`, 2 threads). */
    val renderDispatcher: CoroutineDispatcher

    /** The main thread, immediate where possible: the host's own coroutines (commits, erase, tile results) run here. */
    val mainDispatcher: CoroutineDispatcher

    /** Viewport actions for the host to run; commands sent while no host collects are dropped. */
    val viewCommands: Flow<CanvasCommand> get() = emptyFlow()

    /** Pages the host scrolls to (page panel, add page); jumps sent while no host collects are dropped. */
    val pageJumps: Flow<PageId> get() = emptyFlow()

    /** Asks the session to decode [ids] (visible pages and neighbors); [document] updates when they arrive. */
    fun loadPages(ids: Collection<PageId>)
}
