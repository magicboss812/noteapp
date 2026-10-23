package dev.folio.feature.library.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.repo.BinRetention
import dev.folio.feature.library.state.LibraryBinItem
import dev.folio.feature.library.state.LibraryBinState
import dev.folio.feature.library.state.LibraryBrowseState
import dev.folio.feature.library.state.LibraryTab
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

private const val SCREEN = "w1164dp-h777dp-land-440dpi"
private const val PORTRAIT = "w777dp-h1164dp-port-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = SCREEN)
class BinScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val bin =
        LibraryBinState(
            items =
                persistentListOf(
                    item("Lecture notes", daysAgo = 0, daysLeft = 30),
                    item("Physics", daysAgo = 1, daysLeft = 29, folder = true),
                    item("Sketches", daysAgo = 12, daysLeft = 18),
                    item("Old shopping list", daysAgo = 28, daysLeft = 2),
                    item("Draft", daysAgo = 30, daysLeft = 0),
                ),
        )

    @Test
    fun binTab_list_light() = capture("Bin_listLight", dark = false, bin = bin)

    @Test
    fun binTab_list_dark() = capture("Bin_listDark", dark = true, bin = bin)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun binTab_portrait_light() = capture("Bin_listPortraitLight", dark = false, bin = bin)

    @Test
    fun binTab_retentionNever_hidesDaysLeft() =
        capture(
            "Bin_retentionNever",
            dark = false,
            bin =
                LibraryBinState(
                    persistentListOf(item("Lecture notes", daysAgo = 4, daysLeft = null)),
                    BinRetention.NEVER,
                ),
        )

    @Test
    fun binTab_empty_light() = capture("Bin_emptyLight", dark = false, bin = LibraryBinState())

    @Test
    fun deleteForeverDialog_light() {
        show(dark = false, bin = bin)
        compose.onNodeWithContentDescription("Delete Lecture notes forever").performClick()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/Bin_deleteForeverDialogLight.png")
    }

    @Test
    fun emptyBinDialog_dark() {
        show(dark = true, bin = bin)
        compose.onNodeWithText("Empty bin").performClick()
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/Bin_emptyBinDialogDark.png")
    }

    private fun capture(
        name: String,
        dark: Boolean,
        bin: LibraryBinState,
    ) {
        show(dark, bin)
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun show(
        dark: Boolean,
        bin: LibraryBinState,
    ) {
        val content =
            LibraryContent(
                browse = LibraryBrowseState(tab = LibraryTab.BIN),
                nowMs = now,
                zone = ZoneOffset.UTC,
                bin = bin,
            )
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                LibraryEntryScreen(
                    LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"),
                    onGrantAccess = {},
                    content = content,
                )
            }
        }
    }

    private fun item(
        name: String,
        daysAgo: Int,
        daysLeft: Int?,
        folder: Boolean = false,
    ) = LibraryBinItem(".trash/1__$name", name, folder, daysAgo, daysLeft)
}
