package dev.folio.feature.editor.state

import android.view.KeyEvent
import com.google.common.truth.Truth.assertThat
import dev.folio.feature.editor.canvas.CanvasCommand
import org.junit.Assert.assertThrows
import org.junit.Test

class ShortcutRegistryTest {
    private val registry = ShortcutRegistry()

    private fun action(
        keyCode: Int,
        ctrl: Boolean = false,
        shift: Boolean = false,
        alt: Boolean = false,
    ) = registry.actionFor(keyCode, ctrl, shift, alt)

    @Test
    fun undoRedo_ctrlZ_ctrlShiftZ_ctrlY() {
        assertThat(action(KeyEvent.KEYCODE_Z, ctrl = true)).isEqualTo(EditorAction.Undo)
        assertThat(action(KeyEvent.KEYCODE_Z, ctrl = true, shift = true)).isEqualTo(EditorAction.Redo)
        assertThat(action(KeyEvent.KEYCODE_Y, ctrl = true)).isEqualTo(EditorAction.Redo)
    }

    @Test
    fun tools_altDigitsFollowToolbarOrder() {
        val expected =
            mapOf(
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
        expected.forEach { (key, tool) -> assertThat(action(key, alt = true)).isEqualTo(EditorAction.SelectTool(tool)) }
        // Alt+9 is the Ruler toggle, built in P07.
        assertThat(action(KeyEvent.KEYCODE_9, alt = true)).isNull()
    }

    @Test
    fun zoom_ctrlPlusMinusZero() {
        val zoomIn = EditorAction.Canvas(CanvasCommand.ZOOM_IN)
        assertThat(action(KeyEvent.KEYCODE_EQUALS, ctrl = true)).isEqualTo(zoomIn)
        assertThat(action(KeyEvent.KEYCODE_EQUALS, ctrl = true, shift = true)).isEqualTo(zoomIn)
        assertThat(action(KeyEvent.KEYCODE_PLUS, ctrl = true, shift = true)).isEqualTo(zoomIn)
        assertThat(action(KeyEvent.KEYCODE_NUMPAD_ADD, ctrl = true)).isEqualTo(zoomIn)
        assertThat(action(KeyEvent.KEYCODE_MINUS, ctrl = true)).isEqualTo(EditorAction.Canvas(CanvasCommand.ZOOM_OUT))
        assertThat(action(KeyEvent.KEYCODE_0, ctrl = true)).isEqualTo(EditorAction.Canvas(CanvasCommand.FIT_WIDTH))
    }

    @Test
    fun pages_pageKeysAndCtrlHomeEnd() {
        assertThat(action(KeyEvent.KEYCODE_PAGE_UP)).isEqualTo(EditorAction.Canvas(CanvasCommand.PREVIOUS_PAGE))
        assertThat(action(KeyEvent.KEYCODE_PAGE_DOWN)).isEqualTo(EditorAction.Canvas(CanvasCommand.NEXT_PAGE))
        assertThat(action(KeyEvent.KEYCODE_MOVE_HOME, ctrl = true)).isEqualTo(EditorAction.Canvas(CanvasCommand.FIRST_PAGE))
        assertThat(action(KeyEvent.KEYCODE_MOVE_END, ctrl = true)).isEqualTo(EditorAction.Canvas(CanvasCommand.LAST_PAGE))
    }

    @Test
    fun escapeAndHelp() {
        assertThat(action(KeyEvent.KEYCODE_ESCAPE)).isEqualTo(EditorAction.Escape)
        assertThat(action(KeyEvent.KEYCODE_SLASH, ctrl = true)).isEqualTo(EditorAction.ToggleHelp)
    }

    @Test
    fun modifiersMustMatchExactly() {
        assertThat(action(KeyEvent.KEYCODE_Z)).isNull()
        assertThat(action(KeyEvent.KEYCODE_Z, ctrl = true, alt = true)).isNull()
        assertThat(action(KeyEvent.KEYCODE_PAGE_UP, ctrl = true)).isNull()
        assertThat(action(KeyEvent.KEYCODE_1, ctrl = true)).isNull()
    }

    @Test
    fun everyGroupHasBindings_andEveryBindingIsReachable() {
        registry.groups.forEach { group ->
            assertThat(group.bindings).isNotEmpty()
            group.bindings.forEach { (chord, action) -> assertThat(registry.actionFor(chord)).isEqualTo(action) }
        }
    }

    @Test
    fun duplicateChordIsRejected() {
        val chord = KeyChord(KeyEvent.KEYCODE_Z, ctrl = true)
        val groups =
            listOf(
                ShortcutGroup("a", "a", mapOf(chord to EditorAction.Undo)),
                ShortcutGroup("b", "b", mapOf(chord to EditorAction.Redo)),
            )
        assertThrows(IllegalArgumentException::class.java) { ShortcutRegistry(groups) }
    }
}
