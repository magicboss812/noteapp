package dev.folio.feature.editor.canvas

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.ink.input.StylusPreferences
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.Document
import dev.folio.core.model.InkStroke
import dev.folio.core.model.PageId
import dev.folio.core.model.edit.AddObjects
import dev.folio.core.model.edit.EditCommand
import dev.folio.core.render.viewport.Viewport
import dev.folio.core.testing.ModelFixtures
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow

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
    val loadRequests = ArrayList<PageId>()
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
