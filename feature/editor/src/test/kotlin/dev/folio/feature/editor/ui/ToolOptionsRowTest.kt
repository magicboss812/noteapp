package dev.folio.feature.editor.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.ink.brush.BrushPresets
import dev.folio.feature.editor.state.EditorTool
import dev.folio.feature.editor.state.LassoFilter
import dev.folio.feature.editor.state.OptionsPopover
import dev.folio.feature.editor.state.ToolOptions
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Taps on row 2 and in the tool settings panel (10-editor-ui.md#tool-options). */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class ToolOptionsRowTest {
    @get:Rule
    val compose = createComposeRule()

    private var options by mutableStateOf(ToolOptions())
    private val popovers = mutableListOf<OptionsPopover?>()
    private val settings = mutableListOf<EditorTool>()

    private fun showRow(tool: EditorTool) {
        compose.setContent {
            FolioTheme(darkTheme = false) {
                ToolOptionsRow(
                    tool,
                    options,
                    onChange = { change -> options = change(options) },
                    onPopover = { popovers += it },
                    onSettings = { settings += it },
                )
            }
        }
    }

    @Test
    fun row_tapOtherWidthSelects_tapSelectedWidthOpensItsEditor() {
        showRow(EditorTool.PEN)

        compose.onNodeWithContentDescription("Large width").performClick()
        assertThat(options.pen.kindWidths.selected).isEqualTo(2)
        assertThat(popovers).isEmpty()

        compose.onNodeWithContentDescription("Large width").performClick()
        assertThat(popovers).containsExactly(OptionsPopover.WidthEditor(EditorTool.PEN, 2))
    }

    @Test
    fun row_tapSelectedColorOpensPicker_detailsTogglesSettings() {
        showRow(EditorTool.HIGHLIGHTER)
        val first = "Color 1 #${hexOf(BrushPresets.HIGHLIGHTER_PALETTE[0])}"

        compose.onNodeWithContentDescription(first).performClick()
        compose.onNodeWithContentDescription("Highlighter settings").performClick()

        assertThat(popovers).containsExactly(OptionsPopover.ColorPicker(EditorTool.HIGHLIGHTER, 0))
        assertThat(settings).containsExactly(EditorTool.HIGHLIGHTER)
    }

    @Test
    fun panel_switchAndChips_changeTheToolsOptions_clearPageAsks() {
        compose.setContent {
            FolioTheme(darkTheme = false) {
                val tool = if (options.eraser.highlighterOnly) EditorTool.LASSO else EditorTool.ERASER
                ToolSettingsPanel(tool, options, onChange = { change -> options = change(options) }, onPopover = { popovers += it })
            }
        }

        compose.onNodeWithText("Clear page").performClick()
        compose.onNodeWithText("Erase highlighter only").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Images").performClick()

        assertThat(popovers).containsExactly(OptionsPopover.ClearPage)
        assertThat(options.eraser.highlighterOnly).isTrue()
        assertThat(options.lasso.filters).doesNotContain(LassoFilter.IMAGES)
    }
}
