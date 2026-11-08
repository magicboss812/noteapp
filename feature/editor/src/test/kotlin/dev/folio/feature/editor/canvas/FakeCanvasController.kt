package dev.folio.feature.editor.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.FlowId
import dev.folio.core.model.InkStroke
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.testing.ModelFixtures
import dev.folio.core.text.edit.TableCell
import dev.folio.core.text.flow.EditedBlock
import dev.folio.feature.editor.text.CanvasText
import dev.folio.feature.editor.text.FieldEdit
import dev.folio.feature.editor.text.FormatAction
import dev.folio.feature.editor.text.TextController
import dev.folio.feature.editor.text.TextFieldSpec
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

/** Text controller that records the taps, moves and edits it gets; [focusResult] is what every tap returns, [field] what is shown. */
internal class FakeTextController : TextController {
    override val field = MutableStateFlow<TextFieldSpec?>(null)
    var focusResult = true
    val focusCalls = ArrayList<Triple<PageId, Float, Float>>()
    val moveCalls = ArrayList<Triple<PageId, Float, Float>>()
    val edits = ArrayList<FieldEdit>()
    val formats = ArrayList<FormatAction>()
    var blurCount = 0

    /** What [retype] returns; the sources it got are in [retypeCalls]. */
    var retyped: EditedBlock? = null
    val retypeCalls = ArrayList<String>()

    override fun retype(source: String): EditedBlock? {
        retypeCalls += source
        return retyped
    }

    override fun focusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        slopPt: Float,
    ): Boolean {
        focusCalls += Triple(pageId, xPt, yPt)
        return focusResult
    }

    override fun moveFocusAt(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ): Boolean {
        moveCalls += Triple(pageId, xPt, yPt)
        return focusResult
    }

    override fun blur() {
        blurCount++
    }

    override fun edit(edit: FieldEdit) {
        edits += edit
    }

    val selections = ArrayList<Triple<Int, Int, TableCell?>>()

    val extending = ArrayList<Boolean>()

    override fun select(
        start: Int,
        end: Int,
        cell: TableCell?,
        extending: Boolean,
    ) {
        selections += Triple(start, end, cell)
        this.extending += extending
    }

    override fun format(action: FormatAction) {
        formats += action
    }

    val extends = ArrayList<String>()

    override fun extendHorizontally(forward: Boolean) {
        extends += if (forward) "right" else "left"
    }

    override fun extendTo(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) {
        extends += "to $yPt"
    }

    override fun dragSelection(
        anchor: Boolean,
        pageId: PageId,
        xPt: Float,
        yPt: Float,
    ) {
        extends += "${if (anchor) "anchor" else "caret"} $xPt,$yPt"
    }

    var selectAllCount = 0

    override fun selectAll() {
        selectAllCount++
    }

    /** What [copySelection] and [cutSelection] return; cuts are counted. */
    var copied: String? = null
    var cutCount = 0

    override fun copySelection(): String? = copied

    override fun cutSelection(): String? = copied.also { cutCount++ }

    /** Boxes placed by a handle drag: frame, from page, to page, rect. */
    val placedBoxes = ArrayList<PlacedBox>()

    /** What a box placement or move reports to its caller (false: refused). */
    var placeResult = true

    override fun placeBox(
        frameId: ObjectId,
        fromPage: PageId,
        toPage: PageId,
        rect: RectPt,
        done: (Boolean) -> Unit,
    ) {
        placedBoxes += PlacedBox(frameId, fromPage, toPage, rect)
        done(placeResult)
    }

    /** Boxes moved by a drag: flow, to page, rect. */
    val movedBoxes = ArrayList<Triple<FlowId, PageId, RectPt>>()

    override fun moveBox(
        flowId: FlowId,
        toPage: PageId,
        rect: RectPt,
        done: (Boolean) -> Unit,
    ) {
        movedBoxes += Triple(flowId, toPage, rect)
        done(placeResult)
    }

    /** Taps that reached the boxes: page, x, y and whether the Text tool made them. */
    val boxTaps = ArrayList<Pair<Triple<PageId, Float, Float>, Boolean>>()

    // A tap is also counted as a focus call: the canvas tests check that taps reach the text; which mode a tap gives is
    // the session's (BoxSelectionTest).
    override fun tapBox(
        pageId: PageId,
        xPt: Float,
        yPt: Float,
        slopPt: Float,
        textTool: Boolean,
    ) {
        boxTaps += Triple(pageId, xPt, yPt) to textTool
        focusAt(pageId, xPt, yPt, slopPt)
    }

    var editBoxCount = 0
    var deleteBoxCount = 0

    override fun editBox() {
        editBoxCount++
    }

    override fun deleteBox() {
        deleteBoxCount++
    }

    data class PlacedBox(
        val frameId: ObjectId,
        val fromPage: PageId,
        val toPage: PageId,
        val rect: RectPt,
    )
}

/** Controller over an in-memory document of [pages] lined A4 pages; commands apply synchronously. */
internal class FakeCanvasController(
    pages: Int,
) : CanvasController {
    private val density =
        ApplicationProvider
            .getApplicationContext<Context>()
            .resources.displayMetrics.density
    override val document = MutableStateFlow<Document>(ModelFixtures.document(List(pages) { ModelFixtures.page("p$it") }))
    override val viewport = Viewport(density)
    override val activeBrush = BrushSpec(BrushKind.BALLPOINT, 0xFF1A1A1A.toInt(), 0.9f, 1)

    // Tiles render inline on the main thread, so a frame after the idle request shows them.
    override val renderDispatcher = Dispatchers.Unconfined
    override val mainDispatcher = Dispatchers.Main.immediate
    override var activeTool = CanvasTool.PEN
    override var eraserOptions = EraserOptions.DEFAULT
    override var stylusPreferences = StylusPreferences.DEFAULT
    override var textController: TextController? = null
    override val canvasText = MutableStateFlow(CanvasText.EMPTY)
    val loadRequests = ArrayList<PageId>()

    /** The thread of every [loadPages] call (the view asks while applying a document, so it must be main). */
    val loadThreads = ArrayList<Thread>()
    var accept = true

    /** When set, commits suspend until it completes (a session working off the main thread). */
    var commitGate: CompletableDeferred<Unit>? = null

    val commands = ArrayList<EditCommand>()

    override suspend fun execute(command: EditCommand): Boolean {
        commands += command
        if (accept) document.value = command.execute(document.value).doc
        return accept
    }

    override fun loadPages(ids: Collection<PageId>) {
        loadRequests += ids
        loadThreads += Thread.currentThread()
    }

    override suspend fun commitStrokes(
        pageId: PageId,
        strokes: List<InkStroke>,
    ): Boolean {
        commitGate?.await()
        if (accept) document.value = AddObjects(pageId, strokes).execute(document.value).doc
        return accept
    }
}
