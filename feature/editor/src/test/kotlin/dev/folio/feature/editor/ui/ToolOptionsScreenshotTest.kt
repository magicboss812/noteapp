package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.ink.erase.EraserMode
import dev.folio.core.ink.erase.EraserOptions
import dev.folio.core.model.BrushKind
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.PenOptions
import dev.folio.feature.editor.state.ToolOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_LANDSCAPE)
class ToolOptionsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val custom =
        ToolOptions(pen = PenOptions(kind = BrushKind.FOUNTAIN))
            .withPickedColor(EditorTool.PEN, null, 0xFFB45309.toInt())
            .withPickedColor(EditorTool.PEN, null, 0xFF0EA5E9.toInt())

    private fun show(
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                Box(Modifier.background(FolioTheme.colors.canvasSurround).padding(FolioTheme.space.s16)) { content() }
            }
        }
    }

    private fun row(
        tool: EditorTool,
        options: ToolOptions = ToolOptions(),
        dark: Boolean = false,
    ) = show(dark) { ToolOptionsRow(tool, options, onChange = {}, onPopover = {}) }

    @Test
    fun row_penDefaults_kindsWidthsColorsAddSettings() {
        row(EditorTool.PEN)
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_pen.png")
    }

    @Test
    fun row_penFountainCustomColorsDark_selectedAddedColor() {
        row(EditorTool.PEN, custom, dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_penCustomDark.png")
    }

    @Test
    fun row_highlighterAlwaysStraight_toggleSelected() {
        val options = ToolOptions().let { it.copy(highlighter = it.highlighter.copy(alwaysStraight = true)) }
        row(EditorTool.HIGHLIGHTER, options)
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_highlighter.png")
    }

    @Test
    fun row_eraserPartialLargeHighlighterOnly_modeSizeToggleClear() {
        val eraser = EraserOptions(EraserMode.PARTIAL, EraserOptions.SIZES_PT[2], highlighterOnly = true)
        row(EditorTool.ERASER, ToolOptions(eraser = eraser))
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_eraser.png")
    }

    @Test
    fun popover_penSettings_pressureAndWidthSliders() {
        show { PenSettingsPopover(ToolOptions().let { it.copy(pen = it.pen.withPressureGamma(1.4f)) }, onChange = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_penSettings.png")
    }

    @Test
    fun popover_widthEditorDark_largeHighlighterPreset() {
        show(dark = true) { WidthEditorPopover(EditorTool.HIGHLIGHTER, 2, ToolOptions(), onChange = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_widthEditorDark.png")
    }

    @Test
    fun popover_colorPickerEditDot_squareHueHexRecentsRemove() {
        show { ColorPickerPopover(OptionsPopover.ColorPicker(EditorTool.PEN, 1), custom, onChange = {}, onClose = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_colorPicker.png")
    }

    @Test
    fun popover_colorPickerAddDark_noRemove() {
        show(dark = true) { ColorPickerPopover(OptionsPopover.ColorPicker(EditorTool.HIGHLIGHTER, null), ToolOptions(), {}, {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/ToolOptions_colorPickerAddDark.png")
    }
}
