package dev.folio.feature.editor.canvas

import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.PageObject
import dev.folio.core.model.ShapeKind
import dev.folio.core.model.StrokeStyle
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.edit.SelectionEdit
import dev.folio.core.model.edit.command
import dev.folio.core.model.lasso.LassoKind
import dev.folio.core.render.ImageSource
import dev.folio.core.render.viewport.Viewport
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.TextController
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import javax.inject.Inject
import javax.inject.Singleton

/** What the stylus tip does on the canvas (the toolbar picks it, P04). */
enum class CanvasTool {
    /** Draws ink with [CanvasController.activeBrush]. */
    PEN,

    /** Erases with [CanvasController.eraserOptions]. */
    ERASER,

    /** Pans and zooms like a finger, the eraser end too; no ink and no hover ring (read mode, 10-editor-ui.md#read-mode). */
    NAVIGATE,

    /** A tap places the text caret (10-editor-ui.md#text-tool); no ink. */
    TEXT,

    /** A drag draws [CanvasController.activeShape], polygons by taps (06-ink-input.md#shapes); no ink. */
    SHAPE,

    /** A drag draws a lasso that selects objects by [CanvasController.lassoSpec] (06-ink-input.md#lasso); no ink. */
    LASSO,

    /** A tap starts a sticky note with the caret in it, or edits the text there (A-068); no ink. */
    STICKY,
}

/** What the lasso draws ([rectangle] or freeform) and the object [kinds] it selects. */
data class LassoSpec(
    val rectangle: Boolean,
    val kinds: Set<LassoKind>,
) {
    /** Defaults. */
    companion object {
        /** Freeform, every kind. */
        val DEFAULT = LassoSpec(rectangle = false, kinds = LassoKind.entries.toSet())
    }
}

/**
 * The in-app clipboard of lasso copies (06-ink-input.md#lasso): one per process, so a paste works in another note.
 * [objects] keep the page positions they were copied at. Main thread.
 */
@Singleton
class ObjectClipboard
    @Inject
    constructor() {
        /** The copied objects; empty before the first copy. */
        var objects: List<PageObject> = emptyList()
            private set

        /** Copies [copied] (already with ids of their own) to the clipboard. */
        fun put(copied: List<PageObject>) {
            objects = copied
        }
    }

/** Objects [ids] of page [pageId] for the lasso to select. */
data class ObjectSelection(
    val pageId: PageId,
    val ids: List<ObjectId>,
)

/** What the Shape tool draws next: [kind] with [stroke], filled with [fillArgb] (null: none); [constrain] snaps angles and sides. */
data class ShapeSpec(
    val kind: ShapeKind,
    val stroke: StrokeStyle,
    val fillArgb: Int?,
    val constrain: Boolean,
) {
    /** Defaults. */
    companion object {
        /** A thin black rectangle without fill. */
        val DEFAULT = ShapeSpec(ShapeKind.RECTANGLE, StrokeStyle(0xFF1A1A1A.toInt(), 2f, dashed = false), null, constrain = false)
    }
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

    /** What the Shape tool draws, read at every stylus down with [CanvasTool.SHAPE]. */
    val activeShape: ShapeSpec get() = ShapeSpec.DEFAULT

    /** True while the highlighter's "always straight" option is on (D-024): its strokes are straight lines; read at every stylus down. */
    val alwaysStraight: Boolean get() = false

    /** What the lasso draws and selects, read at every stylus down with [CanvasTool.LASSO]. */
    val lassoSpec: LassoSpec get() = LassoSpec.DEFAULT

    /** The clipboard of lasso Cut and Copy. */
    val clipboard: ObjectClipboard

    /**
     * Runs lasso [edit] on objects [ids] of page [pageId] as one undo step; [document] shows the result afterwards.
     * False if the session rejected it. Called on the main thread: implementations run the command off it.
     */
    suspend fun editSelection(
        pageId: PageId,
        ids: List<ObjectId>,
        edit: SelectionEdit,
    ): Boolean = execute(edit.command(pageId, ids))

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

    /** Arrow keys for the selected box in Preview (P07-T14); keys sent while no host collects are dropped. */
    val boxKeys: Flow<BoxKeyEvent> get() = emptyFlow()

    /** Pages the host scrolls to (page panel, add page); jumps sent while no host collects are dropped. */
    val pageJumps: Flow<PageId> get() = emptyFlow()

    /** Whether this pane shows the ruler (06-ink-input.md#ruler; the toolbar toggle, off in read mode). */
    val rulerShown: Flow<Boolean> get() = emptyFlow()

    /** Objects the host selects with the lasso (an inserted image); requests while no host collects are dropped. */
    val selections: Flow<ObjectSelection> get() = emptyFlow()

    /** Decoded image assets of the document for tiles, snapshots and copies (render threads). */
    val images: ImageSource get() = ImageSource.None

    /** The lasso's Crop button on image [id] of page [pageId]: the editor opens the crop and turn screen (main thread). */
    fun editImage(
        pageId: PageId,
        id: ObjectId,
    ) = Unit

    /** The lasso's Open button on attachment [id] of page [pageId]: the editor opens the file in another app (A-070). */
    fun openAttachment(
        pageId: PageId,
        id: ObjectId,
    ) = Unit

    /**
     * What each page paints as text (A-042) and the live field drawing the focused block, which the pages leave out.
     * Computed off the main thread; empty without a text engine.
     */
    val canvasText: Flow<CanvasText> get() = emptyFlow()

    /** Focus and live field of the text blocks; null when the pane has no text engine (a tap on text then does nothing). */
    val textController: TextController? get() = null

    /** Asks the session to decode [ids] (visible pages and neighbors); [document] updates when they arrive. */
    fun loadPages(ids: Collection<PageId>)

    /** The [viewport] settled (once per gesture, main thread): the chrome's page indicator and zoom update from it. */
    fun viewportSettled() = Unit
}
