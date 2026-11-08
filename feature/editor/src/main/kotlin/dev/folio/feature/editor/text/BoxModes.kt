package dev.folio.feature.editor.text

import dev.folio.core.model.FlowId

/**
 * What the text boxes show (10-editor-ui.md#text-boxes, Modes): no box selected, one box in Preview (outline, grip,
 * toolbar; no keyboard, caret or text selection, a drag moves it) or one box in Edit (the same chrome with the live
 * field, keyboard and caret).
 */
sealed interface BoxMode {
    /** The selected box, null for [None]. */
    val flowId: FlowId?

    /** No box is selected. */
    data object None : BoxMode {
        override val flowId: FlowId? get() = null
    }

    /** Box [flowId] is selected; its text is not edited. */
    data class Preview(
        override val flowId: FlowId,
    ) : BoxMode

    /** Box [flowId] is selected and edited. */
    data class Edit(
        override val flowId: FlowId,
    ) : BoxMode
}

/** What moves the box mode on ([BoxModes.next]). */
sealed interface BoxEvent {
    /** A tap on box [flowId] (Text tool with pen or finger; a finger with another tool outside read mode). */
    data class Tap(
        val flowId: FlowId,
    ) : BoxEvent

    /** A tap outside every box with a tool that makes no box there. */
    data object TapOutside : BoxEvent

    /** The caret went into box [flowId]: a Text tool tap made it, or typing with nothing selected went there. */
    data class Caret(
        val flowId: FlowId,
    ) : BoxEvent

    /** The toolbar's Edit button. */
    data object EditButton : BoxEvent

    /** The system Back gesture or Escape. */
    data object Back : BoxEvent

    /** A pen with an ink tool over the box being edited, or on its grip or knobs (A-045: HyperOS takes a focused field). */
    data object PenOver : BoxEvent

    /** The selected box was deleted or is gone (an undo took it). */
    data object Gone : BoxEvent
}

/** The transitions of the box modes (10-editor-ui.md#text-boxes, Modes). */
object BoxModes {
    /** The mode after [event] in [mode]. */
    fun next(
        mode: BoxMode,
        event: BoxEvent,
    ): BoxMode =
        when (event) {
            // A tap on the box being edited places the caret there; a tap on any other box selects it in Preview.
            is BoxEvent.Tap -> if (mode is BoxMode.Edit && mode.flowId == event.flowId) mode else BoxMode.Preview(event.flowId)

            BoxEvent.TapOutside, BoxEvent.Gone -> BoxMode.None

            is BoxEvent.Caret -> BoxMode.Edit(event.flowId)

            BoxEvent.EditButton -> toggled(mode)

            BoxEvent.Back -> back(mode)

            BoxEvent.PenOver -> if (mode is BoxMode.Edit) BoxMode.Preview(mode.flowId) else mode
        }

    /** True when Back or Escape has something to step back from in [mode] (else it goes to the screen). */
    fun takesBack(mode: BoxMode): Boolean = mode != BoxMode.None

    private fun toggled(mode: BoxMode): BoxMode =
        when (mode) {
            BoxMode.None -> mode
            is BoxMode.Preview -> BoxMode.Edit(mode.flowId)
            is BoxMode.Edit -> BoxMode.Preview(mode.flowId)
        }

    private fun back(mode: BoxMode): BoxMode =
        when (mode) {
            BoxMode.None, is BoxMode.Preview -> BoxMode.None
            is BoxMode.Edit -> BoxMode.Preview(mode.flowId)
        }
}
