package dev.folio.feature.editor.canvas

import dev.folio.core.model.Document
import dev.folio.core.render.viewport.Viewport
import kotlinx.coroutines.flow.StateFlow

/**
 * The only API the canvas host sees (02-modules.md#editor-state); implemented by EditorSession (P04).
 * Later tasks add `commit(strokes)`, `hitTest`, `currentTool()` and `requestRender(bounds)`.
 */
interface CanvasController {
    /** The open document; the host lays out its pages. */
    val document: StateFlow<Document>

    /** Pan and zoom state of this pane, owned by the session so it survives the view. */
    val viewport: Viewport
}
