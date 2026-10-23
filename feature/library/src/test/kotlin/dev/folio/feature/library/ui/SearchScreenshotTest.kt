package dev.folio.feature.library.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.repo.HighlightedText
import dev.folio.feature.library.state.LibraryFolderItem
import dev.folio.feature.library.state.SearchResultItem
import dev.folio.feature.library.state.SearchUiState
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val SCREEN = "w1164dp-h777dp-land-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = SCREEN)
class SearchScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val folders = persistentListOf(LibraryFolderItem("Physics", "Physics", 0), LibraryFolderItem("Physics/Labs", "Labs", 1))

    @Test
    fun search_recentSearches_light() =
        capture(
            "Search_recentLight",
            dark = false,
            SearchUiState(open = true, recent = persistentListOf("kinematics", "lab report", "über")),
        )

    @Test
    fun search_hint_noRecent_dark() = capture("Search_hintDark", dark = true, SearchUiState(open = true))

    @Test
    fun search_results_highlighted_light() = capture("Search_resultsLight", dark = false, results())

    @Test
    fun search_results_filtered_dark() =
        capture(
            "Search_resultsFilteredDark",
            dark = true,
            results().copy(folder = "Physics", tag = "school", tags = persistentListOf("art", "school")),
        )

    @Test
    fun search_noMatches_light() = capture("Search_noMatchesLight", dark = false, SearchUiState(open = true, query = "zzz"))

    @Test
    @Config(qualifiers = "w777dp-h1164dp-port-440dpi")
    fun search_results_portrait_light() = capture("Search_resultsPortraitLight", dark = false, results())

    private fun results() =
        SearchUiState(
            open = true,
            query = "velo",
            tags = persistentListOf("school"),
            results =
                persistentListOf(
                    SearchResultItem(
                        "Physics/Velocity basics.folio",
                        HighlightedText("Velocity basics", listOf(0..7)),
                        "Physics",
                        HighlightedText("average velocity is the change in position over time", listOf(8..15)),
                        0,
                    ),
                    SearchResultItem(
                        "Lecture.folio",
                        HighlightedText("Lecture 4"),
                        "",
                        HighlightedText(
                            "…the escape velocity of a planet depends on its mass and radius, not on the mass of the…",
                            listOf(12..19),
                        ),
                        0,
                    ),
                ),
        )

    private fun capture(
        name: String,
        dark: Boolean,
        state: SearchUiState,
    ) {
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                SearchOverlay(state, folders, SearchActions(), onOpenDocument = {})
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/$name.png")
    }
}
