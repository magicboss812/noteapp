package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.Orientation
import dev.folio.core.model.PageId
import dev.folio.core.model.PageRef
import dev.folio.core.model.PageSpec
import dev.folio.core.model.PaperSize
import dev.folio.core.model.TemplateKind
import dev.folio.core.testing.ModelFixtures
import dev.folio.feature.editor.state.PageSettings
import dev.folio.feature.editor.state.PaperColor
import kotlinx.collections.immutable.toImmutableList
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"
private const val PAD7_PORTRAIT = "w777dp-h1164dp-port-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_LANDSCAPE)
class PageSurfacesScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val pages: List<PageRef> =
        listOf("p1", "p2", "p3", "p4", "p5").mapIndexed { index, id ->
            val page =
                ModelFixtures.page(
                    id,
                    spec =
                        if (index ==
                            3
                        ) {
                            PageSpec.Fixed(PaperSize.A4, Orientation.LANDSCAPE)
                        } else {
                            ModelFixtures.A4
                        },
                )
            val tinted = if (index == 2) page.background.copy(paperArgb = PaperColor.CREAM.argb) else page.background
            ModelFixtures.document(listOf(page.copy(background = tinted))).pages.single()
        }
    private val ids = pages.map { it.id }

    private fun show(
        dark: Boolean = false,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                Box(Modifier.fillMaxSize().background(FolioTheme.colors.canvas).padding(FolioTheme.space.s8)) { content() }
            }
        }
    }

    private val paper: PageThumbnail = { page, modifier -> PaperThumbnail(page, modifier) }

    private fun panel(
        current: PageId?,
        dark: Boolean = false,
    ) = show(dark) { PagePanel(pages.toImmutableList(), current, paper, PageActions(), onClose = {}) }

    @Test
    fun panel_fivePagesSecondCurrent_thumbnailsNumbersHighlight() {
        panel(ids[1])
        compose.onRoot().captureRoboImage("src/test/screenshots/PagePanel_light.png")
    }

    @Test
    fun panel_fivePagesDark_highlightReadsOnDark() {
        panel(ids[0], dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/PagePanel_dark.png")
    }

    @Test
    fun overview_fivePagesSecondCurrent_gridWithNumbers() {
        show { PageOverview(pages.toImmutableList(), ids[1], paper, PageActions()) }
        compose.onRoot().captureRoboImage("src/test/screenshots/PageOverview_light.png")
    }

    @Test
    fun overview_selecting_twoSelectedAndBarActions() {
        show {
            PageOverview(
                pages.toImmutableList(),
                ids[1],
                paper,
                PageActions(),
                initiallySelecting = true,
                initialSelection = setOf(ids[0], ids[2]),
            )
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/PageOverview_selecting.png")
    }

    @Test
    fun settingsSheet_linedPage_sizeTemplateSpacingPaperApplyTo() {
        show { PageSettingsSheet(PageSettings(pages[0]), onApply = { _, _ -> }, onClose = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/PageSettings_lined.png")
    }

    @Test
    fun settingsSheet_gridDark_noMarginRowAndDarkPaperSelected() {
        val grid = PageSettings(pages[0]).withTemplate(TemplateKind.GRID).copy(paperArgb = PaperColor.DARK.argb)
        show(dark = true) { PageSettingsSheet(grid, onApply = { _, _ -> }, onClose = {}) }
        compose.onRoot().captureRoboImage("src/test/screenshots/PageSettings_gridDark.png")
    }
}

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_PORTRAIT)
class PageSurfacesPortraitScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun overview_portraitFivePages_adaptiveColumns() {
        val pages = List(5) { ModelFixtures.document(listOf(ModelFixtures.page("q$it"))).pages.single() }
        compose.setContent {
            FolioTheme {
                PageOverview(
                    pages.toImmutableList(),
                    pages[0].id,
                    { page, modifier -> PaperThumbnail(page, modifier) },
                    PageActions(),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/PageOverview_portrait.png")
    }
}
