package dev.folio.core.render

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.google.common.truth.Truth.assertThat
import dev.folio.core.model.BrushKind
import dev.folio.core.model.BrushSpec
import dev.folio.core.model.InkStroke
import dev.folio.core.model.InputTool
import dev.folio.core.model.ObjectId
import dev.folio.core.model.StrokeInputs
import dev.folio.core.model.geometry.RectPt
import dev.folio.core.testing.ModelFixtures
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PageRendererTest {
    private val renderer = PageRenderer(ink = BoundsPainter())
    private val page =
        ModelFixtures.page(
            "p",
            listOf(
                stroke("blue", 60f, 120f, 300f, 160f, BLUE),
                stroke("red", 200f, 100f, 260f, 400f, RED), // above blue in z-order
                stroke("edge", 560f, 700f, 640f, 760f, BLUE), // crosses the right page edge: clipped
            ),
        )

    @Test
    fun screenshot_exportRaster_paperTemplateObjectsInZOrder_clippedToFrame() {
        val region = RectPt(0f, 0f, 700f, 860f) // wider than A4: the outside stays transparent
        render(region, RenderTarget.EXPORT_RASTER).captureRoboImage("src/test/screenshots/PageRenderer_exportRaster.png")
    }

    @Test
    fun screenContent_isTransparentBetweenObjects_backgroundHasNoObjects() {
        val region = RectPt(0f, 0f, 595f, 842f)
        val content = render(region, RenderTarget.SCREEN_CONTENT)
        val background = render(region, RenderTarget.SCREEN_BACKGROUND)

        assertThat(Color.alpha(content.getPixel(px(20f), px(20f)))).isEqualTo(0)
        assertThat(content.getPixel(px(230f), px(140f))).isEqualTo(RED) // red covers blue
        assertThat(background.getPixel(px(230f), px(140f))).isNotEqualTo(RED)
        assertThat(background.getPixel(px(20f), px(20f))).isEqualTo(Color.WHITE)
    }

    @Test
    fun draw_regionOffset_mapsRegionTopLeftToCanvasOrigin() {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        renderer.draw(
            Canvas(bitmap),
            page.toRef(),
            PageContent.of(page),
            RectPt(200f, 100f, 250f, 150f),
            SCALE,
            RenderTarget.SCREEN_CONTENT,
        )

        assertThat(bitmap.getPixel(10, 10)).isEqualTo(RED)
        assertThat(PageRenderer.isPlainPaper(page.toRef(), RenderTarget.SCREEN_BACKGROUND)).isFalse()
    }

    private fun render(
        region: RectPt,
        target: RenderTarget,
    ): Bitmap {
        val bitmap = Bitmap.createBitmap(px(region.widthPt), px(region.heightPt), Bitmap.Config.ARGB_8888)
        renderer.draw(Canvas(bitmap), page.toRef(), PageContent.of(page), region, SCALE, target)
        return bitmap
    }

    private fun px(pt: Float): Int = (pt * SCALE).toInt()

    private fun stroke(
        id: String,
        left: Float,
        top: Float,
        right: Float,
        bottom: Float,
        argb: Int,
    ): InkStroke {
        val inputs =
            StrokeInputs(floatArrayOf(left, right), floatArrayOf(top, bottom), floatArrayOf(0f, 5f), null, null, null, InputTool.SYNTHETIC)
        return InkStroke(ObjectId(id), BrushSpec(BrushKind.MARKER, argb, 2f, 1), inputs, RectPt(left, top, right, bottom))
    }

    /** Fills the stroke bounds with the brush color: stands in for androidx.ink, whose natives do not load on the JVM. */
    private class BoundsPainter : InkPainter {
        private val paint = Paint()

        override fun draw(
            canvas: Canvas,
            stroke: InkStroke,
            content: PageContent,
            index: Int,
            toDevice: Matrix,
        ) {
            paint.color = stroke.brush.argb
            val b = stroke.bounds
            canvas.drawRect(b.left, b.top, b.right, b.bottom, paint)
        }
    }

    private companion object {
        const val SCALE = 1f
        const val RED = 0xFFD32F2F.toInt()
        const val BLUE = 0xFF1F4FB8.toInt()
    }
}
