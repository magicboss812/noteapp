package dev.folio.feature.editor.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.session.SaveState
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SaveIndicatorScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(dark: Boolean) {
        compose.setContent {
            FolioTheme(darkTheme = dark) {
                Column(Modifier.background(FolioTheme.colors.canvas).padding(16.dp)) {
                    SaveState.entries.forEach { SaveIndicator(it, onRetry = {}, Modifier.padding(8.dp)) }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun saveIndicator_savedSavingAndError_light() {
        show(dark = false)
        compose.onRoot().captureRoboImage("src/test/screenshots/SaveIndicator_light.png")
    }

    @Test
    fun saveIndicator_savedSavingAndError_dark() {
        show(dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/SaveIndicator_dark.png")
    }
}
