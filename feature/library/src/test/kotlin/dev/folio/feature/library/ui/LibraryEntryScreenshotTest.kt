package dev.folio.feature.library.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.component.FolioSnackbarHost
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.DocId
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.core.storage.work.RecoveryEvent
import dev.folio.feature.library.state.LibraryDocItem
import dev.folio.feature.library.state.recoveryNotice
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Xiaomi Pad 7 in landscape: 3200x2136 px at 440 dpi (docs/notes/device.md).
private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_LANDSCAPE)
class LibraryEntryScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun entry_needsPermission_showsOnboarding() {
        compose.setContent {
            FolioTheme(darkTheme = false) {
                LibraryEntryScreen(
                    LibraryAccessState.NeedsPermission("Documents/Folio"),
                    onGrantAccess = {},
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_onboarding.png")
    }

    @Test
    fun entry_failed_showsMessage() {
        compose.setContent {
            FolioTheme(darkTheme = false) { LibraryEntryScreen(LibraryAccessState.Failed("cannot create .trash"), onGrantAccess = {}) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_failed.png")
    }

    @Test
    fun entry_ready_showsLibraryPlaceholder() {
        compose.setContent {
            FolioTheme(darkTheme = true) {
                LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"), onGrantAccess = {})
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_ready.png")
    }

    @Test
    fun entry_readyWithDocuments_listsThem() {
        val docs =
            persistentListOf(
                LibraryDocItem("perf/lined-5.folio", "lined-5", "perf", 5),
                LibraryDocItem("Lecture notes.folio", "Lecture notes", "", 1),
            )
        compose.setContent {
            FolioTheme(darkTheme = false) {
                LibraryEntryScreen(
                    LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"),
                    onGrantAccess = {},
                    documents = docs,
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_readyDocuments.png")
    }

    @Test
    fun entry_afterRecovery_showsRecoverySnackbar() {
        val notice = recoveryNotice(listOf(RecoveryEvent.Recovered(DocId("a"), "Lecture notes.folio")))!!
        compose.mainClock.autoAdvance = false
        compose.setContent {
            FolioTheme(darkTheme = false) {
                // Same arrangement as LibraryEntryRoute: the snackbar sits bottom center over the library.
                val snackbars = remember { SnackbarHostState() }
                LaunchedEffect(notice) { snackbars.showSnackbar(notice.text) }
                Box {
                    LibraryEntryScreen(
                        LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"),
                        onGrantAccess = {},
                        documents = persistentListOf(LibraryDocItem("Lecture notes.folio", "Lecture notes", "", 1)),
                    )
                    FolioSnackbarHost(snackbars, Modifier.align(Alignment.BottomCenter).padding(FolioTheme.space.s16))
                }
            }
        }
        compose.mainClock.advanceTimeBy(SNACKBAR_SETTLE_MS)
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_recoverySnackbar.png")
    }

    private companion object {
        // Past the snackbar's enter animation, well before its auto-dismiss.
        const val SNACKBAR_SETTLE_MS = 1_000L
    }
}
