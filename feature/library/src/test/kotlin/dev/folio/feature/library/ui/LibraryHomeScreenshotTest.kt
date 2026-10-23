package dev.folio.feature.library.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.repo.DocumentSort
import dev.folio.feature.library.state.LibraryBrowseState
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.LibraryTab
import dev.folio.feature.library.state.LibraryViewMode
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

// Xiaomi Pad 7: 3200x2136 px at 440 dpi (docs/notes/device.md).
private const val LANDSCAPE = "w1164dp-h777dp-land-440dpi"
private const val PORTRAIT = "w777dp-h1164dp-port-440dpi"

@RunWith(AndroidJUnit4::class)
class LibraryHomeScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val folders =
        persistentListOf(
            LibraryFolderItem("Physics", "Physics", 0),
            LibraryFolderItem("Physics/Lab", "Lab", 1),
            LibraryFolderItem("Recipes", "Recipes", 0),
        )
    private val docs =
        persistentListOf(
            doc("Lecture notes.folio", "Lecture notes", hoursAgo = 1, favorite = true),
            doc("Physics/Thermodynamics.folio", "Thermodynamics, chapter 4: entropy and the second law of thermodynamics", hoursAgo = 30),
            doc("Shopping.folio", "Shopping", hoursAgo = 24 * 9),
            doc("Sketches.folio", "Sketches", hoursAgo = 24 * 40, favorite = true),
            doc("Old.folio", "Last year", hoursAgo = 24 * 300),
        )

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_emptyLandscapeLight() = capture("LibraryHome_emptyLandscapeLight", dark = false)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun home_emptyPortraitDark() = capture("LibraryHome_emptyPortraitDark", dark = true)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_populatedLandscapeLight() = capture("LibraryHome_populatedLandscapeLight", dark = false, documents = true)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_populatedLandscapeDark() = capture("LibraryHome_populatedLandscapeDark", dark = true, documents = true)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun home_populatedPortraitLight() = capture("LibraryHome_populatedPortraitLight", dark = false, documents = true)

    @Test
    @Config(qualifiers = PORTRAIT)
    fun home_populatedPortraitDark() = capture("LibraryHome_populatedPortraitDark", dark = true, documents = true)

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_listViewCollapsedSidebarSelectMode() =
        capture(
            "LibraryHome_listCollapsedSelect",
            dark = false,
            documents = true,
            browse =
                LibraryBrowseState(
                    viewMode = LibraryViewMode.LIST,
                    sidebarCollapsed = true,
                    selectMode = true,
                    sort = DocumentSort.NAME,
                    userName = "Martin",
                ),
        )

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_favoritesTab() =
        capture("LibraryHome_favorites", dark = false, documents = true, browse = LibraryBrowseState(tab = LibraryTab.FAVORITES))

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_binTabEmpty() =
        capture("LibraryHome_binEmpty", dark = false, documents = true, browse = LibraryBrowseState(tab = LibraryTab.BIN))

    private fun capture(
        name: String,
        dark: Boolean,
        documents: Boolean = false,
        browse: LibraryBrowseState = LibraryBrowseState(),
    ) {
        val content =
            LibraryContent(
                browse = browse,
                documents = if (documents) docs else persistentListOf(),
                folders = if (documents) folders else persistentListOf(),
                nowMs = now,
                zone = ZoneOffset.UTC,
            )
        compose.setContent { Screen(dark, content) }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Composable
    private fun Screen(
        dark: Boolean,
        content: LibraryContent,
    ) {
        FolioTheme(darkTheme = dark) {
            LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"), onGrantAccess = {}, content = content)
        }
    }

    private fun doc(
        path: String,
        title: String,
        hoursAgo: Long,
        favorite: Boolean = false,
    ) = LibraryDocItem(
        path = path,
        title = title,
        folder = path.substringBeforeLast('/', ""),
        pageCount = 3,
        modifiedMs = now - hoursAgo * HOUR_MS,
        createdMs = now - hoursAgo * HOUR_MS * 2,
        favorite = favorite,
    )

    private companion object {
        const val HOUR_MS = 3_600_000L
    }
}
