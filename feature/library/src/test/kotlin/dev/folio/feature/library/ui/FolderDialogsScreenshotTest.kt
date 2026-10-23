package dev.folio.feature.library.ui

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.theme.FolderColor
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.feature.library.state.LibraryFolderItem
import kotlinx.collections.immutable.persistentListOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private const val DIALOG_SCREEN = "w1164dp-h777dp-land-440dpi"

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = DIALOG_SCREEN)
class FolderDialogsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val folders =
        persistentListOf(
            LibraryFolderItem("Physics", "Physics", 0, tint = "sky"),
            LibraryFolderItem("Physics/Lab", "Lab", 1),
            LibraryFolderItem("Recipes", "Recipes", 0),
        )

    @Test
    fun newFolderDialog_light() {
        compose.setContent {
            FolioTheme(darkTheme = false) { NameDialog("New folder", "Folder name", "Create", "", onConfirm = {}, onDismiss = {}) }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/FolderDialog_new.png")
    }

    @Test
    fun colorDialog_currentMarked_dark() {
        compose.setContent {
            FolioTheme(darkTheme = true) { FolderColorDialog(current = FolderColor.Sky, onPick = {}, onDismiss = {}) }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/FolderDialog_colorDark.png")
    }

    @Test
    fun moveFolderDialog_ownSubtreeDisabled_light() {
        compose.setContent {
            FolioTheme(darkTheme = false) {
                MoveNotesDialog(folders, currentFolder = "", onMove = {}, onDismiss = {}, movingFolder = "Physics")
            }
        }
        compose.onNode(isDialog()).captureRoboImage("src/test/screenshots/FolderDialog_move.png")
    }
}
