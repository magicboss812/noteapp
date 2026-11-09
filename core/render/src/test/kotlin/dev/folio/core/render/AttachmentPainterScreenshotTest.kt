package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.Attachment
import dev.folio.core.model.ObjectId
import dev.folio.core.model.geometry.PointPt
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AttachmentPainterScreenshotTest {
    private val renderer = PageRenderer()

    @Test
    fun screenshot_chipsOfEachKindLongNameAndUnknownSize() {
        val chips =
            listOf(
                chip("pdf", "Syllabus.pdf", "application/pdf", 0, 1_245_184),
                chip("img", "Whiteboard photo.jpg", "image/jpeg", 1, 3_565_158),
                chip("doc", "Lab report final version (2) with appendix and data.docx", "application/msword", 2, 48_200),
                chip("zip", "dataset.zip", "application/zip", 3, 0),
                chip("bin", "", "application/octet-stream", 4, 900),
            )
        val page = ModelFixtures.page("p", chips)
        val region = RectPt(0f, 0f, 230f, 270f)
        val bitmap = Bitmap.createBitmap((region.widthPt * SCALE).toInt(), (region.heightPt * SCALE).toInt(), Bitmap.Config.ARGB_8888)

        renderer.draw(Canvas(bitmap), page.toRef(), PageContent.of(page), region, SCALE, RenderTarget.EXPORT_RASTER)

        // The PDF chip's type tile is red; the card around it light gray.
        assertThat(pixel(bitmap, 16f + 10f, 16f + 21f)).isEqualTo(0xFFDC2626.toInt())
        assertThat(pixel(bitmap, 16f + 190f, 16f + 4f)).isEqualTo(0xFFF3F4F7.toInt())
        bitmap.captureRoboImage("src/test/screenshots/AttachmentPainter_kindsLongNameUnknownSize.png")
    }

    private fun chip(
        id: String,
        name: String,
        mime: String,
        row: Int,
        sizeBytes: Long,
    ) = Attachment(ObjectId(id), AssetId(id.padEnd(64, '0')), name, mime, PointPt(16f, 16f + row * 50f), sizeBytes)

    private fun pixel(
        bitmap: Bitmap,
        xPt: Float,
        yPt: Float,
    ): Int = bitmap.getPixel((xPt * SCALE).toInt(), (yPt * SCALE).toInt())

    private companion object {
        const val SCALE = 2f
    }
}
