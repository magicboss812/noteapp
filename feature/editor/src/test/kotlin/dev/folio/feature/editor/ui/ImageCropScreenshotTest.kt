package dev.folio.feature.editor.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.designsystem.theme.FolioTheme
import dev.folio.core.model.AssetId
import dev.folio.core.model.ObjectId
import dev.folio.core.model.PageId
import dev.folio.core.model.RectF01
import dev.folio.feature.editor.state.ImageCropResult
import dev.folio.feature.editor.state.ImageEditUi
import dev.folio.feature.editor.state.ImageTarget
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w1164dp-h777dp-land-440dpi")
class ImageCropScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private val edit =
        ImageEditUi(ImageTarget(PageId("p"), ObjectId("i")), AssetId("a".repeat(64)), RectF01(0.1f, 0.15f, 0.7f, 0.9f), 0f)

    private fun show(
        edit: ImageEditUi,
        dark: Boolean = false,
        onDone: (ImageCropResult) -> Unit = {},
    ) = compose.setContent { FolioTheme(darkTheme = dark) { ImageCropScreen(edit, quadrants(), onDone, {}) } }

    @Test
    fun screenshot_cropFrameOverPicture() {
        show(edit)
        compose.onRoot().captureRoboImage("src/test/screenshots/ImageCrop_light.png")
    }

    @Test
    fun screenshot_turnedImageDark() {
        show(edit.copy(rotationDeg = 90f), dark = true)
        compose.onRoot().captureRoboImage("src/test/screenshots/ImageCrop_turnedDark.png")
    }

    @Test
    fun turnRightThenDone_keepsTheSourceCropAndAddsATurn() {
        var result: ImageCropResult? = null
        show(edit) { result = it }

        compose.onNodeWithContentDescription("Turn right").performClick()
        compose.onNodeWithText("Done").performClick()

        assertThat(result!!.turns).isEqualTo(1)
        assertThat(result!!.crop.left).isWithin(1e-5f).of(0.1f)
        assertThat(result!!.crop.bottom).isWithin(1e-5f).of(0.9f)
    }

    @Test
    fun dragInsideTheCrop_movesItWithinThePicture() {
        var result: ImageCropResult? = null
        show(edit) { result = it }

        compose.onNodeWithTag("cropFrame").performTouchInput {
            // A short first step: the drag starts where it passes touch slop, which must still be inside the crop.
            down(center)
            moveBy(Offset(viewConfiguration.touchSlop + 4f, 0f))
            moveBy(Offset(width * 0.5f, 0f))
            moveBy(Offset(width * 0.5f, 0f))
            up()
        }
        compose.onNodeWithText("Done").performClick()

        // Width 0.6 kept, pushed against the right edge.
        assertThat(result!!.crop.right).isWithin(1e-4f).of(1f)
        assertThat(result!!.crop.left).isWithin(1e-4f).of(0.4f)
        assertThat(result!!.turns).isEqualTo(0)
    }

    // 3:2, red/green over blue/yellow.
    private fun quadrants(): Bitmap =
        Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888).apply {
            val c = Canvas(this)
            val p = Paint()
            listOf(0xFFDC2626, 0xFF16A34A, 0xFF2563EB, 0xFFEAB308).forEachIndexed { i, argb ->
                p.color = argb.toInt()
                c.drawRect((i % 2) * 150f, (i / 2) * 100f, (i % 2) * 150f + 150f, (i / 2) * 100f + 100f, p)
            }
        }
}
