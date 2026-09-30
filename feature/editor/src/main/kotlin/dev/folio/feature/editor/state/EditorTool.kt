package dev.folio.feature.editor.state

import dev.folio.feature.editor.canvas.CanvasTool

/**
 * Tools of the toolbar tools pill in order (10-editor-ui.md#toolbar; the Ruler is a toggle, not a tool).
 * [canvasTool] is what the stylus tip does with the tool selected; null while the tool is not built yet.
 */
enum class EditorTool(
    val canvasTool: CanvasTool?,
) {
    LASSO(null),
    PEN(CanvasTool.PEN),
    HIGHLIGHTER(CanvasTool.PEN),
    ERASER(CanvasTool.ERASER),
    SHAPE(null),
    TEXT(null),
    TABLE(null),
    IMAGE(null),
    STICKY(null),
    ATTACHMENT(null),
    ;

    /** False while the tool is not built yet: the toolbar shows it disabled. */
    val available: Boolean get() = canvasTool != null
}
