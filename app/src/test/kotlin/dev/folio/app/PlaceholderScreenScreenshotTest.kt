package dev.folio.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

// Xiaomi Pad 7 in landscape: 3200x2136 px at 440 dpi (docs/notes/device.md).
private const val PAD7_LANDSCAPE = "w1164dp-h777dp-land-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = PAD7_LANDSCAPE)
class PlaceholderScreenScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun placeholderScreen_debugBuild_matchesGolden() {
        compose.setContent {
            MaterialTheme { PlaceholderScreen(buildType = "debug", versionName = "0.1.0") }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/PlaceholderScreen_debug.png")
    }
}
