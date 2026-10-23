package dev.folio.feature.library.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import dev.folio.core.designsystem.component.FolderCard
import dev.folio.core.designsystem.component.NoteCard
import dev.folio.core.designsystem.theme.FolderColor
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.storage.library.LibraryAccessState
import dev.folio.feature.library.state.LibraryBrowseState
import dev.folio.feature.library.state.LibraryDocItem
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.time.ZoneOffset
import java.time.ZonedDateTime
import android.graphics.Bitmap as AndroidBitmap
import android.graphics.Canvas as AndroidCanvas
import android.graphics.Color as AndroidColor
import android.graphics.Paint as AndroidPaint

private const val LANDSCAPE = "w1164dp-h777dp-land-440dpi"
private const val HOUR_MS = 3_600_000L

/** Mixed cards (stored cover, excerpt, blank, favorite, folder) and the selection mode of the library home. */
@RunWith(AndroidJUnit4::class)
class NoteCardsScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val now = ZonedDateTime.of(2026, 10, 23, 9, 0, 0, 0, ZoneOffset.UTC).toInstant().toEpochMilli()
    private val docs =
        persistentListOf(
            doc("Cover.folio", "Lecture notes", hoursAgo = 1, favorite = true, hasCover = true),
            doc("Excerpt.folio", "Shopping list", hoursAgo = 30, textPreview = "# Shopping\n- oat milk\n- coffee beans\nCall the plumber"),
            doc("Blank.folio", "Untitled", hoursAgo = 24 * 9),
            doc("Cover2.folio", "Sketches", hoursAgo = 24 * 40, hasCover = true),
        )

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun cards_mixedRow_coverExcerptBlankFolder() {
        compose.setContent {
            FolioTheme(darkTheme = false) {
                Row(
                    Modifier.background(FolioTheme.colors.libraryBottom).padding(24.dp),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                ) {
                    FolderCard(
                        "Physics",
                        noteCount = 12,
                        color = FolderColor.Teal,
                        onClick = {},
                        latestTitle = "Entropy",
                        latestPreview = "dS >= 0",
                    )
                    NoteCard("Lecture notes", "1 h ago", onClick = {}, favorite = true, onOverflow = {}) { NotePreview(docs[0], cover()) }
                    NoteCard("Shopping list", "Yesterday", onClick = {}, onOverflow = {}) { NotePreview(docs[1], null) }
                    NoteCard("Untitled", "Oct 14", onClick = {}, onOverflow = {}) { NotePreview(docs[2], null) }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/NoteCards_mixedRow.png")
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_selectMode_twoTicked() {
        val browse = LibraryBrowseState(selectMode = true, selected = persistentSetOf("Cover.folio", "Blank.folio"))
        val notes = NoteActions(loadCover = { cover() })
        compose.setContent {
            FolioTheme(darkTheme = false) {
                LibraryEntryScreen(
                    LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"),
                    onGrantAccess = {},
                    content = LibraryContent(browse = browse, documents = docs, nowMs = now, zone = ZoneOffset.UTC),
                    actions = LibraryActions(notes = notes),
                )
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryHome_selectTwo.png")
    }

    @Test
    @Config(qualifiers = LANDSCAPE)
    fun home_gridWithCovers() {
        val notes = NoteActions(loadCover = { cover() })
        compose.setContent {
            FolioTheme(darkTheme = false) {
                LibraryEntryScreen(
                    LibraryAccessState.Ready("/storage/emulated/0/Documents/Folio-Debug"),
                    onGrantAccess = {},
                    content = LibraryContent(documents = docs, nowMs = now, zone = ZoneOffset.UTC),
                    actions = LibraryActions(notes = notes),
                )
            }
        }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage("src/test/screenshots/LibraryHome_gridWithCovers.png")
    }

    /** A generated cover standing in for the stored `thumbs/cover.webp`: ruled lines and a blue scribble on white. */
    private fun cover(): ImageBitmap {
        val bitmap = AndroidBitmap.createBitmap(COVER_PX, COVER_PX, AndroidBitmap.Config.ARGB_8888)
        val canvas = AndroidCanvas(bitmap)
        canvas.drawColor(AndroidColor.WHITE)
        val rule = AndroidPaint().apply { color = AndroidColor.LTGRAY }
        for (y in COVER_RULE_STEP until COVER_PX step COVER_RULE_STEP) canvas.drawRect(0f, y.toFloat(), COVER_PX.toFloat(), y + 2f, rule)
        val ink =
            AndroidPaint().apply {
                color = AndroidColor.rgb(0x2B, 0x5C, 0xE6)
                strokeWidth = COVER_INK_WIDTH
                style = AndroidPaint.Style.STROKE
            }
        canvas.drawLine(40f, 80f, 360f, 120f, ink)
        canvas.drawLine(40f, 160f, 280f, 150f, ink)
        return bitmap.asImageBitmap()
    }

    private fun doc(
        path: String,
        title: String,
        hoursAgo: Long,
        favorite: Boolean = false,
        hasCover: Boolean = false,
        textPreview: String = "",
    ) = LibraryDocItem(
        path = path,
        title = title,
        folder = "",
        pageCount = 3,
        modifiedMs = now - hoursAgo * HOUR_MS,
        createdMs = now - hoursAgo * HOUR_MS * 2,
        favorite = favorite,
        textPreview = textPreview,
        hasCover = hasCover,
    )

    private companion object {
        const val COVER_PX = 400
        const val COVER_RULE_STEP = 40
        const val COVER_INK_WIDTH = 6f
    }
}
