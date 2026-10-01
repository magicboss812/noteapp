package dev.folio.feature.editor.ui

import android.os.Looper
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.editor.canvas.CanvasHost
import dev.folio.feature.editor.canvas.CanvasHostView
import dev.folio.feature.editor.canvas.FakeCanvasController
import dev.folio.feature.editor.state.DockMode
import dev.folio.feature.editor.state.EditorStatus
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.EditorUiState
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.ToolbarDocks
import dev.folio.feature.editor.state.ToolbarPlacement
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

// Xiaomi Pad 7: 3200x2136 px at 440 dpi (docs/notes/device.md).
private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"
private const val PAD7_PORTRAIT = "w777dp-h1164dp-port-440dpi"

@RunWith(AndroidJUnit4::class)
class EditorScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun showReady(
        tool: EditorTool = EditorTool.PEN,
        popover: OptionsPopover? = null,
        placement: ToolbarPlacement = ToolbarPlacement(),
        dark: Boolean = false,
    ) {
        val controller = FakeCanvasController(pages = 2)
        val docks = ToolbarDocks(landscape = placement, portrait = placement)
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                EditorScreen(
                    EditorUiState(EditorStatus.READY, tool = tool, popover = popover, docks = docks),
                    onBack = {},
                    onSelectTool = {},
                ) {
                    CanvasHost(controller, it)
                }
            }
        }
        // The idle tile request (100 ms after layout) and the frame that shows the tiles.
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CanvasHostView.IDLE_MS * 2))
        compose.waitForIdle()
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeLinedA4_toolbarAboveFitWidthPage() {
        showReady()
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_landscape.png")
    }

    @Test
    @Config(qualifiers = PAD7_PORTRAIT)
    fun editor_portraitLinedA4_toolsPillScrollsWithFade() {
        showReady(EditorTool.HIGHLIGHTER)
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_portrait.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeHighlighterColorPickerOpen_popoverBelowOptionsRow() {
        showReady(EditorTool.HIGHLIGHTER, OptionsPopover.ColorPicker(EditorTool.HIGHLIGHTER, 0))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_colorPickerOpen.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeUndoAvailableHelpOpen_shortcutSheetOverCanvas() {
        val controller = FakeCanvasController(pages = 2)
        compose.setContent {
            FolioTheme {
                EditorScreen(
                    EditorUiState(EditorStatus.READY, canUndo = true, showHelp = true),
                    onBack = {},
                    onSelectTool = {},
                ) {
                    CanvasHost(controller, it)
                }
            }
        }
        compose.waitForIdle()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(CanvasHostView.IDLE_MS * 2))
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_shortcutHelp.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeDockedLeft_railsBesideCanvas() {
        showReady(placement = ToolbarPlacement(DockMode.LEFT))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_dockLeft_landscape.png")
    }

    @Test
    @Config(qualifiers = PAD7_PORTRAIT)
    fun editor_portraitDockedLeftEraser_verticalModeTabs() {
        showReady(EditorTool.ERASER, placement = ToolbarPlacement(DockMode.LEFT))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_dockLeft_portrait.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeDockedRightHighlighterDark_railsMirrored() {
        showReady(EditorTool.HIGHLIGHTER, placement = ToolbarPlacement(DockMode.RIGHT), dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_dockRight_landscape.png")
    }

    @Test
    @Config(qualifiers = PAD7_PORTRAIT)
    fun editor_portraitDockedRight_railsMirrored() {
        showReady(placement = ToolbarPlacement(DockMode.RIGHT))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_dockRight_portrait.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeDockedLeftPenSettings_flyoutBesideOptionsRail() {
        showReady(popover = OptionsPopover.PenSettings, placement = ToolbarPlacement(DockMode.LEFT))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_dockLeft_flyout.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeFloating_pillWithOptionsAtStoredPosition() {
        showReady(placement = ToolbarPlacement(DockMode.FLOATING, floatX = 0.3f, floatY = 0.4f))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_floating_landscape.png")
    }

    @Test
    @Config(qualifiers = PAD7_PORTRAIT)
    fun editor_portraitFloating_pillWithOptionsAtStoredPosition() {
        showReady(EditorTool.ERASER, placement = ToolbarPlacement(DockMode.FLOATING, floatX = 0.5f, floatY = 0.8f))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_floating_portrait.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_landscapeFloatingCollapsed_currentToolOnly() {
        showReady(placement = ToolbarPlacement(DockMode.FLOATING, floatX = 1f, floatY = 0.5f, collapsed = true))
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_floating_collapsed.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun editor_openFailedDark_showsMessageWithoutCanvas() {
        compose.setContent {
            FolioTheme(darkTheme = true) {
                EditorScreen(EditorUiState(EditorStatus.FAILED, "not a Folio file"), onBack = {}, onSelectTool = {}) {
                    error("no canvas while failed")
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/Editor_failedDark.png")
    }
}
