package dev.folio.feature.editor.state

import android.view.KeyEvent
import dev.folio.feature.editor.canvas.CanvasCommand

/** A key with exactly the modifiers held (keyCode is an `android.view.KeyEvent.KEYCODE_*`). */
data class KeyChord(
    val keyCode: Int,
    val ctrl: Boolean = false,
    val shift: Boolean = false,
    val alt: Boolean = false,
)

/** What a shortcut does; the view model performs it (canvas actions go to the pane's canvas). */
sealed interface EditorAction {
    /** Undoes the last step. */
    data object Undo : EditorAction

    /** Redoes the last undone step. */
    data object Redo : EditorAction

    /** Selects [tool] (ignored while it is not built). */
    data class SelectTool(
        val tool: EditorTool,
    ) : EditorAction

    /** Canvas pan and zoom actions. */
    data class Canvas(
        val command: CanvasCommand,
    ) : EditorAction

    /** Closes the help sheet, else the open popover. */
    data object Escape : EditorAction

    /** Opens or closes the shortcut help sheet. */
    data object ToggleHelp : EditorAction
}

/** One row of the help sheet: the [keys] as shown, what they do, and the chords that trigger it. */
class ShortcutGroup(
    val keys: String,
    val description: String,
    val bindings: Map<KeyChord, EditorAction>,
)

/**
 * Hardware keyboard shortcuts of the editor (10-editor-ui.md#keyboard-shortcuts); the single place keys
 * are bound. Only shortcuts of built features are listed: later tasks add their groups here.
 */
class ShortcutRegistry(
    val groups: List<ShortcutGroup> = DEFAULT_GROUPS,
) {
    private val table: Map<KeyChord, EditorAction>

    init {
        val merged = HashMap<KeyChord, EditorAction>()
        for (group in groups) {
            for ((chord, action) in group.bindings) {
                require(merged.put(chord, action) == null) { "duplicate shortcut $chord" }
            }
        }
        table = merged
    }

    /** The action bound to [chord], null if none. */
    fun actionFor(chord: KeyChord): EditorAction? = table[chord]

    /** The action for a key press given as raw key code and modifier state. */
    fun actionFor(
        keyCode: Int,
        ctrl: Boolean,
        shift: Boolean,
        alt: Boolean,
    ): EditorAction? = table[KeyChord(keyCode, ctrl, shift, alt)]

    companion object {
        // Alt+1..9, Alt+0 follow the toolbar order; Alt+9 is the Ruler toggle (P07).
        private val TOOL_KEYS =
            listOf(
                KeyEvent.KEYCODE_1 to EditorTool.PEN,
                KeyEvent.KEYCODE_2 to EditorTool.HIGHLIGHTER,
                KeyEvent.KEYCODE_3 to EditorTool.ERASER,
                KeyEvent.KEYCODE_4 to EditorTool.LASSO,
                KeyEvent.KEYCODE_5 to EditorTool.TEXT,
                KeyEvent.KEYCODE_6 to EditorTool.SHAPE,
                KeyEvent.KEYCODE_7 to EditorTool.IMAGE,
                KeyEvent.KEYCODE_8 to EditorTool.STICKY,
                KeyEvent.KEYCODE_0 to EditorTool.TABLE,
            )

        private fun ctrl(
            keyCode: Int,
            shift: Boolean = false,
        ) = KeyChord(keyCode, ctrl = true, shift = shift)

        private fun canvas(command: CanvasCommand) = EditorAction.Canvas(command)

        private fun zoomIn() =
            listOf(KeyEvent.KEYCODE_EQUALS, KeyEvent.KEYCODE_PLUS, KeyEvent.KEYCODE_NUMPAD_ADD).flatMap {
                listOf(ctrl(it), ctrl(it, shift = true))
            }

        /** The built-in bindings. */
        val DEFAULT_GROUPS: List<ShortcutGroup> =
            listOf(
                ShortcutGroup(
                    "Ctrl+Z",
                    "Undo",
                    mapOf(ctrl(KeyEvent.KEYCODE_Z) to EditorAction.Undo),
                ),
                ShortcutGroup(
                    "Ctrl+Shift+Z, Ctrl+Y",
                    "Redo",
                    mapOf(
                        ctrl(KeyEvent.KEYCODE_Z, shift = true) to EditorAction.Redo,
                        ctrl(KeyEvent.KEYCODE_Y) to EditorAction.Redo,
                    ),
                ),
                ShortcutGroup(
                    "Alt+1..8, Alt+0",
                    "Pen, Highlighter, Eraser, Lasso, Text, Shape, Image, Sticky note, Table",
                    TOOL_KEYS.associate { (key, tool) -> KeyChord(key, alt = true) to EditorAction.SelectTool(tool) },
                ),
                ShortcutGroup(
                    "Ctrl+Plus",
                    "Zoom in",
                    zoomIn().associateWith { canvas(CanvasCommand.ZOOM_IN) },
                ),
                ShortcutGroup(
                    "Ctrl+Minus",
                    "Zoom out",
                    listOf(KeyEvent.KEYCODE_MINUS, KeyEvent.KEYCODE_NUMPAD_SUBTRACT)
                        .associate { ctrl(it) to canvas(CanvasCommand.ZOOM_OUT) },
                ),
                ShortcutGroup(
                    "Ctrl+0",
                    "Fit page width",
                    listOf(KeyEvent.KEYCODE_0, KeyEvent.KEYCODE_NUMPAD_0)
                        .associate { ctrl(it) to canvas(CanvasCommand.FIT_WIDTH) },
                ),
                ShortcutGroup(
                    "PageUp, PageDown",
                    "Previous page, next page",
                    mapOf(
                        KeyChord(KeyEvent.KEYCODE_PAGE_UP) to canvas(CanvasCommand.PREVIOUS_PAGE),
                        KeyChord(KeyEvent.KEYCODE_PAGE_DOWN) to canvas(CanvasCommand.NEXT_PAGE),
                    ),
                ),
                ShortcutGroup(
                    "Ctrl+Home, Ctrl+End",
                    "First page, last page",
                    mapOf(
                        ctrl(KeyEvent.KEYCODE_MOVE_HOME) to canvas(CanvasCommand.FIRST_PAGE),
                        ctrl(KeyEvent.KEYCODE_MOVE_END) to canvas(CanvasCommand.LAST_PAGE),
                    ),
                ),
                ShortcutGroup(
                    "Esc",
                    "Close sheet or popover",
                    mapOf(KeyChord(KeyEvent.KEYCODE_ESCAPE) to EditorAction.Escape),
                ),
                ShortcutGroup(
                    "Ctrl+/",
                    "Shortcut help",
                    mapOf(ctrl(KeyEvent.KEYCODE_SLASH) to EditorAction.ToggleHelp),
                ),
            )
    }
}
