package dev.folio.feature.library.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.feature.library.state.LibraryBrowseState
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.LibraryTab
import dev.folio.feature.library.state.LibraryViewMode
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime

private const val SCREEN = "w1164dp-h777dp-land-440dpi"
private const val HOUR_MS = 3_600_000L

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = SCREEN)
class TagsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val docs =
        persistentListOf(
            doc("Lecture notes", hoursAgo = 1, favorite = true, "physics", "school"),
            doc("Thermodynamics", hoursAgo = 30, favorite = false, "physics"),
            doc("Sketches", hoursAgo = 24 * 9, favorite = false, "art"),
            doc("Shopping", hoursAgo = 24 * 12, favorite = false),
        )

    @Test
    fun tagsTab_everyTaggedNoteGrid_light() = capture("Tags_allGridLight", dark = false, browse = LibraryBrowseState(tab = LibraryTab.TAGS))

    @Test
    fun tagsTab_oneTagSelectedList_dark() =
        capture(
            "Tags_physicsListDark",
            dark = true,
            browse = LibraryBrowseState(tab = LibraryTab.TAGS, tag = "physics", viewMode = LibraryViewMode.LIST),
        )

    @Test
    fun tagsTab_noTags_light() = capture("Tags_none", dark = false, browse = LibraryBrowseState(tab = LibraryTab.TAGS), tagged = false)

    @Test
    fun tagEditor_currentTagsAndSuggestions_light() {
        compose.setContent {
            FolioTheme(darkTheme = false) {
                TagEditorDialog(
                    noteTitle = "Lecture notes",
                    initial = persistentListOf("physics", "school"),
                    known = persistentListOf("art", "lab", "physics", "school"),
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/Tags_editorLight.png")
    }

    @Test
    fun tagEditor_noTags_dark() {
        compose.setContent {
            FolioTheme(darkTheme = true) {
                TagEditorDialog(
                    noteTitle = "Shopping",
                    initial = persistentListOf(),
                    known = persistentListOf("art", "physics"),
                    onSave = {},
                    onDismiss = {},
                )
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/Tags_editorEmptyDark.png")
    }

    private fun capture(
        name: String,
        dark: Boolean,
        browse: LibraryBrowseState,
        tagged: Boolean = true,
    ) {
        val content =
            LibraryContent(
                browse = browse,
                documents = if (tagged) docs else persistentListOf(doc("Shopping", hoursAgo = 3, favorite = false)),
                nowMs = now,
                zone = ZoneOffset.UTC,
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
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    private fun doc(
        title: String,
        hoursAgo: Long,
        favorite: Boolean,
        vararg tags: String,
    ) = LibraryDocItem(
        path = "$title.folio",
        title = title,
        folder = "",
        pageCount = 2,
        modifiedMs = now - hoursAgo * HOUR_MS,
        createdMs = now - hoursAgo * HOUR_MS,
        favorite = favorite,
        tags = persistentListOf(*tags),
    )
}
