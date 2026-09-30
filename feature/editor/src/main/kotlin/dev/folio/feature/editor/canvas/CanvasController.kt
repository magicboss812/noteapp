package dev.folio.feature.editor.canvas

import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.render.viewport.Viewport
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow

/** What the stylus tip does on the canvas (the toolbar picks it, P04). */
enum class CanvasTool {
    /** Draws ink with [CanvasController.activeBrush]. */
    PEN,

    /** Erases with [CanvasController.eraserOptions]. */
    ERASER,
}

/**
 * The only API the canvas host sees (02-modules.md#editor-state); implemented by EditorSession (P04).
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

    /** The open document; the host lays out its pages and tiles the loaded page bodies. */
    val document: StateFlow<Document>

    /** Pan and zoom state of this pane, owned by the session so it survives the view. */
    val viewport: Viewport

    /** Where tiles render (`FolioDispatchers.render`, 2 threads). */
    val renderDispatcher: CoroutineDispatcher

    /** Asks the session to decode [ids] (visible pages and neighbors); [document] updates when they arrive. */
    fun loadPages(ids: Collection<PageId>)
}
