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
import dev.folio.feature.editor.state.EditorStatus
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.EditorUiState
import dev.folio.feature.editor.state.OptionsPopover
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
    ) {
        val controller = FakeCanvasController(pages = 2)
        compose.setContent {
            FolioTheme(darkTheme = false) {
                EditorScreen(EditorUiState(EditorStatus.READY, tool = tool, popover = popover), onBack = {}, onSelectTool = {}) {
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
