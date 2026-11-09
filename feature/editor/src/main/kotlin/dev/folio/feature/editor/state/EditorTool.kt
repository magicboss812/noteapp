package dev.folio.feature.editor.state

import dev.folio.feature.editor.canvas.CanvasTool

/**
 * Tools of the toolbar tools pill in order (10-editor-ui.md#toolbar; the Ruler is a toggle, not a tool).
 * [canvasTool] is what the stylus tip does with the tool selected; null while the tool is not built yet.
 */
enum class EditorTool(
    val canvasTool: CanvasTool?,
) {
    LASSO(CanvasTool.LASSO),
    PEN(CanvasTool.PEN),
    HIGHLIGHTER(CanvasTool.PEN),
    ERASER(CanvasTool.ERASER),
    SHAPE(CanvasTool.SHAPE),
    TEXT(CanvasTool.TEXT),
    TABLE(null),

    // Gallery and Camera in the options row insert; the pen then selects images like the lasso (P07-T04).
    IMAGE(CanvasTool.LASSO),
    STICKY(CanvasTool.STICKY),

    // "Attach file" in the options row inserts; the pen then selects attachments and images like the Image tool (A-070).
    ATTACHMENT(CanvasTool.LASSO),
    ;

    /** False while the tool is not built yet: the toolbar shows it disabled. */
    val available: Boolean get() = canvasTool != null
}

/** Whether [this] tool has a tool settings panel (10-editor-ui.md#tool-options): tapping it again when selected opens it. */
val EditorTool.hasSettings: Boolean
    get() = this in WITH_SETTINGS

private val WITH_SETTINGS =
    setOf(EditorTool.PEN, EditorTool.HIGHLIGHTER, EditorTool.ERASER, EditorTool.LASSO, EditorTool.TEXT, EditorTool.SHAPE)
