package dev.folio.feature.library.ui

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.Orientation
import dev.folio.core.model.TemplateKind
import dev.folio.core.render.template.TemplatePresets
import dev.folio.feature.library.state.NewNoteForm
import dev.folio.feature.library.state.NotePaper
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"
private const val PAD7_PORTRAIT = "w777dp-h1164dp-port-440dpi"
private const val TITLE = "Untitled 2026-10-02"

@RunWith(AndroidJUnit4::class)
class NewNoteSheetScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun sheet_landscape_defaults() {
        show(NewNoteForm(TITLE), dark = false, width = 640.dp)
        compose.onRoot().captureRoboImage("src/test/screenshots/NewNote_landscape.png")
    }

    @Test
    @Config(qualifiers = PAD7_LANDSCAPE)
    fun sheet_landscape_dark() {
        show(
            NewNoteForm(TITLE, template = TemplateKind.GRID, spacingPt = TemplatePresets.LATTICE_SPACINGS_PT[0]),
            dark = true,
            width = 640.dp,
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/NewNote_landscape_dark.png")
    }

    @Test
    @Config(qualifiers = PAD7_PORTRAIT)
    fun sheet_portrait_landscapePaperOnCream() {
        show(
            NewNoteForm(TITLE, orientation = Orientation.LANDSCAPE, paperArgb = NotePaper.CREAM.argb).withTemplate(TemplateKind.DOTTED),
            dark = false,
            width = 520.dp,
        )
        compose.onRoot().captureRoboImage("src/test/screenshots/NewNote_portrait.png")
    }

    private fun show(
        form: NewNoteForm,
        dark: Boolean,
        width: androidx.compose.ui.unit.Dp,
    ) {
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                NewNoteSheet(form = form, onChange = {}, onCreate = {}, onClose = {}, width = width)
            }
        }
    }
}
