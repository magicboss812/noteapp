package dev.folio.feature.editor.ui

import dev.folio.feature.editor.canvas.CanvasHostView
import dev.folio.feature.editor.state.EditorSession

/** Observes the editor's canvas (debug automation drives and inspects it). */
interface EditorCanvasListener {
    /** [host] shows [session]. */
    fun shown(
        session: EditorSession,
        host: CanvasHostView,
    )

    /** The canvas of [session] left the screen. */
    fun gone(session: EditorSession)
}
