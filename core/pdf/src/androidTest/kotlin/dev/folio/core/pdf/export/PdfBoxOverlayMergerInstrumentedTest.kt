package dev.folio.core.pdf.export

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import dev.folio.core.common.Outcome
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** .claude/rules/pdf.md: reopen the merged output with PdfRenderer; check page count, size and the annotation pixel. */
@RunWith(AndroidJUnit4::class)
class PdfBoxOverlayMergerInstrumentedTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir = File(context.cacheDir, "merger-test").apply { mkdirs() }

    @Test
    fun merge_redMarkOnPageTwo_visibleOnlyThere() {
        val original = File(dir, "original.pdf").also { write(it, pages = 3) { canvas -> canvas.drawText("original", 40f, 60f, Paint()) } }
        val overlay =
            File(dir, "overlay.pdf").also {
                write(it, pages = 1) { canvas -> canvas.drawRect(MARK, MARK, MARK + SIZE, MARK + SIZE, red) }
            }
        val output = File(dir, "output.pdf")

        val result = PdfBoxOverlayMerger(context).merge(original, overlay, intArrayOf(1), output)

        assertThat(result).isInstanceOf(Outcome.Success::class.java)
        ParcelFileDescriptor.open(output, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                assertThat(renderer.pageCount).isEqualTo(3)
                for (i in 0 until renderer.pageCount) {
                    renderer.openPage(i).use { page ->
                        assertThat(page.width).isEqualTo(A4_WIDTH_PT)
                        assertThat(page.height).isEqualTo(A4_HEIGHT_PT)
                        assertThat(isRed(pixelAtMark(page))).isEqualTo(i == 1)
                    }
                }
            }
        }
    }

    private val red = Paint().apply { color = Color.rgb(220, 30, 30) }

    private fun write(
        file: File,
        pages: Int,
        draw: (android.graphics.Canvas) -> Unit,
    ) {
        val document = PdfDocument()
        repeat(pages) { index ->
            val page = document.startPage(PdfDocument.PageInfo.Builder(A4_WIDTH_PT, A4_HEIGHT_PT, index + 1).create())
            draw(page.canvas)
            document.finishPage(page)
        }
        file.outputStream().use(document::writeTo)
        document.close()
    }

    private fun pixelAtMark(page: PdfRenderer.Page): Int {
        val pixel = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        pixel.eraseColor(Color.WHITE)
        val center = MARK + SIZE / 2
        page.render(pixel, null, Matrix().apply { setTranslate(-center, -center) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return pixel.getPixel(0, 0).also { pixel.recycle() }
    }

    private fun isRed(argb: Int): Boolean = Color.red(argb) > RED_MIN && Color.green(argb) < OTHER_MAX && Color.blue(argb) < OTHER_MAX

    private companion object {
        const val A4_WIDTH_PT = 595
        const val A4_HEIGHT_PT = 842
        const val MARK = 400f
        const val SIZE = 40f
        const val RED_MIN = 180
        const val OTHER_MAX = 90
    }
}
