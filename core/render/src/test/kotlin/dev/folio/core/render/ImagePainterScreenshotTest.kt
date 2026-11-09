package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.AssetId
import dev.folio.core.model.ImageObject
import dev.folio.core.model.ObjectId
import dev.folio.core.model.RectF01
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class ImagePainterScreenshotTest {
    private val loads = ArrayList<AssetId>()
    private val images =
        ImageBitmaps({ id ->
            loads += id
            if (id == QUADRANTS) quadrantsPng() else null
        })
    private val renderer = PageRenderer(images = images)

    @Test
    fun screenshot_fullCroppedTurnedAndMissing() {
        val objects =
            listOf(
                ImageObject(ObjectId("full"), QUADRANTS, RectPt(20f, 20f, 220f, 120f), 0f, RectF01.FULL),
                // Right half only: green over yellow.
                ImageObject(ObjectId("crop"), QUADRANTS, RectPt(240f, 20f, 340f, 120f), 0f, RectF01(0.5f, 0f, 1f, 1f)),
                ImageObject(ObjectId("turn"), QUADRANTS, RectPt(20f, 190f, 220f, 290f), 90f, RectF01.FULL),
                ImageObject(ObjectId("gone"), AssetId("0".repeat(64)), RectPt(260f, 160f, 340f, 240f), 30f, RectF01.FULL),
            )
        val page = ModelFixtures.page("p", objects)
        val region = RectPt(0f, 0f, 360f, 340f)
        val bitmap = Bitmap.createBitmap((region.widthPt * SCALE).toInt(), (region.heightPt * SCALE).toInt(), Bitmap.Config.ARGB_8888)

        renderer.draw(Canvas(bitmap), page.toRef(), PageContent.of(page), region, SCALE, RenderTarget.EXPORT_RASTER)

        assertThat(pixel(bitmap, 60f, 45f)).isEqualTo(RED)
        assertThat(pixel(bitmap, 180f, 95f)).isEqualTo(YELLOW)
        assertThat(pixel(bitmap, 265f, 45f)).isEqualTo(GREEN)
        assertThat(pixel(bitmap, 265f, 95f)).isEqualTo(YELLOW)
        // Turned clockwise: the red top-left quadrant ends up top-right of the turned extent (x 70..170, y 140..340).
        assertThat(pixel(bitmap, 145f, 165f)).isEqualTo(RED)
        assertThat(pixel(bitmap, 300f, 200f)).isEqualTo(0xFFE3E5EA.toInt())
        bitmap.captureRoboImage("src/test/screenshots/ImagePainter_fullCroppedTurnedAndMissing.png")
    }

    @Test
    fun bitmap_repeatedRequests_decodeOncePerSampleSize() {
        val first = images.bitmap(QUADRANTS, 50, 25)
        val again = images.bitmap(QUADRANTS, 40, 20)
        val finer = images.bitmap(QUADRANTS, 200, 100)

        assertThat(first!!.width).isEqualTo(50)
        assertThat(again).isSameInstanceAs(first)
        assertThat(finer!!.width).isEqualTo(200)
        // Bounds once, then one decode per sample size; a coarser request is served by the finer decode.
        assertThat(images.bitmap(QUADRANTS, 100, 50)).isSameInstanceAs(finer)
        assertThat(loads).hasSize(3)
    }

    @Test
    fun cachedOnly_neverLoads_servesFinerOrCoarserDecodes() {
        val peek = images.cachedOnly

        assertThat(peek.bitmap(QUADRANTS, 50, 25)).isNull()
        assertThat(loads).isEmpty()
        val coarse = images.bitmap(QUADRANTS, 50, 25)
        loads.clear()

        // Finer wanted than decoded: the coarser decode stands in; coarser wanted: the finer one.
        assertThat(peek.bitmap(QUADRANTS, 200, 100)).isSameInstanceAs(coarse)
        assertThat(peek.bitmap(QUADRANTS, 20, 10)).isSameInstanceAs(coarse)
        assertThat(peek.bitmap(AssetId("0".repeat(64)), 50, 25)).isNull()
        assertThat(loads).isEmpty()
    }

    @Test
    fun sampleSize_wantedPixelsAndSideLimit() {
        val small = ImageBitmaps({ null }, maxSidePx = 1000)

        assertThat(small.sampleSize(4000, 3000, 900, 700)).isEqualTo(4)
        assertThat(small.sampleSize(4000, 3000, 1100, 800)).isEqualTo(4) // 2 would give 2000 px, over the side limit
        assertThat(small.sampleSize(800, 600, 2000, 2000)).isEqualTo(1)
    }

    private fun pixel(
        bitmap: Bitmap,
        xPt: Float,
        yPt: Float,
    ): Int = bitmap.getPixel((xPt * SCALE).toInt(), (yPt * SCALE).toInt())

    // 200 x 100: red, green on top; blue, yellow below.
    private fun quadrantsPng(): ByteArray {
        val b = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint()
        listOf(RED to (0 to 0), GREEN to (100 to 0), BLUE to (0 to 50), YELLOW to (100 to 50)).forEach { (argb, at) ->
            p.color = argb
            c.drawRect(at.first.toFloat(), at.second.toFloat(), at.first + 100f, at.second + 50f, p)
        }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private companion object {
        const val SCALE = 1.5f
        val QUADRANTS = AssetId("a".repeat(64))
        const val RED = 0xFFDC2626.toInt()
        const val GREEN = 0xFF16A34A.toInt()
        const val BLUE = 0xFF2563EB.toInt()
        const val YELLOW = 0xFFEAB308.toInt()
    }
}
