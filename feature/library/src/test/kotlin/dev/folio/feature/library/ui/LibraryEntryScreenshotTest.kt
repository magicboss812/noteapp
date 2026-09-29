package dev.folio.feature.library.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.storage.library.LibraryAccessState
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
            MaterialTheme {
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
        compose.setContent { MaterialTheme { LibraryEntryScreen(LibraryAccessState.Failed("cannot create .trash"), onGrantAccess = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_failed.png")
    }

    @Test
    fun entry_ready_showsLibraryPlaceholder() {
        compose.setContent {
            MaterialTheme { LibraryEntryScreen(LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"), onGrantAccess = {}) }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryEntry_ready.png")
    }
}
